package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** 格子布局构建 / 单元背景 / 紧缩 / 格移动动画 / 单元表面生命周期 */
final class HostCellLayoutController {
    private final MiStepHost h;

    HostCellLayoutController(MiStepHost host) { h = host; }

    void buildLayout() {
        h.workspaceSettings.refresh();
        h.readDisplayGeometry();
        int w = h.screenW;
        int hb = h.screenH;
        int pendingSide = MiStepHost.pendingMainSide;
        if (pendingSide >= 0) {
            MiStepHost.pendingMainSide = -1;
            h.mainOnRight = pendingSide == 1;
            MiStepHost.log("layout mainOnRight from gesture edge=" + h.mainOnRight);
        }
        MiStepHost.log("device: " + DeviceProfile.describe(h.ctx));
        float layoutDensity = h.ctx.getResources().getDisplayMetrics().density;
        h.layout = MiStepLayout.of(w, hb, h.mainOnRight, layoutDensity, DeviceProfile.isTablet(h.ctx));
        h.CELL_SLOT_COUNT = 1 + h.layout.cellCount;
        h.mainOnRight = h.layout.mainOnRight;
        MiStepHost.log("layout: " + h.layout.describe()
                + " mainScale=" + h.mainScale() + " cellScale=" + h.cellScale());

        h.root = new FrameLayout(h.ctx) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (h.transitionInput != null && h.transitionInput.consume(event.getActionMasked())) return true;
                return super.dispatchTouchEvent(event);
            }
        };
        h.transitionInput = new MiStepTransitionInput(h.root, h::acquireTransitionInputWindow, h::releaseTransitionInputWindow, blocked -> {
            h.liveSwapInputBlocked = blocked;
            for (Slot slot : h.slots) if (slot != null && slot.index > 0) h.updateTouchRegion(slot);
            if (h.root != null) { h.root.requestLayout(); h.root.invalidate(); }
        }, MiStepHost::log);
        h.root.setClipChildren(false);
        h.root.setBackgroundColor(0x00000000);
        h.installHostTouchRegion();

        h.wm = (WindowManager) h.ctx.getSystemService(Context.WINDOW_SERVICE);
        h.lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        h.lp.gravity = Gravity.TOP | Gravity.START;
        h.lp.setTitle("MiStepHost");
        HostWindowPolicy.configure(h.lp);
        // APPLICATION_OVERLAY 默认被状态栏/cutout 顶下去（真机 122px）。两者都关掉后
        // 窗口坐标系才等于屏幕坐标系（[0,0]-[1080,2460]）。
        try {
            h.lp.setFitInsetsTypes(0);
            MiStepHost.log("fitInsetsTypes=0 applied");
        } catch (Throwable t) {
            MiStepHost.log("setFitInsetsTypes failed: " + t);
        }
        try {
            h.lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            MiStepHost.log("cutoutMode=ALWAYS applied");
        } catch (Throwable t) {
            MiStepHost.log("cutout mode failed: " + t);
        }
        h.wm.addView(h.root, h.lp);
        h.registerDefaultDisplayListener();
        h.createMirrorLayer();
        MiStepHost.log("host 2.9.41 HyperOS glass added " + w + "x" + hb
                + " privateFlags=0x" + Integer.toHexString(
                        XposedHelpers.getIntField(h.lp, "privateFlags")));
        // v1.3.3：呼出Mi Step = 整块屏缩进主区（写缩放触发文件，system_server 轮询执行）。
        h.main.postDelayed(h::collapseStatusBarPanels, 220L);
        // Host-owned toolbar and app strip, matching the Mi Step 3.0 interaction surface.
        try {
            java.util.List<QuickApp> quickApps;
            try {
                quickApps = MiStepWarmup.quickApps(h.ctx);
            } catch (Throwable error) {
                quickApps = java.util.Collections.emptyList();
                MiStepHost.log("top app strip unavailable: " + error);
            }
            h.topBar = new MiStepTopBar(h.ctx, h.layout, quickApps, new MiStepTopBar.Listener() {
                @Override public void onOpenSettings() { h.openSettingsInMain(); }
                @Override public void onOpenClipboard() {
                    if (h.scene == MiStepHost.ACTIVE && h.promotion == null && !h.transferBusy) {
                        if (h.layout.tabletLandscape) h.topCtrl.setTabletToolsOpen(true);
                        h.topBar.toggleClipboard(h.wallpaperBackdrop, h.screenW, h.screenH);
                    }
                }
                @Override public void onToggleTorch() {
                    if (h.scene == MiStepHost.ACTIVE && h.promotion == null && !h.transferBusy)
                        MiStepSystemActions.toggleTorch(h.ctx);
                }
                @Override public void onScreenshot() {
                    if (h.scene == MiStepHost.ACTIVE && h.promotion == null && !h.transferBusy)
                        MiStepSystemActions.takeScreenshot(h.ctx);
                }
                @Override public void onScreenRecord() {
                    if (h.scene == MiStepHost.ACTIVE && h.promotion == null && !h.transferBusy)
                        MiStepSystemActions.toggleScreenRecord(h.ctx);
                }
                @Override public void onExit() {
                    MiStepHost.log("top bar exit tapped");
                    h.toggleVisibility();
                }
                @Override public void onLaunch(QuickApp entry) { h.launchQuick(entry); }
                @Override public boolean canCustomizeApps() {
                    return h.scene == MiStepHost.ACTIVE && h.screenOn && !h.keyguardLocked
                            && h.promotion == null && !h.transferBusy
                            && (!h.topCtrl.topFace || h.layout.tabletLandscape && h.tabletToolsOpen);
                }
                @Override public void onDragStart(MotionEvent event) { h.beginWindowDrag(event); }
                @Override public void onDragMove(MotionEvent event) { h.updateWindowDrag(event); }
                @Override public void onDragEnd(MotionEvent event) { h.finishWindowDrag(event); }
                @Override public void onOpenLandscape() { h.topCtrl.openTopFace(true); }
                @Override public void onTaskCellsCovered(boolean covered) { h.topCtrl.setTaskCellsCovered(covered); }
            });
            h.refreshQuickApps();
            h.topStripBacking = new View(h.ctx);
            h.topStripBacking.setBackgroundColor(0xFF1E2420);
            h.root.addView(h.topStripBacking, new FrameLayout.LayoutParams(-1, h.layout.topStrip));
            h.topLiveClip=new FrameLayout(h.ctx);
            h.topLiveClip.setClipChildren(true);
            h.topLiveClip.setClipToPadding(true);
            h.topLiveClip.setClipBounds(new Rect(0,0,h.screenW,h.layout.tabletLandscape?h.screenH:h.layout.topStrip));
            h.root.addView(h.topLiveClip,new FrameLayout.LayoutParams(-1,h.layout.tabletLandscape?-1:h.layout.topStrip));
            h.topLiveClip.addView(h.topBar, new FrameLayout.LayoutParams(-1, h.layout.topStrip));
            h.topBar.setStatusBarInset(DeviceProfile.statusBarHeightPx(h.ctx));
            h.main.post(h::applyWallpaperBackdrops);
            for (int i = 1; i < h.CELL_SLOT_COUNT; i++) {
                View backing = new View(h.ctx);
                backing.setBackgroundColor(HyperOsStyle.page(h.ctx));
                int[] cell=h.layout.cellBounds(i-1);
                FrameLayout.LayoutParams rlp =
                        new FrameLayout.LayoutParams(h.layout.tabletLandscape?cell[2]-cell[0]:h.layout.sideWidth, h.sideSegmentHeight(i));
                rlp.leftMargin = h.layout.tabletLandscape?cell[0]:h.layout.mainOnRight ? 0 : h.layout.mainWidth;
                rlp.topMargin = h.sideSegmentTop(i);
                backing.setLayoutParams(rlp);
                h.root.addView(backing);
                h.sideBackingViews[i] = backing;
            }
            MiStepHost.log("Mi Step top bar ready shortcuts=" + quickApps.size());
        } catch (Throwable t) {
            MiStepHost.log("top bar add failed: " + t);
        }
        h.root.post(h::updateTopBarState);
        h.loadWallpaperBackdrop();
        h.beginWorkspaceEntry(null);
        // Keep corner activation disabled while active; HyperOS GestureStub owns back gestures.
        HotZoneGesture.raiseAll(h.ctx);
        // v0.9.9：心跳保证热区始终在位上（面板关闭路径异常时也能自动恢复，否则会卡在"撤下"）。
        h.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (h.root == null || MiStepHost.sInstance != h) {
                    return;
                }
                if (h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked || h.root.getVisibility() != View.VISIBLE) {
                    h.main.postDelayed(this, 4000L);
                    return;
                }
                if (h.layout != null && !h.holdsLiveFlipZoom()
                        && android.os.SystemClock.uptimeMillis() >= h.zoomAnimUntil) {
                    h.writeZoom(true);
                }
                if (h.carrierSlotIndex >= 0) {
                    h.reassertCarrier();
                }
                if (!h.zoomed && !HotZoneGesture.isAttached()) {
                    MiStepHost.log("hotzone heartbeat: re-raise");
                    HotZoneGesture.raiseAll(h.ctx);
                }
                // v1.1.3：转场可能覆盖 leash 的位置/矩阵（"只显示一部分"），心跳里周期性重发。
                for (Slot s : h.slots) {
                    if (h.holdsLiveFlipZoom()) continue;
                    if (s.carrier || s.index == h.leashPocSlot) {
                        continue;
                    }
                    ensureCellBackground(s);
                    if (!s.occupied || s.promoting) {
                        continue;
                    }
                    h.reapplyScale(s);
                    if (!s.taskVisible) h.scheduleSideVisibilityRepair(s);
                }
                h.main.postDelayed(this, 4000L);
            }
        }, 4000L);
        h.startPromotionWatchdog();
    }

    /** The same gate owns normal updates, workspace fades and covered task cells. */
    void updateCellSurfaceVisibility(Slot slot) {
        if (!h.lifecycle.ownsCellPresentation(slot)) return;
        boolean visible = h.lifecycle.cellPresentationVisible(slot);
        // An empty native container can be re-shown by SurfaceView's next position frame.
        // Its RenderNode must also be transparent; keep its surface alive for the next launch.
        if (slot.view != null) slot.view.setAlpha(visible ? 1f : 0f);
        Object surface = MiStepHost.getSurfaceField(slot.controller, "mSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(tx, "setAlpha", surface, visible ? h.workspaceAlpha : 0f);
            XposedHelpers.callMethod(tx, visible ? "show" : "hide", surface);
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { MiStepHost.log("cell container visibility: " + error); }
        finally { closeSurfaceObject(tx, "close"); }
    }

    void prepareCellSurface(Slot slot) {
        if (slot.view != null) slot.view.setAlpha(1f);
        Object surface = MiStepHost.getSurfaceField(slot.controller, "mSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(tx, "setAlpha", surface, h.workspaceAlpha);
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { MiStepHost.log("cell destination preparation: " + error); }
        finally { closeSurfaceObject(tx, "close"); }
    }

    void ensureCellBackground(Slot slot) {
        if (h.wallpaperBackdrop != null) h.applyWallpaperBackdrops();
        if (!slot.occupied || slot.parked) {
            removeCellBackground(slot);
            return;
        }
        if (h.root == null || slot.index <= MiStepHost.SLOT_MAIN || slot.controller == null || slot.rect == null) {
            return;
        }
        Object parent = MiStepHost.getSurfaceField(slot.controller, "mSurfaceControl");
        if (parent == null) return;
        int width = slot.rect[2] - slot.rect[0];
        int height = slot.rect[3] - slot.rect[1];
        if (parent == slot.cellBackgroundParent && width == slot.cellBackgroundWidth
                && height == slot.cellBackgroundHeight) return;
        removeCellBackground(slot);
        // Remember a failed attempt too: no allocation/log loop for the same surface generation.
        slot.cellBackgroundParent = parent;
        slot.cellBackgroundWidth = width;
        slot.cellBackgroundHeight = height;
        Object background = null;
        Object transaction = null;
        try {
            Object builder = Class.forName("android.view.SurfaceControl$Builder")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(builder, "setName", "MiStepCellBacking" + slot.index);
            XposedHelpers.callMethod(builder, "setColorLayer");
            XposedHelpers.callMethod(builder, "setParent", parent);
            background = XposedHelpers.callMethod(builder, "build");
            transaction = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            int page = HyperOsStyle.page(h.ctx);
            XposedHelpers.callMethod(transaction, "setColor", background,
                    new float[]{android.graphics.Color.red(page) / 255f,
                            android.graphics.Color.green(page) / 255f,
                            android.graphics.Color.blue(page) / 255f});
            XposedHelpers.callMethod(transaction, "setWindowCrop", background,
                    new Rect(0, 0, width, height));
            XposedHelpers.callMethod(transaction, "setLayer", background, -1);
            XposedHelpers.callMethod(transaction, "show", background);
            XposedHelpers.callMethod(transaction, "apply");
            slot.cellBackground = background;
            background = null; // The slot owns only the newly created backing, never the parent.
            MiStepHost.log("cell backing ready slot=" + slot.index);
        } catch (Throwable failure) {
            MiStepHost.log("cell backing failed slot=" + slot.index + ": " + failure);
        } finally {
            closeSurfaceObject(transaction, "close");
            if (background != null) destroyCellSurface(background);
        }
    }

    MiStepLayout makeAdaptiveLayout(boolean right) {
        android.util.DisplayMetrics metrics = h.ctx.getResources().getDisplayMetrics();
        return MiStepLayout.of(h.screenW, h.screenH, right, metrics.density, DeviceProfile.isTablet(h.ctx));
    }

    int dp(float value) {
        return Math.round(h.ctx.getResources().getDisplayMetrics().density * value);
    }

    void removeCellBackground(Slot slot) {
        Object background = slot.cellBackground;
        slot.cellBackground = null;
        slot.cellBackgroundParent = null;
        if (background == null) return;
        destroyCellSurface(background);
    }

    void destroyCellSurface(Object background) {
        Object transaction = null;
        try {
            transaction = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(transaction, "remove", background);
            XposedHelpers.callMethod(transaction, "apply");
        } catch (Throwable failure) {
            MiStepHost.log("cell backing cleanup failed: " + failure);
        } finally {
            closeSurfaceObject(transaction, "close");
            closeSurfaceObject(background, "release");
        }
    }

    void closeSurfaceObject(Object object, String method) {
        if (object == null) return;
        try {
            XposedHelpers.callMethod(object, method);
        } catch (Throwable failure) {
            MiStepHost.log("cell backing " + method + " failed: " + failure);
        }
    }

    void applyRect(Slot slot) {
        if (slot.rect == null) {
            return;
        }
        if (slot.view != null) {
            slot.view.setX(slot.rect[0]);
            slot.view.setY(slot.rect[1]);
            if (slot.view.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.view.getLayoutParams();
                lp.width = slot.rect[2] - slot.rect[0];
                lp.height = slot.rect[3] - slot.rect[1];
                slot.view.setLayoutParams(lp);
            }
        }
        if (slot.hint != null) {
            boolean paneLocal=slot.index==MiStepHost.SLOT_TOP;
            slot.hint.setX(paneLocal?0:slot.rect[0]);
            slot.hint.setY(paneLocal?0:slot.rect[1]);
            if (slot.hint.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.hint.getLayoutParams();
                lp.width = paneLocal?-1:slot.rect[2] - slot.rect[0];
                lp.height = paneLocal?-1:slot.rect[3] - slot.rect[1];
                slot.hint.setLayoutParams(lp);
            }
        }
        ensureCellBackground(slot);
    }

    void applyMainLayout(MiStepLayout target) {
        if (target == null || target.width != h.screenW || target.height != h.screenH)
            throw new IllegalArgumentException("stale Host layout");
        h.mainOnRight = target.mainOnRight;
        h.layout = target;
        MiStepStatusBarSampling.geometryChanged();
        if (h.topBar != null) h.topBar.setMainOnRight(h.mainOnRight);
        MiStepHost.log("main side layout: " + (h.mainOnRight ? "right" : "left") + " " + h.layout.describe());
        h.applyWallpaperBackdrops();
        if (h.root == null) return;
        if (h.layout.tabletLandscape) h.topCtrl.updateTopTaskGeometry();
        for (int i = 1; i < h.CELL_SLOT_COUNT; i++) {
            Slot slot = h.slots[i];
            slot.rect = h.slotBounds(slot);
            applyRect(slot);
            h.updateTouchRegion(slot);
        }
        for (int i = 1; i < h.CELL_SLOT_COUNT; i++) {
            View backing = h.sideBackingViews[i];
            if (backing == null
                    || !(backing.getLayoutParams() instanceof FrameLayout.LayoutParams)) continue;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) backing.getLayoutParams();
            int[] cell=target.cellBounds(i-1);
            params.leftMargin = target.tabletLandscape?cell[0]:target.mainOnRight ? 0 : target.mainWidth;
            params.topMargin = h.sideSegmentTop(i);
            params.width = target.tabletLandscape?cell[2]-cell[0]:target.sideWidth;
            params.height = h.sideSegmentHeight(i);
            backing.setLayoutParams(params);
        }
        h.root.requestLayout();
        h.root.invalidate();
    }

    void compactCellsAfterCollection() {
        // Collection has committed its real task binding. Fill earlier empty
        // cells now instead of waiting for the next workspace resume.
        compactCells();
    }

    /**
     * v0.9.9：副格是"栈"——某个格子空了，后面的占用格子自动往上排（官方 SideAreaStackView 的紧凑排列）。
     * 平板顶排同样保持连续；独立的竖屏副窗 SLOT_TOP 位于 CELL_SLOT_COUNT 之后，
     * 不参与这条补位链。
     */
    void compactCells() {
        if (h.root == null || h.layout == null) return;
        if (h.scene != MiStepHost.ACTIVE || h.promotion != null || h.transferBusy || h.cellMotion != null) {
            h.compactPending = true;
            return;
        }
        // Pending callbacks and an active host gesture keep their owner geometry fixed.
        for (Slot s : h.slots) {
            if (s.promoting || s.launching || s.closing || s.nativeReleasePending
                    || s.bufferedDown != null || s.injecting) {
                h.compactPending = true;
                return;
            }
        }
        h.compactPending = false;
        h.publishDesktopSideTasks();
        java.util.List<CellMovePlan> moves = new java.util.ArrayList<>();
        int initialEmptySlots = 0;
        for (Slot slot : h.slots) if (slot.index > MiStepHost.SLOT_MAIN && !slot.occupied)
            initialEmptySlots |= 1 << slot.index;
        for (int i = 1; i < h.CELL_SLOT_COUNT - 1; i++) {
            Slot cur = h.slots[i];
            if (cur.occupied) {
                continue;
            }
            for (int j = i + 1; j < h.CELL_SLOT_COUNT; j++) {
                Slot next = h.slots[j];
                if (!next.occupied) {
                    continue;
                }
                MiStepHost.log("compactCells: slot" + j + " -> slot" + i + " (" + next.pkg + ")");
                h.recycleBuffered(cur);
                h.recycleBuffered(next);
                Rect oldRect = next.rect == null ? null : new Rect(
                        next.rect[0], next.rect[1], next.rect[2], next.rect[3]);
                SlotPositions.swap(h.slots, i, j);
                h.syncSlotWindowState(h.slots[i]);
                h.syncSlotWindowState(h.slots[j]);
                cur.rect = h.layout.cellBounds(cur.index - 1);
                next.rect = h.layout.cellBounds(next.index - 1);
                if (oldRect != null) {
                    moves.add(new CellMovePlan(next, oldRect));
                }
                break;
            }
        }
        if (!moves.isEmpty()) animateCellMoves(moves, initialEmptySlots);
    }

    void requestCompaction() {
        if (h.scene != MiStepHost.ACTIVE || h.root == null) return;
        h.compactPending = true;
        h.main.post(this::compactCells);
    }

    void finishCellLayout() {
        h.updateTopTaskGeometry();
        for (int i = 1; i < MiStepHost.SLOT_COUNT; i++) {
            Slot slot = h.slots[i];
            applyRect(slot);
            h.updateHint(slot);
            h.updateTouchRegion(slot);
            h.reapplyScale(slot);
        }
        h.applyWallpaperBackdrops();
        h.publishDesktopSideTasks();
        if (h.root != null) { h.root.requestLayout(); h.root.invalidate(); }
    }

    boolean cellMoveCurrent(Slot slot, FrameLayout movingRoot, long epoch, long binding,
            int id, Object token, Object controller, View movingView, Object surface) {
        String changed = h.root != movingRoot ? "root" : h.sceneEpoch != epoch ? "scene"
                : !h.isActive(slot) ? "slot" : slot.taskEpoch != binding ? "binding"
                : slot.taskId != id ? "task" : !java.util.Objects.equals(slot.taskToken, token) ? "token"
                : slot.controller != controller ? "controller" : slot.view != movingView ? "view"
                : MiStepHost.getSurfaceField(controller, "mSurfaceControl") != surface ? "surface"
                : !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")) ? "invalid surface" : null;
        if (changed != null) MiStepHost.log("real cell motion binding changed: " + changed + " slot=" + slot.index
                + " task=" + id + " current=" + slot.taskId);
        return changed == null;
    }

    static int compactionHintMask(int initialEmptySlots, int occupiedSlots) { return HostStaticUtil.compactionHintMask(initialEmptySlots, occupiedSlots); }

    void animateCellMoves(java.util.List<CellMovePlan> plans, int initialEmptySlots) {
        final FrameLayout movingRoot = h.root;
        final long epoch = h.sceneEpoch;
        final java.util.List<MiStepCellMotion.Move> moves = new java.util.ArrayList<>();
        final java.util.List<LiveSwapVisualLease> hints = new java.util.ArrayList<>();
        MiStepLiveBackdrop wallpaper = null;
        try {
            int occupiedSlots = 0;
            for (Slot slot : h.slots) if (slot.index > MiStepHost.SLOT_MAIN && slot.occupied)
                occupiedSlots |= 1 << slot.index;
            for (CellMovePlan plan : plans) {
                Slot slot = plan.slot;
                final long binding = slot.taskEpoch;
                final int id = slot.taskId;
                final Object token = slot.taskToken, controller = slot.controller;
                final Object surface = MiStepHost.getSurfaceField(controller, "mSurfaceControl");
                if (!h.canRender(slot) || surface == null) continue;
                final View movingView = slot.view;
                moves.add(new MiStepCellMotion.Move(movingView, plan.from.left, plan.from.top,
                        slot.rect[0], slot.rect[1], () -> cellMoveCurrent(slot, movingRoot, epoch, binding,
                        id, token, controller, movingView, surface)));
            }
            if (moves.isEmpty()) { finishCellLayout(); return; }
            // Preserve existing empty pluses and reveal vacated sources. An occupied
            // intermediate destination never acquires a one-frame '+' during travel.
            final int revealEmptySlots = compactionHintMask(initialEmptySlots, occupiedSlots);
            final MiStepLayout motionGeometry = h.layout;
            Rect strip = h.liveSideStrip(motionGeometry);
            wallpaper = MiStepLiveBackdrop.create(h.liveHostRootSurface(), h.wallpaperBackdrop, h.screenW, h.screenH,
                    strip, strip, canvas -> h.drawLiveEmptyHints(canvas, motionGeometry, motionGeometry,
                            revealEmptySlots), MiStepHost::log);
            // One wallpaper/plus owner covers the complete strip during compaction.
            // Static backings otherwise cover its pluses and reappear between layouts.
            for (Slot slot : h.slots) if (slot.index > MiStepHost.SLOT_MAIN && slot.index < h.CELL_SLOT_COUNT)
                hints.add(new LiveSwapVisualLease(h.sideBackingViews[slot.index], slot.hint));
            final MiStepLiveBackdrop backing = wallpaper;
            final MiStepCellMotion[] run = new MiStepCellMotion[1];
            run[0] = new MiStepCellMotion(movingRoot, moves,
                    () -> {
                        if (h.root != movingRoot || h.sceneEpoch != epoch) return;
                        finishCellLayout();
                        // Restore Host chrome in the SAME draw that retires the temporary
                        // layer, before waiting for that frame's committed callback.
                        for (LiveSwapVisualLease hint : hints) hint.restore();
                        backing.closeAfterFrame(movingRoot);
                    },
                    () -> {
                        for (LiveSwapVisualLease hint : hints) hint.restore();
                        movingRoot.invalidate();
                        backing.closeAfterFrame(movingRoot);
                        if (h.cellMotion != run[0]) return;
                        h.cellMotion = null;
                        h.transferBusy = false;
                        // finishCellLayout ran while cellMotion still owned visibility.
                        // Reconcile now, including the empty old source's resize background.
                        for (Slot cell : h.slots) updateCellSurfaceVisibility(cell);
                        if (h.compactPending) compactCells();
                        h.finishPendingSuspend();
                    }, MiStepHost::log);
            h.cellMotion = run[0];
            h.transferBusy = true;
            run[0].start();
        } catch (Throwable error) {
            for (LiveSwapVisualLease hint : hints) hint.restore();
            if (wallpaper != null) wallpaper.closeAfterFrame(movingRoot);
            h.cellMotion = null;
            h.transferBusy = false;
            finishCellLayout();
            MiStepHost.log("real cell motion unavailable: " + error);
        }
    }

}
