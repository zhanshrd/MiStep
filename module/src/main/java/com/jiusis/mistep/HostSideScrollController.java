package com.jiusis.mistep;

import android.content.Intent;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XposedHelpers;

/**
 * 侧栏滚动子系统（「无限小窗」，上游 v1.0.4 语义移植）。
 * 上游把这一簇（约 620 行 + 状态字段）直接写在 MiStepHost 里；fork 在 1.0.3 移植时
 * MiStepHost 已拆成纯 shim 壳，且 HostSwapController / HostTopTaskController 都逼近 1000 行，
 * 所以整簇落为独立 controller。代码逐方法搬自上游 1.0.4 MiStepHost（掩码保护 + 宿主成员表
 * 自动推导改写，宿主成员经 h.*，宿主静态经 MiStepHost.*），未改语义；匿名类内的 this 保留。
 *
 * 模型：每个 Slot 始终持有同一个 TaskView/controller/listener；sideOrder 是滚动行的逻辑序列，
 * 行号写在 Slot#sideRow（<0 = 固定格，不参与滚动）。可见行映射到 slots[] 前部（1..cellCount）
 * 的物理下标，供交换/归还等按物理下标工作的路径使用；只在滚动停稳（mapSideSlots）后才重映射。
 * TaskView 容器表面挂进一个固定显示坐标的视口容器层（sideViewport），视口外的行裁剪且不可获焦。
 */
final class HostSideScrollController {
    private final MiStepHost h;

    HostSideScrollController(MiStepHost host) { h = host; }

    final java.util.ArrayList<Slot> sideOrder = new java.util.ArrayList<>();
    boolean sideScrolling, sideArmed, sideCaptured;
    private Object pendingSideView;
    /** 当前行偏移（单位=行）；滚动中为小数，停稳后取整。外部路径只读。 */
    float sideOffset;
    private float sideDownX, sideDownY, sideLastPosition, savedSideOffset;
    private final java.util.ArrayList<Slot> sideAttached = new java.util.ArrayList<>();
    private android.animation.ValueAnimator sideScrollAnimator;
    private android.view.VelocityTracker sideVelocity;
    private android.widget.OverScroller sideFling;
    private Runnable sideFlingFrame;
    boolean sideCompacting, sideDesktopRevealing;
    private long sideRevealGeneration;
    private final java.util.IdentityHashMap<Slot, Float> sideCompactionShift = new java.util.IdentityHashMap<>();
    private float sideCompactionProgress;
    Object sideViewport;
    private final Rect sideViewportCrop = new Rect();
    FrameLayout sideInputViewport;

    void updateSideInputViewportBounds() {
        if (sideInputViewport != null) sideInputViewport.setClipBounds(sideViewportBounds());
    }

    FrameLayout createSideInputViewport() {
        FrameLayout viewport = new FrameLayout(h.ctx) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                // setClipBounds limits drawing, but child touch dispatch uses full view bounds.
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && !isInSideViewport(event.getX(), event.getY())) return false;
                return super.dispatchTouchEvent(event);
            }
        };
        viewport.setClipChildren(false);
        return viewport;
    }

    boolean presentsSlot(Slot slot) {
        if (slot.index == MiStepHost.SLOT_TOP) return true;
        return slot.sideRow >= 0 ? slot.sideShown : slot.index < h.CELL_SLOT_COUNT;
    }

    boolean canMoveSideRows() {
        return h.scene == MiStepHost.ACTIVE && h.screenOn && !h.keyguardLocked && !h.workspaceEntering
                && !h.taskCellsCovered && !h.transferBusy && h.promotion == null && h.cellMotion == null
                && h.sideDismiss == null && h.carrierSlotIndex < 0;
    }

