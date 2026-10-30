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
    private final MiStepHost h;

    HostSwapController(MiStepHost host) {
        h = host;
    }

    private View transitionInputWindow;
    private BroadcastReceiver liveZoomReceiver;
    private LiveZoomRequest pendingLiveZoom;

    // ---------------------------------------------------------------- v0.9 触摸仲裁与定向注入

    /**
     * 触摸规则（官方Mi Step语义 + 演示需要）：
     *   主区由系统处理，宿主不接收其触摸；
     *   副格有任务 → 轻点=与主区交换；滑动取消轻点，始终不操作副任务；
     *   空槽 → 不消费，交给宿主点击处理。
     */
    boolean onSlotTouch(int slotIndex, MotionEvent e) {
        if (slotIndex <= MiStepHost.SLOT_MAIN || slotIndex >= MiStepHost.SLOT_COUNT) return false;
        MiStepHost.Slot slot = h.slots[slotIndex];
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

    void recycleBuffered(MiStepHost.Slot slot) {
        if (slot.bufferedDown != null) {
            slot.bufferedDown.recycle();
            slot.bufferedDown = null;
        }
    }

    boolean followSideDismiss(MiStepHost.Slot slot, MotionEvent e) {
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

    void dismissSideToBackground(MiStepHost.Slot slot) {
        if (h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked || h.transferBusy || h.promotion != null
                || h.cellMotion != null || !h.isActive(slot) || !slot.occupied || slot.carrier
                || slot.parked || slot.frozen || slot.promoting || slot.launching || slot.closing
                || slot.taskId < 0 || slot.taskToken == null || slot.controller == null) return;
        for (MiStepHost.Slot other : h.slots) if (other.launching || other.promoting || other.closing) return;
        final int taskId = slot.taskId;
        final long binding = slot.taskEpoch;
        final long epoch = h.sceneEpoch;
        final View hostRoot = h.root;
        MiStepHost.log("side outward swipe -> background task=" + taskId + " slot=" + slot.index);
        MiStepLiveBackdrop preparing = null;
        MiStepHost.LiveSwapVisualLease preparingVisuals = null;
        try {
            final MiStepLayout geometry = h.layout;
            final int emptyMask = 1 << slot.index;
            Rect strip = liveSideStrip(geometry);
            final MiStepLiveBackdrop backing = preparing = MiStepLiveBackdrop.create(liveHostRootSurface(),
                    h.wallpaperBackdrop, h.screenW, h.screenH, strip, strip,
                    canvas -> drawLiveEmptyHints(canvas, geometry, geometry, emptyMask), MiStepHost::log);
            final MiStepHost.LiveSwapVisualLease visuals = preparingVisuals =
                    new MiStepHost.LiveSwapVisualLease(h.sideBackingViews[slot.index], slot.hint);
            h.transferBusy = true;
            slot.promoting = true;
            final MiStepSideDismiss[] run = new MiStepSideDismiss[1];
            Runnable release = () -> {
                run[0].close();
                if (h.sideDismiss == run[0]) { h.sideDismiss = null; h.sideDismissSlot = null; }
                recycleBuffered(slot); slot.injecting = false;
                if (h.root == hostRoot && h.sceneEpoch == epoch) {
                    for (MiStepHost.Slot cell : h.slots) h.updateCellSurfaceVisibility(cell);
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
    void transferTouchToTask(MiStepHost.Slot slot, long eventTime) {
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
            if (h.scene != MiStepHost.ACTIVE || h.transferBusy) return;
            MiStepHost.Slot slot = h.slots[slotIndex];
            if (slot.closing) return;
            if (slot.carrier) {
                h.restoreCarrierToMain();
                return;
            }
            if (slot.parked && slot.occupied && !slot.frozen) {
                h.adoptExisting(slot, new MiStepHost.LiveTask(slot), h::finishPendingSuspend,
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
    void captureMainIntoEmptySlot(MiStepHost.Slot requestedSlot) {
        if (requestedSlot == null) return;
        final MiStepHost.Slot slot = requestedSlot;
        if (h.carrierSlotIndex >= 0) {
            android.widget.Toast.makeText(h.ctx, "先点副格把相机放回主区",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked || h.transferBusy
                || h.promotion != null || h.swapPreview != null
                || slot.occupied || slot.launching || slot.promoting || slot.frozen
                || slot.controller == null) return;
        for (MiStepHost.Slot other : h.slots) if (other.launching || other.promoting || other.closing) return;
        final long epoch = h.sceneEpoch;
        h.transferBusy = true;
        if (!h.executeOnShell(() -> {
            try {
                MiStepHost.LiveTask task = h.findMainTask(); // Deliberately excludes Home and existing cells.
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
    void startLiveCollect(MiStepHost.Slot slot, MiStepHost.LiveTask task) {
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
        MiStepHost.Promotion ticket = new MiStepHost.Promotion(slot, h.slots);
        ticket.sceneEpoch = h.sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        ticket.previousMain = task;
        ticket.previousLayout = ticket.targetLayout = geometry;
        ticket.nativeSwap = ticket.nativeCollect = true;
        slot.promoting = true;
        h.promotion = ticket;
        h.transferBusy = false;
        MiStepHost.OwnedTask candidate = h.ownedTasks.computeIfAbsent(task.token, key -> new MiStepHost.OwnedTask(task.id, key));
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
        ticket.liveVisual = new MiStepHost.LiveSwapVisualLease(h.sideBackingViews[slot.index], null);
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
                        if (!h.ordinaryRoot(info, 1) || !XposedHelpers.getBooleanField(info, "isVisible"))
                            throw new IllegalStateException("collect fullscreen identity changed");
                        candidate.userId = XposedHelpers.getIntField(info, "userId");
                        ticket.collectionHome = h.existingHomeForCollection(task);
                        h.resetIdleTaskView(slot);
                        accepted = MiStepLiveCollect.start(h.ctx.getClassLoader(), h.transitions,
                                slot.controller, app, ticket.collectionHome.token, candidate.userId,
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

    void prepareCollectHandoff(MiStepHost.Promotion ticket, Consumer<Boolean> settled) {
        if (!h.externalPromotionCurrent(ticket)) { settled.accept(false); return; }
        // The real app is still above Host. Remove the opaque empty-cell drawing
        // now, commit that frame, then let Shell return the app to its TaskView.
        if (ticket.slot.hint != null) ticket.slot.hint.setVisibility(View.GONE);
        MiStepCollectFrame.await(h.root, () -> h.externalPromotionCurrent(ticket), settled);
    }

    void finishLiveCollect(MiStepHost.Promotion ticket, MiStepLiveSwap.Result result) {
        if (ticket.nativeCompleting) return;
        if (!livePromotionBound(ticket)) {
            if (h.promotion == ticket && !ticket.cancelled)
                forceAbandonLivePromotion(ticket, "stale live collect completion", bindingIntact(ticket));
            return;
        }
        ticket.nativeCompleting = true;
        MiStepHost.Slot slot = ticket.slot;
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

    void completeLiveCollect(MiStepHost.Promotion ticket, MiStepLiveSwap.Result result) {
        if (h.promotion != ticket || ticket.cancelled) return;
        MiStepHost.Slot slot = ticket.slot;
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
        MiStepHost.Slot cellSlot = h.slots[slotIndex];
        if (h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked || h.transferBusy
                || h.swapPreview != null || cellSlot.parked
                || h.promotion != null || cellSlot.launching || cellSlot.promoting) return;
        if (cellSlot.carrier) {
            // 镜像承载的任务本身仍在主区：把画面移回来即完成“升为主区”。
            h.restoreCarrierToMain();
            return;
        }
        for (MiStepHost.Slot s : h.slots) if (s.launching || s.closing) return;
        if (!cellSlot.occupied || cellSlot.controller == null) {
            captureMainIntoEmptySlot(cellSlot);
            return;
        }
        if (cellSlot.taskToken == null) {
            MiStepHost.log("promote: task token not ready; keep cell" + slotIndex);
            return;
        }
        final MiStepHost.Promotion ticket = new MiStepHost.Promotion(cellSlot, h.slots);
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
        return new Rect(geometry.mainOnRight ? 0 : geometry.mainWidth, geometry.topStrip,
                geometry.mainOnRight ? geometry.sideWidth : geometry.width, geometry.height);
    }

    private boolean livePeerBindingsCurrent(MiStepHost.Promotion ticket) {
        for (MiStepHost.LivePeerBinding peer : ticket.livePeers) {
            MiStepHost.Slot slot = peer.slot;
            if (!h.isActive(slot) || slot.taskEpoch != peer.epoch || slot.taskId != peer.id
                    || slot.controller != peer.controller || !peer.token.equals(slot.taskToken)
                    || !slot.occupied || slot.parked || slot.frozen || slot.promoting || slot.launching)
                return false;
        }
        return true;
    }

    /** Real A/B leashes stay in their original columns as their roles expand/shrink. */
    private void startLiveSwap(MiStepHost.Promotion ticket) {
        if (!h.externalPromotionCurrent(ticket) || ticket.previousMain == null) {
            h.failPromotion(ticket, "live swap scene changed before preparation");
            return;
        }
        final MiStepLayout geometry = h.layout;
        final MiStepLayout target = h.makeAdaptiveLayout(!geometry.mainOnRight);
        final int width = h.screenW, height = h.screenH;
        final MiStepHost.LiveTask first = ticket.previousMain;
        LiveSwapGeometry.Task a = new LiveSwapGeometry.Task(first.id, first.token, width, height,
                liveMainRect(geometry), liveCellRect(target, ticket.slot.index));
        LiveSwapGeometry.Task b = new LiveSwapGeometry.Task(ticket.taskId, ticket.token, width, height,
                liveCellRect(geometry, ticket.slot.index), liveMainRect(target));
        final Object hostSurface;
        final java.util.List<LiveTaskSwapDriver.Peer> peers = new java.util.ArrayList<>();
        final Object[] cellBackgrounds = new Object[MiStepHost.SLOT_COUNT - 1];
        try {
            hostSurface = liveHostRootSurface();
            for (int i = 1; i < MiStepHost.SLOT_COUNT; i++) {
                MiStepHost.Slot peer = h.slots[i];
                cellBackgrounds[i - 1] = peer.cellBackground;
                if (peer == ticket.slot || !peer.occupied) continue;
                if (peer.parked || peer.frozen || peer.carrier || peer.launching || peer.promoting
                        || peer.windowMode != 6 || peer.taskToken == null || peer.controller == null)
                    throw new IllegalStateException("peer not live for flip slot=" + i);
                ticket.livePeers.add(new MiStepHost.LivePeerBinding(peer));
                peers.add(new LiveTaskSwapDriver.Peer(new LiveSwapGeometry.Task(peer.taskId,
                        peer.taskToken, width, height, liveCellRect(geometry, i), liveCellRect(target, i)),
                        peer.controller));
            }
            ticket.liveBackdrop = MiStepLiveBackdrop.create(hostSurface, h.wallpaperBackdrop, width, height,
                    // Include the h.main/side gap while its normal Host backing is hidden.
                    new Rect(geometry.mainOnRight ? 0 : geometry.mainWidth, geometry.topStrip,
                            geometry.mainOnRight ? geometry.sideWidth : width, height),
                    new Rect(target.mainOnRight ? 0 : target.mainWidth, target.topStrip,
                            target.mainOnRight ? target.sideWidth : width, height),
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
        MiStepHost.OwnedTask candidate = h.ownedTasks.computeIfAbsent(first.token, token -> new MiStepHost.OwnedTask(first.id, token));
        candidate.recoveryController = ticket.slot.controller;
        candidate.recoveryCandidate = true;
        ticket.previousMainCandidate = candidate;
        MiStepHost.OwnedTask oldSide = h.ownedTasks.get(ticket.token);
        if (oldSide != null) {
            oldSide.recoveryController = ticket.slot.controller;
            oldSide.recoveryCandidate = true;
        }
        ticket.liveVisual = new MiStepHost.LiveSwapVisualLease(h.sideBackingViews[ticket.slot.index], ticket.slot.hint);
        for (int i = 1; i < MiStepHost.SLOT_COUNT; i++) if (i != ticket.slot.index)
            ticket.peerVisuals.add(new MiStepHost.LiveSwapVisualLease(h.sideBackingViews[i], h.slots[i].hint));
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
            if (!h.executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (h.externalPromotionCurrent(ticket) && h.layout == geometry && livePeerBindingsCurrent(ticket)) {
                        Object shellTransitions = XposedHelpers.getObjectField(h.transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < h.zoomAnimUntil || !Boolean.TRUE.equals(
                                XposedHelpers.callMethod(shellTransitions, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        Object info = h.rootInfo(first);
                        if (info == null) throw new IllegalStateException("live main task disappeared");
                        int user = XposedHelpers.getIntField(info, "userId");
                        ticket.previousMainCandidate.userId = user;
                        if (oldSide != null) oldSide.userId = user;
                        h.traceSwapEvent("before-single-native-transition");
                        LiveTaskSwapDriver.Flip request = new LiveTaskSwapDriver.Flip(a, b, user,
                                new Rect(0, 0, width, height), hostSurface, oldZoom, targetZoom,
                                (zoom, done) -> applyLiveZoom(ticket, zoom, done),
                                (zoom, restoring, done) -> applyLiveMainLayout(ticket, zoom, restoring, done),
                                peers.toArray(new LiveTaskSwapDriver.Peer[0]), cellBackgrounds, ticket.liveBackdrop);
                        accepted = LiveTaskSwapDriver.startFlip(h.ctx.getClassLoader(), h.transitions,
                                ticket.slot.controller, request,
                                () -> h.externalPromotionCurrent(ticket) && livePeerBindingsCurrent(ticket)
                                        && (h.layout == geometry || h.layout == target),
                                frames -> { /* Native input follows these real task surfaces. */ },
                                result -> finishLiveResult(ticket, result), MiStepHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { MiStepHost.log("native live swap rejected: " + error); }
                if (!accepted) h.main.post(() -> finishLiveSwap(ticket,
                        new MiStepLiveSwap.Result(false, false,
                                new IllegalStateException("native preflight refused; no fallback transition"))));
            })) h.failPromotion(ticket, "live swap Shell queue unavailable");
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
            for (int i = 1; i < MiStepHost.SLOT_COUNT; i++) {
                MiStepHost.Slot slot = h.slots[i];
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
    private void startLiveHome(MiStepHost.Promotion ticket) {
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
        MiStepHost.OwnedTask owned = h.ownedTasks.get(ticket.token);
        if (owned != null) { owned.recoveryController = ticket.slot.controller; owned.recoveryCandidate = true; }
        ticket.liveVisual = new MiStepHost.LiveSwapVisualLease(h.sideBackingViews[ticket.slot.index], ticket.slot.hint);
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
                        Object info = h.rootInfo(new MiStepHost.LiveTask(ticket.slot));
                        if (info == null) throw new IllegalStateException("live Home app disappeared");
                        int user = XposedHelpers.getIntField(info, "userId");
                        if (owned != null) owned.userId = user;
                        LiveTaskSwapDriver.Home request = new LiveTaskSwapDriver.Home(app,
                                ticket.homeBeforeCover.id, ticket.homeBeforeCover.token, user,
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
    void afterLiveHostFrame(MiStepHost.Promotion ticket, Runnable action) {
        final View drawingRoot = h.root;
        if (!h.externalPromotionCurrent(ticket) || drawingRoot == null
                || !drawingRoot.isAttachedToWindow() || !drawingRoot.isHardwareAccelerated()) {
            h.failPromotion(ticket, "live Host draw cannot commit");
            return;
        }
        try {
            drawingRoot.getViewTreeObserver().registerFrameCommitCallback(() -> h.main.post(() -> {
                if (h.root == drawingRoot && h.externalPromotionCurrent(ticket)) action.run();
                else h.failPromotion(ticket, "live Host frame scene changed");
            }));
            drawingRoot.invalidate();
        } catch (Throwable error) { h.failPromotion(ticket, "live Host frame registration: " + error); }
    }

    boolean holdsLiveFlipZoom() {
        MiStepHost.Promotion active = h.promotion;
        return active != null && active.nativeFlip && !active.cancelled;
    }

    /** Recovery may run after screen-off, so this deliberately omits entry-only guards. */
    boolean livePromotionBound(MiStepHost.Promotion ticket) {
        return h.promotion == ticket && !ticket.cancelled && h.isActive(ticket.slot)
                && h.sceneEpoch == ticket.sceneEpoch && ticket.slot.taskEpoch == ticket.bindingEpoch
                && ticket.slot.taskId == ticket.taskId && ticket.token.equals(ticket.slot.taskToken);
    }

    private final class LiveZoomRequest implements Runnable {
        final MiStepHost.Promotion ticket;
        final LiveSwapZoom.Zoom expected;
        final java.util.function.Consumer<LiveSwapZoom.Ack> completed;
        boolean delivered;
        LiveZoomRequest(MiStepHost.Promotion ticket, LiveSwapZoom.Zoom expected,
                java.util.function.Consumer<LiveSwapZoom.Ack> completed) {
            this.ticket = ticket; this.expected = expected; this.completed = completed;
        }
        void complete(LiveSwapZoom.Ack ack) {
            if (delivered) return;
            delivered = true;
            h.main.removeCallbacks(this);
            if (pendingLiveZoom == this) pendingLiveZoom = null;
            completed.accept(ack);
        }
        void failed(String reason) {
            complete(new LiveSwapZoom.Ack(expected.generation, 0L,
                    expected.scale, expected.x, expected.y, false, reason));
        }
        @Override public void run() { failed("WMS live zoom commit timed out"); }
    }

    private boolean registerLiveZoomReceiver() {
        if (liveZoomReceiver != null) return true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                LiveZoomRequest pending = pendingLiveZoom;
                if (intent == null || !LiveSwapZoomProtocol.ACK.equals(intent.getAction())) return;
                Object replay;
                try {
                    replay = intent.getParcelableExtra(LiveSwapZoomProtocol.FINISH_REPLAY,
                            android.view.SurfaceControl.Transaction.class);
                } catch (RuntimeException unreadable) {
                    if (pending != null) pending.failed("WMS finish replay parcel unreadable");
                    return;
                }
                if (pending == null || intent == null
                        || !LiveSwapZoomProtocol.ACK.equals(intent.getAction())
                        || intent.getLongExtra(LiveSwapZoomProtocol.GENERATION, -1L)
                                != pending.expected.generation) {
                    LiveSwapZoom.closeReplay(replay); return;
                }
                if (!livePromotionBound(pending.ticket)) {
                    LiveSwapZoom.closeReplay(replay);
                    pending.failed("live zoom ticket changed before WMS commit");
                    return;
                }
                pending.complete(new LiveSwapZoom.Ack(pending.expected.generation,
                        intent.getLongExtra(LiveSwapZoomProtocol.TRANSACTION, 0L),
                        Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE, 0)),
                        Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X, 0)),
                        Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y, 0)),
                        intent.getBooleanExtra(LiveSwapZoomProtocol.COMMITTED, false),
                        intent.getStringExtra(LiveSwapZoomProtocol.ERROR), replay));
            }
        };
        try {
            h.ctx.registerReceiver(receiver, new IntentFilter(LiveSwapZoomProtocol.ACK),
                    LiveSwapZoomProtocol.PERMISSION, h.main, Context.RECEIVER_EXPORTED);
            liveZoomReceiver = receiver;
            return true;
        } catch (Throwable error) {
            MiStepHost.log("live zoom receiver unavailable: " + error);
            return false;
        }
    }

    /** WMS acknowledgement only; the native driver separately awaits the Host h.layout frame. */
    private void applyLiveZoom(MiStepHost.Promotion ticket, LiveSwapZoom.Zoom target,
            java.util.function.Consumer<LiveSwapZoom.Ack> completed) {
        h.main.post(() -> {
            LiveZoomRequest request = new LiveZoomRequest(ticket, target, completed);
            if (!livePromotionBound(ticket) || !ticket.nativeFlip
                    || !registerLiveZoomReceiver()) {
                request.failed("live zoom ticket/receiver unavailable");
                return;
            }
            if (pendingLiveZoom != null) pendingLiveZoom.failed("live zoom superseded by recovery");
            pendingLiveZoom = request;
            h.zoomCommandGeneration = Math.max(h.zoomCommandGeneration, target.generation);
            h.zoomAnimUntil = SystemClock.uptimeMillis() + LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS;
            ticket.liveZoomGuardUntil = h.zoomAnimUntil;
            long issuedAt = System.currentTimeMillis();
            // ZOOM64 renews the WM lease itself. Ordinary transports would eagerly
            // reset the leashes while they still belong to the animation parent.
            // Publish their steady tuple only after the native finish commits.
            Intent intent = new Intent(LiveSwapZoomProtocol.APPLY).setPackage("android");
            intent.putExtra(LiveSwapZoomProtocol.GENERATION, target.generation);
            intent.putExtra(LiveSwapZoomProtocol.SCALE, Float.floatToIntBits(target.scale));
            intent.putExtra(LiveSwapZoomProtocol.X, Float.floatToIntBits(target.x));
            intent.putExtra(LiveSwapZoomProtocol.Y, Float.floatToIntBits(target.y));
            intent.putExtra(LiveSwapZoomProtocol.ISSUED, issuedAt);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            h.main.postDelayed(request, LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS);
            try { h.ctx.sendBroadcast(intent, LiveSwapZoomProtocol.PERMISSION); }
            catch (Throwable error) { request.failed("live zoom dispatch failed: " + error); }
        });
    }

    /** This callback confirms only the target Host frame, never the WMS scaling transaction. */
    private void applyLiveMainLayout(MiStepHost.Promotion ticket, LiveSwapZoom.Zoom zoom, boolean restoring,
            java.util.function.Consumer<LiveSwapZoom.FrameAck> completed) {
        h.main.post(() -> {
            MiStepLayout target = restoring ? ticket.previousLayout : ticket.targetLayout;
            View drawingRoot = h.root;
            java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.function.Consumer<String> fail = error -> {
                if (delivered.compareAndSet(false, true))
                    completed.accept(new LiveSwapZoom.FrameAck(zoom.generation, false, error));
            };
            if (!livePromotionBound(ticket) || !ticket.nativeFlip || target == null
                    || !zoom.sameTuple(target.mainScale, target.mainOffsetX, target.mainOffsetY)
                    || drawingRoot == null || !drawingRoot.isAttachedToWindow()
                    || !drawingRoot.isHardwareAccelerated()) {
                fail.accept("live Host layout generation/scene unavailable");
                return;
            }
            Runnable timeout = () -> fail.accept("live Host layout frame timed out");
            try {
                drawingRoot.getViewTreeObserver().registerFrameCommitCallback(() -> h.main.post(() -> {
                    if (!livePromotionBound(ticket) || h.root != drawingRoot || h.layout != target) {
                        fail.accept("live Host layout changed before frame commit");
                    } else if (delivered.compareAndSet(false, true)) {
                        h.main.removeCallbacks(timeout);
                        completed.accept(new LiveSwapZoom.FrameAck(zoom.generation, true, null));
                    }
                }));
                h.main.postDelayed(timeout, LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS);
                h.applyMainLayout(target);
            } catch (Throwable error) {
                h.main.removeCallbacks(timeout);
                fail.accept("live Host layout failed: " + error);
            }
        });
    }

    /** Already on Shell's completion turn, after exact roles and the finish Surface commit. */
    void finishLiveResult(MiStepHost.Promotion ticket, MiStepLiveSwap.Result result) {
        boolean mainReady = result.rolesConfirmed && (ticket.nativeCollect ? result.sideOwned
                : ticket.nativeHome ? result.succeeded && !result.sideOwned : result.sideOwned);
        if (mainReady && h.externalPromotionCurrent(ticket)) {
            try { ticket.liveMainFocusResult = requestLiveMainFocus(ticket); }
            catch (Throwable error) {
                ticket.liveMainFocusResult = PromotionFocusTransfer.Result.UNAVAILABLE;
                MiStepHost.log("live main focus request unavailable: " + error);
            }
            ticket.liveMainFocusPrepared = true;
            MiStepHost.log("live main focus result=" + ticket.liveMainFocusResult + " task="
                    + (ticket.nativeCollect ? ticket.collectionHome == null ? -1
                            : ticket.collectionHome.id : ticket.taskId)
                    + " uptime=" + SystemClock.uptimeMillis());
        }
        h.main.post(() -> {
            if (ticket.nativeCollect) finishLiveCollect(ticket, result);
            else if (ticket.nativeHome) finishLiveHome(ticket, result);
            else finishLiveSwap(ticket, result);
        });
    }

    private PromotionFocusTransfer.Result requestLiveMainFocus(MiStepHost.Promotion ticket) throws Exception {
        final MiStepHost.LiveTask home = ticket.nativeCollect ? ticket.collectionHome : null;
        if (ticket.nativeCollect && home == null) return PromotionFocusTransfer.Result.UNAVAILABLE;
        final int id = home == null ? ticket.taskId : home.id;
        final Object token = home == null ? ticket.token : home.token;
        final int user = h.currentUserId();
        Object service = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityTaskManager"), "getService");
        java.util.List<?> roots = (java.util.List<?>) XposedHelpers.callMethod(service, "getAllRootTaskInfos");
        if (home != null) for (Object info : roots) {
            if (XposedHelpers.getIntField(info, "displayId") != 0
                    || !XposedHelpers.getBooleanField(info, "isFocused")) continue;
            Object focused = XposedHelpers.getObjectField(info, "token");
            // A new h.main app or native freeform has priority over returning Home.
            // The collected task and other exact side tasks never own h.main input.
            if (!token.equals(focused) && !ticket.token.equals(focused)
                    && !ticket.otherSideTokens.contains(focused))
                return PromotionFocusTransfer.Result.UNAVAILABLE;
        }
        return PromotionFocusTransfer.request(new PromotionFocusTransfer.Tasks() {
            public PromotionFocusTransfer.State read(int taskId) {
                for (Object info : roots) {
                    if (XposedHelpers.getIntField(info, "taskId") != taskId
                            || (home == null ? !h.ordinaryRoot(info, 1)
                                    : !h.isHomeRoot(info) || h.windowModeOf(info) != 1)) continue;
                    return new PromotionFocusTransfer.State(XposedHelpers.getObjectField(info, "token"),
                            XposedHelpers.getIntField(info, "displayId"),
                            XposedHelpers.getIntField(info, "userId"), h.windowModeOf(info),
                            XposedHelpers.getBooleanField(info, "isVisible"),
                            XposedHelpers.getBooleanField(info, "isFocused"));
                }
                return null;
            }
            public void focus(int taskId) { XposedHelpers.callMethod(service, "setFocusedTask", taskId); }
        }, id, token, user);
    }

    void finishLiveSwap(MiStepHost.Promotion ticket, MiStepLiveSwap.Result result) {
        if (h.promotion != ticket || ticket.cancelled || ticket.nativeCompleting || !h.isActive(ticket.slot)) return;
        if (h.sceneEpoch != ticket.sceneEpoch || ticket.slot.taskEpoch != ticket.bindingEpoch
                || ticket.slot.taskId != ticket.taskId || !ticket.token.equals(ticket.slot.taskToken)) {
            MiStepHost.log("stale native completion rejected; scene or original slot binding changed");
            forceAbandonLivePromotion(ticket, "stale native completion", bindingIntact(ticket));
            return;
        }
        ticket.nativeCompleting = true;
        // This is a confirmed native finish, after the exact WMS/Host commits.
        // REQUEST_TIMEOUT_MS is a pending-request deadline, not a tap cooldown.
        if (result.rolesConfirmed && ticket.nativeFlip && pendingLiveZoom == null
                && ticket.liveZoomGuardUntil > 0L && h.zoomAnimUntil == ticket.liveZoomGuardUntil) {
            h.zoomAnimUntil = 0L;
            h.writeZoom(true);
        }
        MiStepHost.Slot slot = ticket.slot;
        if (result.sideOwned) {
            // Facade has retired B and bound the same controller to A already.
            // Clear only the old logical slot; never adopt/relaunch A a second time.
            h.ownedTasks.remove(ticket.token);
            h.clearSlot(slot);
            h.bindTask(slot, ticket.previousMain.id, ticket.previousMain.token);
            if (ticket.previousMainCandidate != null) ticket.previousMainCandidate.recoveryCandidate = false;
            slot.pkg = ticket.previousMain.pkg;
            slot.occupied = true;
            slot.taskVisible = true;
            slot.windowMode = 6;
            slot.promoting = false;
            slot.launching = false;
            slot.parked = false;
            h.updateHint(slot);
            h.finishPromotionFocus(ticket, () -> {
                if (h.promotion != ticket || ticket.cancelled) return;
                h.traceSwapEvent("single-native-finish-and-focus");
                ticket.cancelled = true;
                h.promotion = null;
                h.coveredHome = null;
                restoreLiveSwapVisual(ticket);
                h.statusBar.finish(ticket);
                h.publishDesktopSideTasks();
                MiStepHost.log("single native swap roles settled main=" + ticket.taskId + " side="
                        + slot.taskId + " animated=" + result.succeeded + " error=" + result.error);
                h.finishPendingSuspend();
            }, 0);
        } else {
            ticket.cancelled = true;
            h.promotion = null;
            boolean original = h.controllerOwns(slot, ticket.taskId, ticket.token)
                    && h.mayWriteSideSurface(slot) && result.rolesConfirmed;
            if (original) {
                if (ticket.previousMainCandidate != null)
                    h.ownedTasks.remove(ticket.previousMainCandidate.token, ticket.previousMainCandidate);
                MiStepHost.OwnedTask retained = h.ownedTasks.get(ticket.token);
                if (retained != null) retained.recoveryCandidate = false;
            } else if (!ticket.nativeSubmitted && ticket.previousMainCandidate != null) {
                // Facade refused before a WCT, so this fullscreen A was never ours.
                h.ownedTasks.remove(ticket.previousMainCandidate.token, ticket.previousMainCandidate);
            }
            slot.promoting = !original;
            slot.frozen = !original;
            restoreLiveSwapVisual(ticket);
            h.statusBar.finish(ticket);
            h.publishDesktopSideTasks();
            MiStepHost.log("single native swap unconfirmed; original side retained=" + original + " error=" + result.error);
            h.finishPendingSuspend();
        }
    }

    void finishLiveHome(MiStepHost.Promotion ticket, MiStepLiveSwap.Result result) {
        if (ticket.nativeCompleting) return;
        if (!livePromotionBound(ticket)) {
            if (h.promotion == ticket && !ticket.cancelled)
                forceAbandonLivePromotion(ticket, "stale live home completion", bindingIntact(ticket));
            return;
        }
        ticket.nativeCompleting = true;
        MiStepHost.Slot slot = ticket.slot;
        if (result.succeeded && !result.sideOwned && result.rolesConfirmed) {
            // The facade already gave B to the existing fullscreen listener and committed finishT.
            ticket.handoffAttempted = ticket.handoffComplete = true;
            h.ownedTasks.remove(ticket.token);
            h.clearSlot(slot);
            h.finishPromotionFocus(ticket, () -> {
                if (h.promotion != ticket || ticket.cancelled) return;
                restoreLiveSwapVisual(ticket);
                h.statusBar.finish(ticket);
                h.completeHomePromotion(ticket);
            }, 0);
        } else {
            boolean original = result.sideOwned && result.rolesConfirmed
                    && h.controllerOwns(slot, ticket.taskId, ticket.token) && h.mayWriteSideSurface(slot);
            MiStepHost.OwnedTask retained = h.ownedTasks.get(ticket.token);
            if (retained != null && original) retained.recoveryCandidate = false;
            ticket.cancelled = true;
            h.promotion = null;
            slot.promoting = !original;
            slot.frozen = !original;
            restoreLiveSwapVisual(ticket);
            h.statusBar.finish(ticket);
            h.publishDesktopSideTasks();
            MiStepHost.log("live Home promotion unconfirmed; original side retained=" + original + " error=" + result.error);
            h.finishPendingSuspend();
        }
    }

    /** Last-resort teardown when the native cancellation path is proven inert (watchdog
     *  force, stale completions): releases every global latch with NO WM write — the
     *  native outcome is unknown. The cell is frozen per the unconfirmed-completion
     *  convention (heals at session exit) unless the slot binding already moved on. */
    void forceAbandonLivePromotion(MiStepHost.Promotion ticket, String reason, boolean keepCellUnfrozen) {
        if (h.promotion != ticket || ticket.cancelled) return;
        ticket.cancelled = true;
        h.promotion = null;
        MiStepHost.Slot slot = ticket.slot;
        slot.promoting = !keepCellUnfrozen;
        slot.frozen = !keepCellUnfrozen;
        restoreLiveSwapVisual(ticket);
        // 对齐 completeLiveCollect 约定：按槽位状态重新推导提示与触区
        // （collect 的 prepareCollectHandoff 在租约外把 hint 置 GONE，必须显式恢复）。
        h.updateHint(slot);
        h.updateTouchRegion(slot);
        h.statusBar.finish(ticket);
        h.publishDesktopSideTasks();
        MiStepHost.log("native promotion force-abandoned; cell frozen=" + !keepCellUnfrozen + ": " + reason);
        h.finishPendingSuspend();
    }

    /** True when the ticket slot still carries exactly the ticket's binding. */
    private static boolean bindingIntact(MiStepHost.Promotion ticket) {
        return ticket.slot.taskEpoch == ticket.bindingEpoch && ticket.slot.taskId == ticket.taskId
                && java.util.Objects.equals(ticket.token, ticket.slot.taskToken);
    }

    void restoreLiveSwapVisual(MiStepHost.Promotion ticket) {
        MiStepHost.LiveSwapVisualLease visual = ticket.liveVisual;
        ticket.liveVisual = null;
        if (visual != null) visual.restore();
        for (MiStepHost.LiveSwapVisualLease peer : ticket.peerVisuals) peer.restore();
        ticket.peerVisuals.clear();
        // Native ownership and the final logical bindings now agree. Reconcile
        // once, after the replacement, rather than hiding between clear/bind.
        for (MiStepHost.Slot cell : h.slots) if (cell.index > MiStepHost.SLOT_MAIN) h.updateCellSurfaceVisibility(cell);
        if (h.root != null) { h.root.requestLayout(); h.root.invalidate(); }
        if (ticket.liveBackdrop != null) {
            ticket.liveBackdrop.closeAfterFrame(h.root); ticket.liveBackdrop = null;
        }
        if (h.geometryRefreshAfterLive) {
            h.geometryRefreshAfterLive = false;
            h.scheduleDisplayGeometryRefresh();
        }
        // The draw also restores backing/hints and removes the wallpaper lease.
        // A gesture begun while the real tasks were borrowed remains consumed to UP.
        setLiveSwapInputBlocked(false);
        h.resumeNotificationLaunch(ticket);
        // The WMS ACK receiver outlived its consumer once the ticket is done; park it.
        // registerLiveZoomReceiver lazily re-arms it on the next flip.
        if (pendingLiveZoom == null && liveZoomReceiver != null) {
            try { h.ctx.unregisterReceiver(liveZoomReceiver); }
            catch (Throwable error) { MiStepHost.log("live zoom receiver unregister failed: " + error); }
            liveZoomReceiver = null;
        }
    }
}
