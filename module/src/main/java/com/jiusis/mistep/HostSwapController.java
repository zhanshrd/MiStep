package com.jiusis.mistep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.util.function.Consumer;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * v0.9 触摸仲裁 / 定向注入 / 点击交换 / 空格收集（live collect）子系统。
 * 自 MiStepHost 原样搬出；宿主内保留同名委托 shim。
 * 状态字段 sideDismiss / sideDismissSlot 留在宿主（suspend 等路径也在用），经 h.* 读写。
 */
final class HostSwapController {
    final MiStepHost h;
    private final HostLiveSwapFinishController finishCtl;

    HostSwapController(MiStepHost host) {
        finishCtl = new HostLiveSwapFinishController(this, host);
        h = host;
    }

    private View transitionInputWindow;
    BroadcastReceiver liveZoomReceiver;
    LiveZoomRequest pendingLiveZoom;

    // ---------------------------------------------------------------- v0.9 触摸仲裁与定向注入

    /**
     * 触摸规则（官方Mi Step语义 + 演示需要）：
     *   主区由系统处理，宿主不接收其触摸；
     *   副格有任务 → 轻点=与主区交换；滑动取消轻点，始终不操作副任务；
     *   空槽 → 不消费，交给宿主点击处理。
     */
    boolean onSlotTouch(int slotIndex, MotionEvent e) {
        if (slotIndex <= MiStepHost.SLOT_MAIN || slotIndex >= h.CELL_SLOT_COUNT) return false;
        if (h.taskCellsCovered) return true;
        Slot slot = h.slots[slotIndex];
        // Our MOVE session owns this pointer even while the task-transfer gate is held.
        if (h.sideDismiss != null && h.sideDismissSlot == slot) return followSideDismiss(slot, e);
        if (slot.promoting || slot.launching || slot.closing || slot.frozen || slot.parked
                || h.transferBusy || h.promotion != null || h.cellMotion != null) {
            recycleBuffered(slot);
            slot.injecting = false;
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            MiStepHost.log("slotTouch down slot=" + slotIndex + " occupied=" + slot.occupied
                    + " x=" + e.getRawX() + " y=" + e.getRawY());
        }
        if (!slot.occupied || slot.view == null) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                recycleBuffered(slot);
                slot.bufferedDown = MotionEvent.obtain(e);
                slot.touchTaskEpoch = slot.taskEpoch;
                slot.touchSceneEpoch = h.sceneEpoch;
                slot.injecting = false;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (slot.bufferedDown != null && e.getPointerCount() == 1
                        && slot.touchTaskEpoch == slot.taskEpoch && slot.touchSceneEpoch == h.sceneEpoch) {
                    float dx = e.getRawX() - slot.bufferedDown.getRawX();
                    float dy = e.getRawY() - slot.bufferedDown.getRawY();
                    int slop = ViewConfiguration.get(h.ctx).getScaledTouchSlop();
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) slot.injecting = true;
                    if (MiStepSideDismissGesture.starts(h.mainOnRight, dx, dy, slop)) {
                        dismissSideToBackground(slot);
                        if (h.sideDismiss != null && h.sideDismissSlot == slot) h.sideDismiss.follow(dx);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                recycleBuffered(slot);
                slot.injecting = true;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                MotionEvent down = slot.bufferedDown;
                boolean current = down != null && e.getActionMasked() == MotionEvent.ACTION_UP
                        && e.getPointerCount() == 1 && down.getPointerCount() == 1
                        && e.getPointerId(0) == down.getPointerId(0)
                        && slot.touchTaskEpoch == slot.taskEpoch && slot.touchSceneEpoch == h.sceneEpoch;
                float dx = current ? e.getRawX() - down.getRawX() : 0;
                float dy = current ? e.getRawY() - down.getRawY() : 0;
                int slop = ViewConfiguration.get(h.ctx).getScaledTouchSlop();
                boolean dismiss = current && slot.rect != null && MiStepSideDismissGesture.accepts(
                        h.mainOnRight, dx, dy, slot.rect[2] - slot.rect[0],
                        h.ctx.getResources().getDisplayMetrics().density, slop,
                        e.getEventTime() - down.getEventTime());
                boolean tap = current && !slot.injecting && Math.abs(dx) <= slop && Math.abs(dy) <= slop;
                recycleBuffered(slot);
                slot.injecting = false;
                if (dismiss) {
                    dismissSideToBackground(slot);
                    if (h.sideDismiss != null && h.sideDismissSlot == slot) {
                        h.sideDismiss.follow(dx);
                        h.sideDismiss.settle(true);
                    }
                } else if (tap) {
                    MiStepHost.log("cell tap slot=" + slotIndex + " -> swap");
                    onSlotTapped(slotIndex);
                }
                if (h.compactPending) h.compactCells();
                return true;
            }
            default:
                return false;
        }
    }

    void recycleBuffered(Slot slot) {
        if (slot.bufferedDown != null) {
            slot.bufferedDown.recycle();
            slot.bufferedDown = null;
        }
    }

    boolean followSideDismiss(Slot slot, MotionEvent e) {
        MiStepSideDismiss run = h.sideDismiss;
        if (!run.isTracking()) return true;
        MotionEvent down = slot.bufferedDown;
        int action = e.getActionMasked();
        boolean current = down != null && e.getPointerCount() == 1
                && e.getPointerId(0) == down.getPointerId(0)
                && slot.touchTaskEpoch == slot.taskEpoch && slot.touchSceneEpoch == h.sceneEpoch;
        float dx = current ? e.getRawX() - down.getRawX() : 0f;
        float dy = current ? e.getRawY() - down.getRawY() : 0f;
        if (action == MotionEvent.ACTION_MOVE && current) { run.follow(dx); return true; }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || !current
                || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            boolean commit = current && action == MotionEvent.ACTION_UP && slot.rect != null
                    && MiStepSideDismissGesture.accepts(h.mainOnRight, dx, dy,
                            slot.rect[2] - slot.rect[0], h.ctx.getResources().getDisplayMetrics().density,
                            ViewConfiguration.get(h.ctx).getScaledTouchSlop(), e.getEventTime() - down.getEventTime());
            if (current) run.follow(dx);
            recycleBuffered(slot); slot.injecting = false;
            run.settle(commit);
        }
        return true;
    }

    void dismissSideToBackground(Slot slot) {
        if (h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked || h.transferBusy || h.promotion != null
                || h.cellMotion != null || !h.isActive(slot) || !slot.occupied || slot.carrier
                || slot.parked || slot.frozen || slot.promoting || slot.launching || slot.closing
                || slot.taskId < 0 || slot.taskToken == null || slot.controller == null) return;
        for (Slot other : h.slots) if (other.launching || other.promoting || other.closing) return;
        final int taskId = slot.taskId;
        final long binding = slot.taskEpoch;
        final long epoch = h.sceneEpoch;
        final View hostRoot = h.root;
        MiStepHost.log("side outward swipe -> background task=" + taskId + " slot=" + slot.index);
        MiStepLiveBackdrop preparing = null;
        LiveSwapVisualLease preparingVisuals = null;
        try {
            final MiStepLayout geometry = h.layout;
            final int emptyMask = 1 << slot.index;
            Rect strip = liveSideStrip(geometry);
            final MiStepLiveBackdrop backing = preparing = MiStepLiveBackdrop.create(liveHostRootSurface(),
                    h.wallpaperBackdrop, h.screenW, h.screenH, strip, strip,
                    canvas -> drawLiveEmptyHints(canvas, geometry, geometry, emptyMask), MiStepHost::log);
            final LiveSwapVisualLease visuals = preparingVisuals =
                    new LiveSwapVisualLease(h.sideBackingViews[slot.index], slot.hint);
            h.transferBusy = true;
            slot.promoting = true;
            final MiStepSideDismiss[] run = new MiStepSideDismiss[1];
            Runnable release = () -> {
                run[0].close();
                if (h.sideDismiss == run[0]) { h.sideDismiss = null; h.sideDismissSlot = null; }
                recycleBuffered(slot); slot.injecting = false;
                if (h.root == hostRoot && h.sceneEpoch == epoch) {
                    for (Slot cell : h.slots) h.updateCellSurfaceVisibility(cell);
                    visuals.restore();
                    hostRoot.invalidate();
                } else visuals.restore();
                backing.closeAfterFrame(hostRoot);
                if (h.compactPending) h.compactCells();
                h.finishPendingSuspend();
            };
            float distance = (h.mainOnRight ? -1f : 1f)
                    * (slot.rect[2] - slot.rect[0] + HyperOsStyle.dp(h.ctx, 8f));
            run[0] = new MiStepSideDismiss(hostRoot, slot.view, distance,
                    () -> h.root == hostRoot && h.sceneEpoch == epoch && h.scene == MiStepHost.ACTIVE
                            && h.isActive(slot) && slot.taskEpoch == binding && slot.taskId == taskId,
                    reachedEdge -> {
                        if (!reachedEdge) {
                            if (slot.taskEpoch == binding && slot.taskId == taskId) slot.promoting = slot.frozen;
                            h.transferBusy = false; release.run(); return;
                        }
                        h.returnToSystem(slot, false, () -> {
                            // Hide the empty real container before restoring its offscreen View.
                            if (slot.taskEpoch == binding && slot.taskId == taskId) h.clearSlot(slot);
                            MiStepHost.log("side swipe handed back task=" + taskId + "; kept in system background");
                            release.run();
                        }, error -> {
                            MiStepHost.log("side swipe handback stopped; binding retained task=" + taskId + ": " + error);
                            release.run();
                        });
                    }, MiStepHost::log);
            h.sideDismiss = run[0];
            h.sideDismissSlot = slot;
            MiStepHost.log("side outward real View animation task=" + taskId + " distance=" + distance);
            // MOVE positions the real view immediately; UP completes or rebounds from it.
        } catch (Throwable error) {
            if (preparingVisuals != null) preparingVisuals.restore();
            if (preparing != null) preparing.closeAfterFrame(hostRoot);
            h.transferBusy = false;
            slot.promoting = slot.frozen;
            MiStepHost.log("side outward preparation stopped: " + error);
            h.finishPendingSuspend();
        }
    }

    /** Keep tap-to-swap, transfer a drag to its real window without reinjecting coordinates. */
    void transferTouchToTask(Slot slot, long eventTime) {
        if (h.root == null || slot.taskToken == null || !h.controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        try {
            android.os.Bundle tokens = new android.os.Bundle();
            tokens.putBinder("host", h.root.getWindowToken());
            tokens.putBinder("task", (android.os.IBinder) XposedHelpers.callMethod(slot.taskToken, "asBinder"));
            Intent request = new Intent("com.jiusis.mistep.TRANSFER_TOUCH");
            request.setPackage("android");
            request.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            request.putExtra("tokens", tokens);
            request.putExtra("taskId", slot.taskId);
            request.putExtra("eventTime", eventTime);
            h.ctx.sendBroadcast(request);
            MiStepHost.log("side touch transfer requested slot=" + slot.index + " task=" + slot.taskId);
        } catch (Throwable t) {
            MiStepHost.log("side touch transfer request failed slot=" + slot.index + ": " + t);
        }
    }

    // ---------------------------------------------------------------- 点击 / 交换
    void onSlotTapped(int slotIndex) {
        try {
            if (slotIndex <= MiStepHost.SLOT_MAIN || slotIndex >= MiStepHost.SLOT_COUNT) {
                return;
            }
            MiStepHost.log("onSlotTapped slot=" + slotIndex
                    + (h.slots[slotIndex] != null && h.slots[slotIndex].carrier ? " carrier" : ""));
            if (h.scene != MiStepHost.ACTIVE || h.transferBusy || h.promotion != null) return;
            Slot slot = h.slots[slotIndex];
            if (slot.closing) return;
            if (slot.carrier) {
                h.restoreCarrierToMain();
                return;
            }
            if (slot.parked && slot.occupied && !slot.frozen) {
                h.adoptExisting(slot, new LiveTask(slot), h::finishPendingSuspend,
                        error -> MiStepHost.log("explicit resume retained task: " + error));
                return;
            }
            if (!slot.occupied) {
                captureMainIntoEmptySlot(slot);
                return;
            }
            if (slotIndex == MiStepHost.SLOT_MAIN) {
                return;
            }
            swapWithMain(slotIndex);
        } catch (Throwable t) {
            MiStepHost.log("onSlotTapped FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    /** Official empty-cell flow: open an app from Home, then collect that exact task. */
    void captureMainIntoEmptySlot(Slot requestedSlot) {
        if (requestedSlot == null) return;
        final Slot slot = requestedSlot;
        if (h.carrierSlotIndex >= 0) {
            android.widget.Toast.makeText(h.ctx, "先点副格把相机放回主区",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked || h.transferBusy
                || h.promotion != null || h.swapPreview != null
                || slot.occupied || slot.launching || slot.promoting || slot.frozen
                || slot.controller == null) return;
        for (Slot other : h.slots) if (other.launching || other.promoting || other.closing) return;
        final long epoch = h.sceneEpoch;
        h.transferBusy = true;
        if (!h.executeOnShell(() -> {
            try {
                LiveTask task = h.findMainTask(); // Deliberately excludes Home and existing cells.
                h.main.post(() -> {
                    if (h.scene != MiStepHost.ACTIVE || epoch != h.sceneEpoch || !h.isActive(slot)
                            || slot.occupied || h.promotion != null || h.suspendRequested) {
                        h.transferBusy = false;
                        h.finishPendingSuspend();
                        return;
                    }
                    if (task == null) {
                        h.transferBusy = false;
                        MiStepHost.log("empty cell keeps Home in main; open an app there first");
                        android.widget.Toast.makeText(h.ctx, "先在主屏打开应用，再点此格收进来",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    h.hideIme();
                    startLiveCollect(slot, task);
                });
            } catch (Throwable error) {
                h.main.post(() -> {
                    h.transferBusy = false;
                    MiStepHost.log("collect current main admission stopped: " + error);
                    h.finishPendingSuspend();
                });
            }
        })) {
            h.transferBusy = false;
            MiStepHost.log("no Shell queue for collecting current main");
        }
    }

    /** Collect the actual fullscreen task; no captured app or desktop participates. */
    void startLiveCollect(Slot slot, LiveTask task) {
        final MiStepLayout geometry = h.layout;
        final int width = h.screenW, height = h.screenH;
        final Object hostSurface;
        try { hostSurface = liveHostRootSurface(); }
        catch (Throwable error) {
            h.transferBusy = false;
            MiStepHost.log("live collect Host unavailable: " + error);
            h.finishPendingSuspend();
            return;
        }
        h.bindTask(slot, task.id, task.token);
        Promotion ticket = new Promotion(slot, h.slots);
        ticket.sceneEpoch = h.sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        ticket.previousMain = task;
        ticket.previousLayout = ticket.targetLayout = geometry;
        ticket.nativeSwap = ticket.nativeCollect = true;
        slot.promoting = true;
        h.promotion = ticket;
        h.transferBusy = false;
        OwnedTask candidate = h.ownedTasks.computeIfAbsent(task.token, key -> new OwnedTask(task.id, key));
        candidate.recoveryController = slot.controller;
        candidate.recoveryCandidate = true;
        ticket.previousMainCandidate = candidate;
        try {
            Rect side = liveSideStrip(geometry);
            ticket.liveBackdrop = MiStepLiveBackdrop.create(hostSurface, h.wallpaperBackdrop, width, height,
                    side, side, canvas -> drawLiveEmptyHints(canvas, geometry, geometry,
                            1 << slot.index), MiStepHost::log);
        } catch (Throwable error) {
            h.failPromotion(ticket, "collect wallpaper preparation: " + error);
            return;
        }
        // Keep the empty-cell affordance until the real task occupies it.
        ticket.liveVisual = new LiveSwapVisualLease(h.sideBackingViews[slot.index], null);
        h.prepareCellSurface(slot);
        if (!setLiveSwapInputBlocked(true)) {
            h.failPromotion(ticket, "transition input window unavailable"); return;
        }
        h.statusBar.hideFor(ticket, () -> h.failPromotion(ticket, "collect status-bar lease expired"));
        LiveSwapGeometry.Task app = new LiveSwapGeometry.Task(task.id, task.token, width, height,
                liveMainRect(geometry), liveCellRect(geometry, slot.index));
        h.statusBar.runAfterHiddenFrame(ticket, () -> afterLiveHostFrame(ticket, () -> {
            if (!h.executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (h.externalPromotionCurrent(ticket) && h.layout == geometry) {
                        Object shell = XposedHelpers.getObjectField(h.transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < h.zoomAnimUntil || !Boolean.TRUE.equals(
                                XposedHelpers.callMethod(shell, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        Object info = h.rootInfo(task);
                        if (!h.ordinaryRoot(info, 1) || !h.mainTaskVisible(info))
                            throw new IllegalStateException("collect fullscreen identity changed");
                        candidate.userId = XposedHelpers.getIntField(info, "userId");
                        ticket.collectionHome = h.existingHomeForCollection(task);
                        Object homeInfo = h.rootInfo(ticket.collectionHome);
                        if (!h.isHomeRoot(homeInfo) || h.windowModeOf(homeInfo) != 1)
                            throw new IllegalStateException("collect Home identity changed");
                        ticket.liveMainUserId = XposedHelpers.getIntField(homeInfo, "userId");
                        h.resetIdleTaskView(slot);
                        accepted = MiStepLiveCollect.start(h.ctx.getClassLoader(), h.transitions,
                                slot.controller, app, ticket.collectionHome.token, candidate.userId, ticket.liveMainUserId,
                                new Rect(0, 0, width, height), hostSurface, slot.cellBackground,
                                () -> h.externalPromotionCurrent(ticket) && h.layout == geometry,
                                settled -> h.main.post(() -> prepareCollectHandoff(ticket, settled)),
                                result -> finishLiveResult(ticket, result), MiStepHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { MiStepHost.log("live collect refused: " + error); }
                if (!accepted) h.main.post(() -> finishLiveCollect(ticket, new MiStepLiveSwap.Result(false,
                        false, new IllegalStateException("live collect preflight refused"))));
            })) h.failPromotion(ticket, "live collect Shell queue unavailable");
        }));
    }

    void prepareCollectHandoff(Promotion ticket, Consumer<Boolean> settled) {
        if (!h.externalPromotionCurrent(ticket)) { settled.accept(false); return; }
        // The real app is still above Host. Remove the opaque empty-cell drawing
        // now, commit that frame, then let Shell return the app to its TaskView.
        if (ticket.slot.hint != null) ticket.slot.hint.setVisibility(View.GONE);
        MiStepCollectFrame.await(h.root, () -> h.externalPromotionCurrent(ticket), settled);
    }

    void finishLiveCollect(Promotion ticket, MiStepLiveSwap.Result result) {
        if (ticket.nativeCompleting) return;
        if (!livePromotionBound(ticket)) {
            if (h.promotion == ticket && !ticket.cancelled)
                forceAbandonLivePromotion(ticket, "stale live collect completion", true);
            return;
        }
        ticket.nativeCompleting = true;
        Slot slot = ticket.slot;
        if (result.sideOwned && result.rolesConfirmed) {
            slot.pkg = ticket.previousMain.pkg;
            slot.occupied = slot.taskVisible = true;
            slot.windowMode = 6;
            slot.promoting = slot.launching = slot.parked = slot.frozen = false;
            ticket.previousMainCandidate.recoveryCandidate = false;
            HomeTaskReturn savedHome = h.coveredHome;
            if (savedHome != null && savedHome.covers(ticket.taskId, ticket.token)) h.coveredHome = null;
        } else if (result.rolesConfirmed) {
            h.ownedTasks.remove(ticket.token, ticket.previousMainCandidate);
            h.clearSlot(slot);
        } else {
            slot.promoting = slot.launching = false;
            slot.occupied = slot.frozen = true;
        }
        if (result.sideOwned && result.rolesConfirmed) {
            h.finishPromotionFocus(ticket, () -> completeLiveCollect(ticket, result), 0);
        } else completeLiveCollect(ticket, result);
    }

    void completeLiveCollect(Promotion ticket, MiStepLiveSwap.Result result) {
        if (h.promotion != ticket || ticket.cancelled) return;
        Slot slot = ticket.slot;
        ticket.cancelled = true;
        h.promotion = null;
        restoreLiveSwapVisual(ticket);
        h.statusBar.finish(ticket);
        h.updateHint(slot);
        h.updateTouchRegion(slot);
        h.publishDesktopSideTasks();
        MiStepHost.log("live collect settled task=" + ticket.taskId + " side=" + result.sideOwned
                + " confirmed=" + result.rolesConfirmed + " animated=" + result.succeeded + " error=" + result.error);
        h.requestCompaction();
        h.finishPendingSuspend();
    }

    /** Promote through the existing Shell queue; retain the slot until actual detachment. */
    void swapWithMain(int slotIndex) {
        swapWithMain(slotIndex, false);
    }

    private void swapWithMain(int slotIndex, boolean desktopLaunch) {
        if (slotIndex <= MiStepHost.SLOT_MAIN || slotIndex >= MiStepHost.SLOT_COUNT) {
            return;
        }
        Slot cellSlot = h.slots[slotIndex];
        if (h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked || h.transferBusy
                || h.swapPreview != null || cellSlot.parked
                || h.promotion != null || cellSlot.launching || cellSlot.promoting) return;
        if (cellSlot.carrier) {
            // 镜像承载的任务本身仍在主区：把画面移回来即完成“升为主区”。
            h.restoreCarrierToMain();
            return;
        }
        for (Slot s : h.slots) if (s.launching || s.closing) return;
        if (!cellSlot.occupied || cellSlot.controller == null) {
            captureMainIntoEmptySlot(cellSlot);
            return;
        }
        if (cellSlot.taskToken == null) {
            MiStepHost.log("promote: task token not ready; keep cell" + slotIndex);
            return;
        }
        final Promotion ticket = new Promotion(cellSlot, h.slots);
        MiStepClipboard.get(h.ctx).close();
        ticket.sceneEpoch = h.sceneEpoch;
        ticket.bindingEpoch = cellSlot.taskEpoch;
        ticket.previousMainOnRight = h.mainOnRight;
        ticket.targetMainOnRight = h.mainOnRight;
        h.promotion = ticket;
        if (desktopLaunch) ticket.slot.promoting = true;
        h.statusBar.hideFor(ticket, () -> {
            if (h.promotion == ticket && !ticket.cancelled) {
                h.failPromotion(ticket, "status-bar lease expired");
            } else if (h.swapPreviewStatusOwner == ticket) {
                h.closeSwapPreview();
            }
        });
        recycleBuffered(cellSlot);
        MiStepHost.log("promote begin: cell" + slotIndex + " task=" + ticket.taskId);
        h.hideIme();
        if (!h.executeOnShell(() -> {
            try {
                if (ticket.cancelled) return;
                Object current = XposedHelpers.getObjectField(cellSlot.controller, "mTaskToken");
                if (!ticket.token.equals(current)) throw new IllegalStateException("task token changed");
                ticket.previousMain = desktopLaunch ? null : h.findMainTask();
                if (ticket.previousMain == null) ticket.homeBeforeCover = h.existingHomeTask();
                h.traceSwap(ticket);
                if (ticket.previousMain == null) {
                    // Home remains anchored. Its state is retained by its original
                    // task, rather than by animating or repainting a Home snapshot.
                    ticket.targetMainOnRight = ticket.previousMainOnRight;
                    h.main.post(() -> {
                        if (h.promotion != ticket || ticket.cancelled || !h.screenOn
                                || h.keyguardLocked || h.scene != MiStepHost.ACTIVE) {
                            h.failPromotion(ticket, "Home promotion scene changed");
                            return;
                        }
                        if (ticket.homeBeforeCover == null) {
                            h.failPromotion(ticket, "exact Home task unavailable");
                            return;
                        }
                        ticket.slot.promoting = true;
                        MiStepHost.log("cover existing Home task=" + ticket.homeBeforeCover.id
                                + " with app=" + ticket.taskId + " desktop=" + desktopLaunch
                                + " keepMainSide=" + ticket.previousMainOnRight);
                        startLiveHome(ticket);
                    });
                    return;
                }
                h.main.post(() -> startLiveSwap(ticket));
            } catch (Throwable t) {
                h.main.post(() -> h.failPromotion(ticket, "prepare: " + t));
            }
        })) h.failPromotion(ticket, "Shell executor unavailable");
    }

    boolean setLiveSwapInputBlocked(boolean on) {
        if (h.transitionInput == null) return !on;
        if (on) {
            boolean acquired = h.transitionInput.block();
            if (acquired && transitionInputStartedAt == 0L) {
                transitionInputStartedAt = SystemClock.uptimeMillis();
                MiStepHost.log("live input acquired uptime=" + transitionInputStartedAt);
            }
            return acquired;
        }
        transitionInputReleaseRequestedAt = SystemClock.uptimeMillis();
        MiStepHost.log("live input final frame requested uptime=" + transitionInputReleaseRequestedAt);
        h.transitionInput.releaseAfterFrame();
        return true;
    }

    private long transitionInputStartedAt, transitionInputReleaseRequestedAt;

    void acquireTransitionInputWindow() {
        if (transitionInputWindow != null) return;
        View blocker = new View(h.ctx);
        blocker.setOnTouchListener((view, event) -> true);
        blocker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(h.lp);
        params.setTitle("MiStepTransitionInput");
        // A sibling window sits above the borrowed app surfaces under Host's h.root.
        // Merely widening Host's own touch region cannot cover its higher children.
        h.wm.addView(blocker, params);
        transitionInputWindow = blocker;
    }

    void releaseTransitionInputWindow() {
        View blocker = transitionInputWindow;
        transitionInputWindow = null;
        if (blocker == null) return;
        blocker.setVisibility(View.GONE);
        try { h.wm.removeViewImmediate(blocker); }
        catch (Throwable error) { MiStepHost.log("live input window release: " + error); }
        if (transitionInputStartedAt != 0L) {
            long now = SystemClock.uptimeMillis();
            MiStepHost.log("live input released uptime=" + now + " heldMs=" + (now - transitionInputStartedAt)
                    + " finalFrameMs=" + (transitionInputReleaseRequestedAt == 0L
                            ? -1L : now - transitionInputReleaseRequestedAt));
            transitionInputStartedAt = transitionInputReleaseRequestedAt = 0L;
        }
    }

    Object liveHostRootSurface() {
        if (h.root == null || !h.root.isAttachedToWindow() || !h.root.isHardwareAccelerated())
            throw new IllegalStateException("live Host window unavailable");
        Object viewRoot = XposedHelpers.callMethod(h.root, "getViewRootImpl");
        Object surface = viewRoot == null ? null : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
            throw new IllegalStateException("live Host root surface unavailable");
        return surface;
    }

    static LiveSwapGeometry.Rect liveMainRect(MiStepLayout geometry) {
        return new LiveSwapGeometry.Rect(geometry.mainOffsetX, geometry.mainOffsetY,
                geometry.width * geometry.mainScale, geometry.height * geometry.mainScale);
    }

    static LiveSwapGeometry.Rect liveCellRect(MiStepLayout geometry, int index) {
        int[] bounds = geometry.cellBounds(index - 1);
        float scale = geometry.cellScale(index - 1);
        return new LiveSwapGeometry.Rect(bounds[0], bounds[1],
                geometry.width * scale, geometry.height * scale);
    }

    static Rect liveSideStrip(MiStepLayout geometry) {
        if(geometry.tabletLandscape)return new Rect(0,geometry.toolHeight,geometry.width,geometry.topStrip);
        return new Rect(geometry.mainOnRight ? 0 : geometry.mainWidth, geometry.topStrip,
                geometry.mainOnRight ? geometry.sideWidth : geometry.width, geometry.height);
    }

    private boolean livePeerBindingsCurrent(Promotion ticket) {
        for (LivePeerBinding peer : ticket.livePeers) {
            Slot slot = peer.slot;
            if (!h.isActive(slot) || slot.taskEpoch != peer.epoch || slot.taskId != peer.id
                    || slot.controller != peer.controller || !peer.token.equals(slot.taskToken)
                    || !slot.occupied || slot.parked || slot.frozen || slot.promoting || slot.launching)
                return false;
        }
        return true;
    }

    /** Real A/B leashes stay in their original columns as their roles expand/shrink. */
    private void startLiveSwap(Promotion ticket) {
        if (!h.externalPromotionCurrent(ticket) || ticket.previousMain == null) {
            h.failPromotion(ticket, "live swap scene changed before preparation");
            return;
        }
        final MiStepLayout geometry = h.layout;
        final WorkspaceOptions options = h.workspaceOptions;
        final MiStepLayout target = h.makeAdaptiveLayout(geometry.tabletLandscape
                ? geometry.mainOnRight : options.mainOnRightAfterSwap(geometry.mainOnRight));
        MiStepHost.log("live swap mode=" + options.swapMode + " mainRight=" + geometry.mainOnRight + " -> " + target.mainOnRight);
        final int width = h.screenW, height = h.screenH;
        final LiveTask first = ticket.previousMain;
        LiveSwapGeometry.Task a = new LiveSwapGeometry.Task(first.id, first.token, width, height,
                liveMainRect(geometry), liveCellRect(target, ticket.slot.index));
        LiveSwapGeometry.Task b = new LiveSwapGeometry.Task(ticket.taskId, ticket.token, width, height,
                liveCellRect(geometry, ticket.slot.index), liveMainRect(target));
        final Object hostSurface;
        final java.util.List<LiveTaskSwapDriver.Peer> peers = new java.util.ArrayList<>();
        final Object[] cellBackgrounds = new Object[h.CELL_SLOT_COUNT - 1];
        try {
            hostSurface = liveHostRootSurface();
            for (int i = 1; i < h.CELL_SLOT_COUNT; i++) {
                Slot peer = h.slots[i];
                cellBackgrounds[i - 1] = peer.cellBackground;
                if (peer == ticket.slot || !peer.occupied) continue;
                if (peer.parked || peer.frozen || peer.carrier || peer.launching || peer.promoting
                        || peer.windowMode != 6 || peer.taskToken == null || peer.controller == null)
                    throw new IllegalStateException("peer not live for flip slot=" + i);
                ticket.livePeers.add(new LivePeerBinding(peer));
                Object peerInfo = h.rootInfo(new LiveTask(peer));
                if (!h.ordinaryRoot(peerInfo, 6))
                    throw new IllegalStateException("peer user or identity changed slot=" + i);
                peers.add(new LiveTaskSwapDriver.Peer(new LiveSwapGeometry.Task(peer.taskId,
                        peer.taskToken, width, height, liveCellRect(geometry, i), liveCellRect(target, i)),
                        peer.controller, XposedHelpers.getIntField(peerInfo, "userId")));
            }
            ticket.liveBackdrop = MiStepLiveBackdrop.create(hostSurface, h.wallpaperBackdrop, width, height,
                    // Include the h.main/side gap while its normal Host backing is hidden.
                    liveSideStrip(geometry), liveSideStrip(target),
                    canvas -> drawLiveEmptyHints(canvas, geometry, target, 0), MiStepHost::log);
        } catch (Throwable error) { h.failPromotion(ticket, "live flip Host preparation: " + error); return; }
        ticket.nativeSwap = true;
        ticket.nativeFlip = true;
        ticket.previousLayout = geometry;
        ticket.targetLayout = target;
        ticket.targetMainOnRight = target.mainOnRight;
        final LiveSwapZoom.Zoom oldZoom = new LiveSwapZoom.Zoom(++h.zoomCommandGeneration,
                geometry.mainScale, geometry.mainOffsetX, geometry.mainOffsetY);
        final LiveSwapZoom.Zoom targetZoom = new LiveSwapZoom.Zoom(++h.zoomCommandGeneration,
                target.mainScale, target.mainOffsetX, target.mainOffsetY);
        ticket.slot.promoting = true;
        OwnedTask candidate = h.ownedTasks.computeIfAbsent(first.token, token -> new OwnedTask(first.id, token));
        candidate.recoveryController = ticket.slot.controller;
        candidate.recoveryCandidate = true;
        ticket.previousMainCandidate = candidate;
        OwnedTask oldSide = h.ownedTasks.get(ticket.token);
        if (oldSide != null) {
            oldSide.recoveryController = ticket.slot.controller;
            oldSide.recoveryCandidate = true;
        }
        ticket.liveVisual = new LiveSwapVisualLease(h.sideBackingViews[ticket.slot.index], ticket.slot.hint);
        for (int i = 1; i < h.CELL_SLOT_COUNT; i++) if (i != ticket.slot.index)
            ticket.peerVisuals.add(new LiveSwapVisualLease(h.sideBackingViews[i], h.slots[i].hint));
        if (!setLiveSwapInputBlocked(true)) {
            h.failPromotion(ticket, "transition input window unavailable"); return;
        }
        // Hold the full input region until the final Host draw. App gestures while
        // these tasks are borrowed can start another transition and overwrite their matrices.
        h.statusBar.runAfterHiddenFrame(ticket, () -> afterLiveHostFrame(ticket, () -> {
            if (!h.externalPromotionCurrent(ticket) || h.layout != geometry) {
                h.failPromotion(ticket, "live swap input frame scene changed");
                return;
            }
            MiStepSwapOverlayBridge.acquire(h.ctx,h.main,oldZoom,targetZoom,overlays->{
            if(!h.externalPromotionCurrent(ticket) || h.layout!=geometry) {
                overlays.close();h.failPromotion(ticket,"live toolbox preparation scene changed");return;
            }
            if (!h.executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (h.externalPromotionCurrent(ticket) && h.layout == geometry && livePeerBindingsCurrent(ticket)) {
                        Object shellTransitions = XposedHelpers.getObjectField(h.transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < h.zoomAnimUntil || !Boolean.TRUE.equals(
                                ShellCompat.call(shellTransitions, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        Object info = h.rootInfo(first);
                        Object sideInfo = h.rootInfo(new LiveTask(ticket.slot));
                        if (!h.ordinaryRoot(info, 1) || !h.ordinaryRoot(sideInfo, 6))
                            throw new IllegalStateException("live main/side identity changed");
                        int user = XposedHelpers.getIntField(info, "userId");
                        int sideUser = XposedHelpers.getIntField(sideInfo, "userId");
                        ticket.previousMainCandidate.userId = user;
                        if (oldSide != null) oldSide.userId = sideUser;
                        ticket.liveMainUserId = sideUser;
                        h.traceSwapEvent("before-single-native-transition");
                        LiveTaskSwapDriver.Flip request = new LiveTaskSwapDriver.Flip(a, b, user, sideUser,
                                new Rect(0, 0, width, height), hostSurface, oldZoom, targetZoom,
                                (zoom, done) -> applyLiveZoom(ticket, zoom, done),
                                (zoom, restoring, done) -> applyLiveMainLayout(ticket, zoom, restoring, done),
                                peers.toArray(new LiveTaskSwapDriver.Peer[0]), cellBackgrounds, ticket.liveBackdrop);
                        request.overlays=overlays;
                        accepted = LiveTaskSwapDriver.startFlip(h.ctx.getClassLoader(), h.transitions,
                                ticket.slot.controller, request,
                                () -> h.externalPromotionCurrent(ticket) && livePeerBindingsCurrent(ticket)
                                        && (h.layout == geometry || h.layout == target),
                                frames -> { /* Native input follows these real task surfaces. */ },
                                result -> finishLiveResult(ticket, result), MiStepHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { MiStepHost.log("native live swap rejected: " + error); }
                if(!accepted)overlays.close();
                if (!accepted) h.main.post(() -> finishLiveSwap(ticket,
                        new MiStepLiveSwap.Result(false, false,
                                new IllegalStateException("native preflight refused; no fallback transition"))));
            })) {overlays.close();h.failPromotion(ticket, "live swap Shell queue unavailable");}
            },error->h.failPromotion(ticket,error));
        }));
    }

    /** Paint only empty Host controls; real app buffers remain the original task surfaces. */
    void drawLiveEmptyHints(android.graphics.Canvas canvas, MiStepLayout original,
            MiStepLayout target, int revealEmptySlots) {
        // Match the resting side background, including gaps outside the hint bounds.
        canvas.drawColor(HyperOsStyle.topWallpaperTint(h.ctx));
        int outer = canvas.save();
        try {
            canvas.scale((float) h.wallpaperBackdrop.getWidth() / original.width,
                    (float) h.wallpaperBackdrop.getHeight() / original.height);
            for (int i = 1; i < h.CELL_SLOT_COUNT; i++) {
                Slot slot = h.slots[i];
                boolean revealed = (revealEmptySlots & (1 << i)) != 0;
                if (slot.hint == null || (!revealed
                        && (slot.occupied || slot.hint.getVisibility() != View.VISIBLE))) continue;
                // Old cells remain until naturally covered by the growing app; target
                // cells emerge below the shrinking app. Finish crops to the target side.
                // A revealed cell paints only its Host hint underneath the moving task.
                int[] before = original.cellBounds(i - 1), after = target.cellBounds(i - 1);
                int[][] positions = java.util.Arrays.equals(before, after)
                        ? new int[][]{before} : new int[][]{before, after};
                for (int[] rect : positions) {
                    int saved = canvas.save();
                    try {
                        canvas.translate(rect[0], rect[1]);
                        canvas.clipRect(0, 0, rect[2] - rect[0], rect[3] - rect[1]);
                        if (revealed) {
                            // An occupied hint may still have its pre-wallpaper fallback.
                            // Paint fresh empty chrome rather than revealing that dark tile.
                            WallpaperCropDrawable empty = new WallpaperCropDrawable(h.wallpaperBackdrop,
                                    original.width, original.height, rect[0], rect[1],
                                    HyperOsStyle.topWallpaperTint(h.ctx));
                            empty.setBounds(0, 0, rect[2] - rect[0], rect[3] - rect[1]);
                            empty.draw(canvas);
                            h.drawEmptyCellPlus(canvas, rect[2] - rect[0], rect[3] - rect[1],
                                    HyperOsStyle.textSecondary(h.ctx), new android.graphics.Paint(
                                            android.graphics.Paint.ANTI_ALIAS_FLAG));
                            continue;
                        }
                        android.graphics.drawable.Drawable background = slot.hint.getBackground();
                        if (background instanceof WallpaperCropDrawable) {
                            ((WallpaperCropDrawable) background).withScreenOrigin(rect[0], rect[1],
                                    () -> slot.hint.draw(canvas));
                        } else slot.hint.draw(canvas);
                    } finally { canvas.restoreToCount(saved); }
                }
            }
        } finally { canvas.restoreToCount(outer); }
    }

    /** B covers the existing Home; Home, Host direction and WMS Z do not participate. */
    private void startLiveHome(Promotion ticket) {
        if (!h.externalPromotionCurrent(ticket) || ticket.previousMain != null || ticket.homeBeforeCover == null) {
            h.failPromotion(ticket, "live Home scene/identity changed before preparation");
            return;
        }
        final MiStepLayout geometry = h.layout;
        final int width = h.screenW, height = h.screenH;
        final Object hostSurface;
        try {
            hostSurface = liveHostRootSurface();
            Rect side = liveSideStrip(geometry);
            ticket.liveBackdrop = MiStepLiveBackdrop.create(hostSurface, h.wallpaperBackdrop, width, height,
                    side, side, canvas -> drawLiveEmptyHints(canvas, geometry, geometry,
                            1 << ticket.slot.index), MiStepHost::log);
        }
        catch (Throwable error) { h.failPromotion(ticket, "live Home Host preparation: " + error); return; }
        LiveSwapGeometry.Task app = new LiveSwapGeometry.Task(ticket.taskId, ticket.token, width, height,
                liveCellRect(geometry, ticket.slot.index), liveMainRect(geometry));
        final LiveSwapZoom.Zoom zoom = new LiveSwapZoom.Zoom(++h.zoomCommandGeneration,
                geometry.mainScale, geometry.mainOffsetX, geometry.mainOffsetY);
        ticket.nativeSwap = true;
        ticket.nativeHome = true;
        ticket.previousLayout = ticket.targetLayout = geometry;
        ticket.slot.promoting = true;
        OwnedTask owned = h.ownedTasks.get(ticket.token);
        if (owned != null) { owned.recoveryController = ticket.slot.controller; owned.recoveryCandidate = true; }
        ticket.liveVisual = new LiveSwapVisualLease(h.sideBackingViews[ticket.slot.index], ticket.slot.hint);
        if (!setLiveSwapInputBlocked(true)) {
            h.failPromotion(ticket, "transition input window unavailable"); return;
        }
        h.statusBar.runAfterHiddenFrame(ticket, () -> afterLiveHostFrame(ticket, () -> {
            if (!h.externalPromotionCurrent(ticket) || h.layout != geometry) {
                h.failPromotion(ticket, "live Home input frame scene changed");
                return;
            }
            if (!h.executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (h.externalPromotionCurrent(ticket) && h.layout == geometry) {
                        Object shell = XposedHelpers.getObjectField(h.transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < h.zoomAnimUntil || !Boolean.TRUE.equals(
                                XposedHelpers.callMethod(shell, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        Object info = h.rootInfo(new LiveTask(ticket.slot));
                        Object homeInfo = h.rootInfo(ticket.homeBeforeCover);
                        if (!h.ordinaryRoot(info, 6) || !h.isHomeRoot(homeInfo) || h.windowModeOf(homeInfo) != 1)
                            throw new IllegalStateException("live Home/app identity changed");
                        int user = XposedHelpers.getIntField(info, "userId");
                        int homeUser = XposedHelpers.getIntField(homeInfo, "userId");
                        ticket.liveMainUserId = user;
                        if (owned != null) owned.userId = user;
                        LiveTaskSwapDriver.Home request = new LiveTaskSwapDriver.Home(app,
                                ticket.homeBeforeCover.id, ticket.homeBeforeCover.token, user, homeUser,
                                new Rect(0, 0, width, height), hostSurface, zoom,
                                new Object[]{ticket.slot.cellBackground}, ticket.liveBackdrop);
                        accepted = LiveTaskSwapDriver.startHome(h.ctx.getClassLoader(), h.transitions,
                                ticket.slot.controller, request,
                                () -> h.externalPromotionCurrent(ticket) && h.layout == geometry,
                                frames -> { /* Real task input follows the same surface transaction. */ },
                                result -> finishLiveResult(ticket, result), MiStepHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { MiStepHost.log("native Home promotion rejected: " + error); }
                if (!accepted) h.main.post(() -> finishLiveHome(ticket, new MiStepLiveSwap.Result(false,
                        true, new IllegalStateException("native Home preflight refused; no fallback transition"))));
            })) h.failPromotion(ticket, "live Home Shell queue unavailable");
        }));
    }

    /** Commit the Host visual/input hole itself before moving real tasks below its window. */
    void afterLiveHostFrame(Promotion ticket, Runnable action) { finishCtl.afterLiveHostFrame(ticket, action); }

    boolean holdsLiveFlipZoom() { return finishCtl.holdsLiveFlipZoom(); }

    /** Recovery may run after screen-off, so this deliberately omits entry-only guards. */
    boolean livePromotionBound(Promotion ticket) { return finishCtl.livePromotionBound(ticket); }


    private boolean registerLiveZoomReceiver() { return finishCtl.registerLiveZoomReceiver(); }

    /** WMS acknowledgement only; the native driver separately awaits the Host h.layout frame. */
    private void applyLiveZoom(Promotion ticket, LiveSwapZoom.Zoom target,            java.util.function.Consumer<LiveSwapZoom.Ack> completed) { finishCtl.applyLiveZoom(ticket, target, completed); }

    /** This callback confirms only the target Host frame, never the WMS scaling transaction. */
    private void applyLiveMainLayout(Promotion ticket, LiveSwapZoom.Zoom zoom, boolean restoring,            java.util.function.Consumer<LiveSwapZoom.FrameAck> completed) { finishCtl.applyLiveMainLayout(ticket, zoom, restoring, completed); }

    /** Already on Shell's completion turn, after exact roles and the finish Surface commit. */
    void finishLiveResult(Promotion ticket, MiStepLiveSwap.Result result) { finishCtl.finishLiveResult(ticket, result); }

    private PromotionFocusTransfer.Result requestLiveMainFocus(Promotion ticket) throws Exception { return finishCtl.requestLiveMainFocus(ticket); }

    void finishLiveSwap(Promotion ticket, MiStepLiveSwap.Result result) { finishCtl.finishLiveSwap(ticket, result); }

    void finishLiveHome(Promotion ticket, MiStepLiveSwap.Result result) { finishCtl.finishLiveHome(ticket, result); }

    /** Last-resort teardown when the native cancellation path is proven inert (watchdog
     *  force) or a completion arrives stale: releases every global latch with NO WM
     *  write — the native outcome is unknown. Stale completions never freeze
     *  (keepCellUnfrozen=true): with the ticket dead, slot/task reconciliation
     *  (TaskInfo callbacks, visibility/mode repair) is the only healer, and freezing
     *  disables exactly those. Freezing (keepCellUnfrozen=false) is reserved for the
     *  inert-cancellation wedge, where a transition may still be mid-flight and
     *  reconciliation would fight it; it heals at session exit per the
     *  unconfirmed-completion convention. */
    void forceAbandonLivePromotion(Promotion ticket, String reason, boolean keepCellUnfrozen) { finishCtl.forceAbandonLivePromotion(ticket, reason, keepCellUnfrozen); }

    void restoreLiveSwapVisual(Promotion ticket) { finishCtl.restoreLiveSwapVisual(ticket); }
}