/** 点击落在视口外的滚动行上：先停稳滚动并把该行滚进视口（或回传到主区），再按物理下标处理。 */
    boolean prepareSideRowTap(Slot slot) {
        if (slot.sideRow < 0) return true;
        if (sideCompacting || sideDesktopRevealing) return false;
        if (sideCaptured || isSideMotionRunning()) finishSideScroll();
        if (slot.index < h.CELL_SLOT_COUNT) return true;
        if (!slot.occupied) return false;
        if (!sideScrolling) {
            h.returnToSystem(slot, true, () -> {
                h.clearSlot(slot);
                h.finishPendingSuspend();
            }, error -> MiStepHost.log("open hidden side task=" + slot.taskId + ": " + error));
            return false;
        }
        sideOffset = Math.min(maxSideOffset(), slot.sideRow);
        mapSideSlots((int) sideOffset);
        layoutSideViewport(false);
        return true;
    }

    boolean isInSideViewport(float x, float y) {
        return h.layout != null && h.layout.sideViewportContains(x, y);
    }

    Rect sideViewportBounds() {
        return new Rect(h.layout.sideViewportLeft, h.layout.sideViewportTop,
                h.layout.sideViewportRight, h.layout.sideViewportBottom);
    }

    boolean trackSideScroll(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            recycleSideVelocity();
            sideCaptured = false;
            sideDownX = event.getX();
            sideDownY = event.getY();
            sideLastPosition = h.layout.tabletLandscape ? sideDownX : sideDownY;
            sideArmed = sideScrolling && canMoveSideRows()
                    && (maxSideOffset() > 0 || isSideMotionRunning())
                    && isInSideViewport(sideDownX, sideDownY);
            if (sideArmed) {
                sideVelocity = android.view.VelocityTracker.obtain();
                if (isSideMotionRunning()) {
                    if (!sideCompacting && !sideDesktopRevealing) cancelSideAnimator();
                    sideCaptured = true;
                }
                sideVelocity.addMovement(event);
            }
            return sideCaptured;
        }
        if (!sideArmed && !sideCaptured) return false;
        if (sideVelocity != null) sideVelocity.addMovement(event);
        if (!canMoveSideRows() || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            boolean consumed = sideCaptured;
            int velocity = 0;
            if (consumed && action == MotionEvent.ACTION_UP && sideVelocity != null) {
                ViewConfiguration config = ViewConfiguration.get(h.ctx);
                sideVelocity.computeCurrentVelocity(1000, config.getScaledMaximumFlingVelocity());
                int pointer = event.getPointerId(0);
                float pixelsPerSecond = -(h.layout.tabletLandscape
                        ? sideVelocity.getXVelocity(pointer) : sideVelocity.getYVelocity(pointer));
                if (Math.abs(pixelsPerSecond) >= config.getScaledMinimumFlingVelocity())
                    velocity = Math.round(pixelsPerSecond);
            }
            sideArmed = sideCaptured = false;
            recycleSideVelocity();
            if (!consumed && h.compactPending) h.requestCompaction();
            if (consumed && !sideCompacting && !sideDesktopRevealing) {
                if (canMoveSideRows()) {
                    if (!flingSideRows(velocity)) settleSideScroll();
                }
                else finishSideScroll();
            }
            return consumed;
        }
        if (sideCompacting || sideDesktopRevealing) return sideCaptured;
        if (action != MotionEvent.ACTION_MOVE) return sideCaptured;
        boolean horizontal = h.layout.tabletLandscape;
        float position = horizontal ? event.getX() : event.getY();
        if (!sideCaptured) {
            float dx = event.getX() - sideDownX;
            float dy = event.getY() - sideDownY;
            float along = horizontal ? dx : dy;
            float across = horizontal ? dy : dx;
            int slop = ViewConfiguration.get(h.ctx).getScaledTouchSlop();
            if (Math.max(Math.abs(along), Math.abs(across)) <= slop) return false;
            sideArmed = false;
            if (Math.abs(along) <= Math.abs(across)) {
                recycleSideVelocity();
                return false;
            }
            sideCaptured = true;
            sideLastPosition = (horizontal ? sideDownX : sideDownY) + Math.copySign(slop, along);
        }
        setSideOffset(sideOffset + (sideLastPosition - position) / sidePitch());
        sideLastPosition = position;
        return true;
    }

    float sidePitch() {
        return h.layout.sideScrollPitch();
    }

    int maxSideOffset() {
        int rows = h.layout.cellCount;
        for (int i = sideOrder.size() - 1; i >= 0; i--) {
            Slot slot = sideOrder.get(i);
            if (slot.occupied || slot.launching || slot.promoting) {
                // Include one ready empty row after the final task.
                rows = Math.max(rows, Math.min(sideOrder.size(), i + 2));
                break;
            }
        }
        return rows - h.layout.cellCount;
    }

    void setSideOffset(float offset) {
        float next = Math.min(maxSideOffset(), Math.max(0f, offset));
        if (next == sideOffset) return;
        sideOffset = next;
        layoutSideViewport(false);
    }

    void cancelSideAnimator() {
        android.animation.ValueAnimator animator = sideScrollAnimator;
        sideScrollAnimator = null;
        if (animator != null) animator.cancel();
        if (sideFlingFrame != null) {
            if (h.root != null) h.root.removeCallbacks(sideFlingFrame);
            sideFlingFrame = null;
            sideFling.forceFinished(true);
        }
        sideCompacting = false;
        sideCompactionShift.clear();
        if (sideDesktopRevealing) {
            sideDesktopRevealing = false;
            ++sideRevealGeneration;
        }
    }

    boolean isSideMotionRunning() {
        return sideScrollAnimator != null || sideFlingFrame != null || sideDesktopRevealing;
    }

    void recycleSideVelocity() {
        if (sideVelocity != null) {
            sideVelocity.recycle();
            sideVelocity = null;
        }
    }

    boolean flingSideRows(int velocity) {
        final int start = Math.round(sideOffset * sidePitch());
        final int end = Math.round(maxSideOffset() * sidePitch());
        if (velocity == 0 || h.root == null || end <= 0 || (velocity < 0 && start <= 0)
                || (velocity > 0 && start >= end)
                || !android.animation.ValueAnimator.areAnimatorsEnabled()) return false;
        cancelSideAnimator();
        if (sideFling == null) sideFling = new android.widget.OverScroller(h.ctx);
        sideFling.fling(0, start, 0, velocity, 0, 0, 0, end);
        final FrameLayout host = h.root;
        final long epoch = h.sceneEpoch;
        sideFlingFrame = new Runnable() {
            @Override public void run() {
                if (sideFlingFrame != this) return;
                if (h.root != host || h.sceneEpoch != epoch || !canMoveSideRows()) {
                    finishSideScroll();
                    return;
                }
                if (sideFling.computeScrollOffset()) {
                    setSideOffset(sideFling.getCurrY() / sidePitch());
                    host.postOnAnimation(this);
                } else {
                    sideFlingFrame = null;
                    settleSideScroll();
                }
            }
        };
        MiStepHost.log("side fling start offset=" + sideOffset + " velocity=" + velocity
                + " projected=" + (sideFling.getFinalY() / sidePitch()));
        host.postOnAnimation(sideFlingFrame);
        return true;
    }

    void settleSideScroll() {
        final int target = Math.min(maxSideOffset(), Math.round(sideOffset));
        if (sideOffset == target || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            finishSideScroll();
            return;
        }
        animateSideOffset(target, 160L, this::finishSideScroll);
    }

    void animateSideOffset(int target, long duration, Runnable done) {
        if (sideOffset == target || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            setSideOffset(target);
            done.run();
            return;
        }
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(sideOffset, target);
        sideScrollAnimator = animator;
        animator.setDuration(duration);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(frame -> {
            if (sideScrollAnimator == animator && canMoveSideRows())
                setSideOffset((float) frame.getAnimatedValue());
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (sideScrollAnimator != animator) return;
                sideScrollAnimator = null;
                done.run();
            }
        });
        animator.start();
    }

    void finishSideScroll() {
        boolean compacting = sideCompacting;
        cancelSideAnimator();
        recycleSideVelocity();
        sideArmed = sideCaptured = false;
        if (!sideScrolling && !compacting) return;
        commitSideScrollPosition();
        if (compacting) for (Slot slot : sideAttached) h.updateHint(slot);
        if (h.compactPending) h.requestCompaction();
    }

    void commitSideScrollPosition() {
        if (sideScrolling) {
            sideOffset = Math.min(maxSideOffset(), Math.round(sideOffset));
            mapSideSlots((int) sideOffset);
            layoutSideViewport(false);
            syncSideViewportFocus();
        } else {
            layoutSideViewport(true);
        }
        h.publishDesktopSideTasks();
        MiStepHost.log("side scroll settled row=" + (int) sideOffset + h.describeTasks());
    }

    boolean sideRowInPlace(Slot slot) {
        return !sideCaptured && !isSideMotionRunning()
                && slot.index < h.CELL_SLOT_COUNT && slot.rect != null
                && slot.rect[0] >= h.layout.sideViewportLeft && slot.rect[2] <= h.layout.sideViewportRight
                && slot.rect[1] >= h.layout.sideViewportTop && slot.rect[3] <= h.layout.sideViewportBottom;
    }

    void revealSideTaskForDesktop(Slot slot, android.os.Bundle binding) {
        final FrameLayout host = h.root;
        final int old = Math.round(sideOffset);
        final long epoch = h.sceneEpoch;
        final int target = Math.min(maxSideOffset(), Math.max(0,
                Math.min(slot.sideRow, Math.max(old, slot.sideRow - h.layout.cellCount + 1))));
        cancelSideAnimator();
        recycleSideVelocity();
        sideArmed = sideCaptured = false;
        sideDesktopRevealing = true;
        final long generation = ++sideRevealGeneration;
        MiStepHost.log("desktop side reveal start task=" + slot.taskId + " from=" + sideOffset + " to=" + target);
        animateSideOffset(target, Math.min(430L, 235L + 45L * Math.abs(target - old)), () -> {
            if (h.root != host || generation != sideRevealGeneration || !sideDesktopRevealing) return;
            commitSideScrollPosition();
            Runnable afterFrame = () -> {
                if (h.root != host || generation != sideRevealGeneration || !sideDesktopRevealing) return;
                sideDesktopRevealing = false;
                if (h.sceneEpoch == epoch && h.scene == MiStepHost.ACTIVE && sideRowInPlace(slot)) {
                    MiStepHost.log("desktop side reveal committed row=" + (int) sideOffset + " task=" + slot.taskId);
                    h.preannounceDesktopLaunch(binding);
                }
                if (h.compactPending) h.requestCompaction();
            };
            if (host.isHardwareAccelerated() && host.isAttachedToWindow()
                    && host.getViewTreeObserver().isAlive()) {
                host.getViewTreeObserver().registerFrameCommitCallback(() -> h.main.post(afterFrame));
                host.invalidate();
                h.main.postDelayed(() -> {
                    if (h.root == host && generation == sideRevealGeneration && sideDesktopRevealing) {
                        sideDesktopRevealing = false;
                        MiStepHost.log("desktop side reveal frame not committed task=" + slot.taskId);
                        if (h.compactPending) h.requestCompaction();
                    }
                }, 350L);
            } else host.postOnAnimation(afterFrame);
        });
    }

    /**
     * A Slot always owns the same TaskView, controller and listener. Only after scrolling
     * settles do the visible owners take the physical indices used by swaps.
     */
    void mapSideSlots(int firstRow) {
        Slot[] mapped = h.slots.clone();
        int tail = sideOrder.size() == h.slots.length - 2 ? h.CELL_SLOT_COUNT : MiStepHost.SLOT_COUNT;
        for (int i = 0; i < sideOrder.size(); i++) {
            Slot owner = sideOrder.get(i);
            owner.sideRow = i;
            int viewportRow = i - firstRow;
            if (tail == MiStepHost.SLOT_TOP) tail++; // TOP is independent of the scrolling row.
            int position = viewportRow >= 0 && viewportRow < h.layout.cellCount
                    ? 1 + viewportRow : tail++;
            mapped[position] = owner;
        }
        for (int i = 1; i < mapped.length; i++) {
            mapped[i].position(i);
            h.syncSlotWindowState(mapped[i]);
        }
        h.slots = mapped;
    }

    void layoutSideViewport(boolean all) {
        if (h.root == null || h.layout == null || sideOrder.isEmpty()) return;
        if (all) {
            ensureSideViewport();
            for (Slot slot : sideOrder) applySideRow(slot, sideOffset, sideScrolling);
            syncSideViewportFocus();
        } else {
            for (int i = sideAttached.size() - 1; i >= 0; i--) {
                Slot slot = sideAttached.get(i);
                if (!nearSideViewport(slot.sideRow, sideOffset)) {
                    sideAttached.remove(i);
                    applySideRow(slot, sideOffset, false);
                }
            }
            int start = (int) Math.floor(sideOffset);
            int first = Math.max(0, start - 2);
            int end = Math.min(sideOrder.size(), start + h.layout.cellCount + 3);
            for (int i = first; i < end; i++) applySideRow(sideOrder.get(i), sideOffset, true);
        }
        h.root.invalidate();
    }

    /** Keep near rows warm without letting a clipped activity or its dialog take focus. */
    void syncSideViewportFocus() {
        final long epoch = h.sceneEpoch;
        h.executeOnShell(() -> {
            if (h.sceneEpoch != epoch || (h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING)
                    || !h.screenOn || h.keyguardLocked) return;
            try {
                Object wct = null;
                int clipped = 0;
                for (Slot slot : h.slots) {
                    if (slot.sideRow < 0 || !slot.occupied || slot.parked || slot.promoting
                            || slot.launching || slot.closing || slot.frozen) continue;
                    final int id = slot.taskId;
                    final Object token = slot.taskToken;
                    if (token == null || !h.controllerOwns(slot, id, token)
                            || MiStepHost.windowModeOf(h.currentOrganizedTaskInfo(slot, id, token)) != 6) continue;
                    if (wct == null) wct = Class.forName("android.window.WindowContainerTransaction")
                            .getDeclaredConstructor().newInstance();
                    boolean focusable = slot.sideShown;
                    XposedHelpers.callMethod(wct, "setFocusable", token, focusable);
                    if (!focusable) clipped++;
                }
                if (h.sceneEpoch == epoch && wct != null)
                    h.applyWct("side-viewport-focus-outside-" + clipped, wct);
            } catch (Throwable error) {
                MiStepHost.log("side viewport focus update: " + error);
            }
        });
    }

    /** Reparent in the draw transaction when ViewRootImpl replaces the native host surface. */
    void observeSideViewportRoot(View observedRoot) {
        try {
            Object viewRoot = XposedHelpers.callMethod(observedRoot, "getViewRootImpl");
            Class<?> callback = XposedHelpers.findClass(
                    "android.view.ViewRootImpl$SurfaceChangedCallback", viewRoot.getClass().getClassLoader());
            Object observer = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (observedRoot == h.root && sideViewport != null && !sideViewportCrop.isEmpty()
                                && args != null && args.length == 1
                                && ("surfaceCreated".equals(name) || "surfaceReplaced".equals(name))) {
                            try {
                                if (XposedHelpers.callMethod(observedRoot, "getViewRootImpl") != viewRoot)
                                    return null;
                                stageSideViewport(args[0], h.liveHostRootSurface(), sideViewportCrop);
                            } catch (Throwable error) {
                                MiStepHost.log("side viewport host refresh failed: " + error);
                            }
                        }
                        return null;
                    });
            XposedHelpers.callMethod(viewRoot, "addSurfaceChangedCallback", observer);
            MiStepHost.log("side viewport host callback registered");
        } catch (Throwable error) {
            MiStepHost.log("side viewport host callback unavailable: " + error);
        }
    }

    void stageSideViewport(Object tx, Object host, Rect crop) {
        XposedHelpers.callMethod(tx, "reparent", sideViewport, host);
        XposedHelpers.callMethod(tx, "setLayer", sideViewport, -2);
        XposedHelpers.callMethod(tx, "setWindowCrop", sideViewport, crop);
        if (h.retainedResumeStaging()) XposedHelpers.callMethod(tx, "setAlpha", sideViewport, 0f);
        XposedHelpers.callMethod(tx, "show", sideViewport);
    }

    /** Fixed in display coordinates; SurfaceView may update its child position asynchronously. */
    boolean ensureSideViewport() {
        Object tx = null;
        try {
            Object host = h.liveHostRootSurface();
            if (sideViewport == null
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(sideViewport, "isValid"))) {
                h.closeSurfaceObject(sideViewport, "release");
                Object builder = Class.forName("android.view.SurfaceControl$Builder")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(builder, "setName", "MiStepSideViewport");
                XposedHelpers.callMethod(builder, "setParent", host);
                XposedHelpers.callMethod(builder, "setContainerLayer");
                sideViewport = XposedHelpers.callMethod(builder, "build");
                sideViewportCrop.setEmpty();
            }
            Rect crop = sideViewportBounds();
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            stageSideViewport(tx, host, crop);
            XposedHelpers.callMethod(tx, "apply");
            sideViewportCrop.set(crop);
            return true;
        } catch (Throwable error) {
            MiStepHost.log("side viewport unavailable: " + error);
            return false;
        } finally { h.closeSurfaceObject(tx, "close"); }
    }

    void attachSideViewport(Slot slot, Object surface) {
        Object viewport = sideViewport;
        if (surface == null || viewport == null || sideViewportCrop.isEmpty()
                || surface == slot.sideViewportSurface && viewport == slot.sideViewportParent) return;
        Object tx = null;
        try {
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(tx, "reparent", surface, viewport);
            XposedHelpers.callMethod(tx, "apply");
            slot.sideViewportSurface = surface;
            slot.sideViewportParent = viewport;
        } catch (Throwable error) { MiStepHost.log("side viewport slot=" + slot.index + ": " + error); }
        finally { h.closeSurfaceObject(tx, "close"); }
    }

    boolean nearSideViewport(int row, float offset) {
        int start = (int) Math.floor(offset);
        return row >= start - 2 && row < start + h.layout.cellCount + 3;
    }

    boolean clipToSideViewport(Rect rect) {
        return rect.intersect(h.layout.sideViewportLeft, h.layout.sideViewportTop,
                h.layout.sideViewportRight, h.layout.sideViewportBottom);
    }

    void applySideRow(Slot slot, float offset, boolean scrolling) {
        Float shiftFrom = sideCompacting ? sideCompactionShift.get(slot) : null;
        if (shiftFrom != null) offset -= shiftFrom * (1f - sideCompactionProgress);
        int row = slot.sideRow - (int) Math.floor(offset);
        int[] rect = h.layout.sideScrollBounds(slot.sideRow, offset);
        boolean near = scrolling ? nearSideViewport(slot.sideRow, offset)
                : row >= 0 && row < h.layout.cellCount && !sideScrolling;
        Rect crop = new Rect(rect[0], rect[1], rect[2], rect[3]);
        boolean shown = near && clipToSideViewport(crop);
        boolean changed = shown != slot.sideShown;
        boolean preparationChanged = near != slot.sidePrepared;
        boolean preparing = near && preparationChanged;
        slot.sideShown = shown;
        slot.sidePrepared = near;
        slot.rect = rect;
        if (near) {
            if (!sideAttached.contains(slot)) sideAttached.add(slot);
        } else {
            sideAttached.remove(slot);
        }
        if (shown) crop.offset(-rect[0], -rect[1]);
        else crop.setEmpty();
        if (near) attachSideViewport(slot, MiStepHost.getSurfaceField(slot.controller, "mSurfaceControl"));
        placeSideView(slot.view, rect, crop);
        placeSideView(slot.hint, rect, crop);
        if (slot.view != null) {
            int visibility = near ? View.VISIBLE : View.INVISIBLE;
            if (slot.view.getVisibility() != visibility) slot.view.setVisibility(visibility);
        }
        if (changed || preparationChanged) h.updateHint(slot);
        else if (shown) h.refreshEmptyHintBackdrop(slot);
        if (preparing && slot.occupied) h.reapplyScale(slot);
        if (preparing && slot.occupied && !slot.taskVisible) {
            slot.visibilityRepair.reset();
            h.scheduleSideVisibilityRepair(slot, true);
        }
        h.updateTouchRegion(slot);
    }

    void placeSideView(View view, int[] rect, Rect crop) {
        if (view == null) return;
        view.setX(rect[0]);
        view.setY(rect[1]);
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && (params.width != rect[2] - rect[0] || params.height != rect[3] - rect[1])) {
            params.width = rect[2] - rect[0];
            params.height = rect[3] - rect[1];
            view.setLayoutParams(params);
        }
        view.setClipBounds(crop);
    }

    /** All busy operations retain their physical slots until their final frame. */
    boolean compactScrollingSideRows() {
        final boolean enabled = h.workspaceOptions.scrollSideTasks;
        if (!enabled && sideOrder.isEmpty()) return false;
        if (h.slots[1].view == null || h.slots[MiStepHost.SLOT_TOP].view == null) return true;
        if (!ensureSideViewport()) return true;
        if (sideOrder.isEmpty()) {
            int capacity = DeviceProfile.isTablet(h.ctx) ? MiStepLayout.MAX_CELL_COUNT : h.layout.cellCount;
            for (int i = 1; i <= capacity; i++) sideOrder.add(h.slots[i]);
        }
        final boolean wasScrolling = sideScrolling;
        final float previousOffset = sideOffset;
        final int requestedRow = Math.round(wasScrolling ? previousOffset : savedSideOffset);
        final Slot anchor = sideOrder.get(Math.min(sideOrder.size() - 1, requestedRow));
        // Once created, these rows keep their TaskViews across the toggle. Fixed
        // mode compacts the current physical cells and keeps the other rows parked.
        final int rowCount = enabled ? sideOrder.size() : h.layout.cellCount;
        sideOrder.subList(0, rowCount).sort((a, b) -> Boolean.compare(b.occupied, a.occupied));
        sideScrolling = enabled;
        if (enabled) {
            int anchorRow = anchor.occupied ? sideOrder.indexOf(anchor) : requestedRow;
            sideOffset = Math.min(maxSideOffset(), anchorRow);
        } else {
            if (wasScrolling || anchor.occupied)
                savedSideOffset = anchor.occupied ? sideOrder.indexOf(anchor) : requestedRow;
            sideOffset = 0;
        }
        if (wasScrolling == enabled && canMoveSideRows()
                && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            for (int i = 0; i < rowCount; i++) {
                Slot slot = sideOrder.get(i);
                float delta = (slot.sideRow - previousOffset) - (i - sideOffset);
                if (slot.occupied && delta != 0f && (!enabled
                        || nearSideViewport(slot.sideRow, previousOffset)
                        || nearSideViewport(i, sideOffset))) sideCompactionShift.put(slot, delta);
            }
        }
        mapSideSlots((int) sideOffset);
        if (!sideCompactionShift.isEmpty()) animateSideCompaction();
        else layoutSideViewport(true);
        Slot last = sideOrder.get(sideOrder.size() - 1);
        if (enabled && last.occupied) appendSideRow();
        return true;
    }

    /** Keep each original SurfaceView in motion while its row fills the gap. */
    void animateSideCompaction() {
        final FrameLayout host = h.root;
        final long epoch = h.sceneEpoch;
        sideCompacting = true;
        sideCompactionProgress = 0f;
        for (Slot row : sideOrder) {
            applySideRow(row, sideOffset, sideScrolling);
            if (!row.occupied) h.updateHint(row);
        }
        android.animation.ValueAnimator animation = android.animation.ValueAnimator.ofFloat(0f, 1f);
        sideScrollAnimator = animation;
        animation.setDuration(240L);
        animation.setInterpolator(new android.view.animation.PathInterpolator(.22f, .7f, .12f, 1f));
        animation.addUpdateListener(frame -> {
            if (sideScrollAnimator != animation || h.root != host || h.sceneEpoch != epoch) return;
            sideCompactionProgress = (float) frame.getAnimatedValue();
            for (Slot slot : sideCompactionShift.keySet()) applySideRow(slot, sideOffset, sideScrolling);
            host.invalidate();
        });
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animator) {
                if (sideScrollAnimator != animation) return;
                finishSideScroll();
                MiStepHost.log("side compaction settled");
            }
        });
        MiStepHost.log("side compaction start moving=" + sideCompactionShift.size());
        animation.start();
    }

    void appendSideRow() {
        if (pendingSideView != null) return;
        final Object request = new Object();
        pendingSideView = request;
        try {
            ShellCompat.createTaskView(h.factory, h.ctx, h.executor, taskView ->
                    h.main.post(() -> finishAppendingSideRow(request, taskView)));
        } catch (Throwable error) {
            pendingSideView = null;
            MiStepHost.log("add side row TaskView: " + error);
            return;
        }
        h.main.postDelayed(() -> {
            if (pendingSideView != request) return;
            pendingSideView = null;
            MiStepHost.log("add side row TaskView: callback timed out");
        }, MiStepHost.LAUNCH_TIMEOUT_MS);
    }

    void finishAppendingSideRow(Object request, Object taskView) {
        if (pendingSideView != request || h.root == null || MiStepHost.sInstance != h) {
            releaseUnattachedSideView(taskView);
            return;
        }
        pendingSideView = null;
        Slot[] existing = h.slots;
        Slot owner = new Slot(existing.length);
        owner.sideRow = sideOrder.size();
        Slot[] expanded = java.util.Arrays.copyOf(existing, existing.length + 1);
        expanded[owner.index] = owner;
        sideOrder.add(owner);
        h.slots = expanded;
        if (h.onTaskViewCreated(owner, taskView, false)) {
            h.requestCompaction();
            return;
        }
        sideOrder.remove(owner);
        sideAttached.remove(owner);
        h.slots = existing;
        if (owner.hint != null) sideInputViewport.removeView(owner.hint);
        if (owner.view != null) sideInputViewport.removeView(owner.view);
        releaseUnattachedSideView(taskView);
    }

    void releaseUnattachedSideView(Object taskView) {
        try { XposedHelpers.callMethod(taskView, "release"); }
        catch (Throwable error) { MiStepHost.log("unused side TaskView release: " + error); }
    }
}
