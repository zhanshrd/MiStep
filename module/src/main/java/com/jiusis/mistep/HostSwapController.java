package com.jiusis.mistep;

import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

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
            Rect strip = h.liveSideStrip(geometry);
            final MiStepLiveBackdrop backing = preparing = MiStepLiveBackdrop.create(h.liveHostRootSurface(),
                    h.wallpaperBackdrop, h.screenW, h.screenH, strip, strip,
                    canvas -> h.drawLiveEmptyHints(canvas, geometry, geometry, emptyMask), MiStepHost::log);
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
            h.swapWithMain(slotIndex);
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
        try { hostSurface = h.liveHostRootSurface(); }
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
            Rect side = h.liveSideStrip(geometry);
            ticket.liveBackdrop = MiStepLiveBackdrop.create(hostSurface, h.wallpaperBackdrop, width, height,
                    side, side, canvas -> h.drawLiveEmptyHints(canvas, geometry, geometry,
                            1 << slot.index), MiStepHost::log);
        } catch (Throwable error) {
            h.failPromotion(ticket, "collect wallpaper preparation: " + error);
            return;
        }
        // Keep the empty-cell affordance until the real task occupies it.
        ticket.liveVisual = new MiStepHost.LiveSwapVisualLease(h.sideBackingViews[slot.index], null);
        h.prepareCellSurface(slot);
        if (!h.setLiveSwapInputBlocked(true)) {
            h.failPromotion(ticket, "transition input window unavailable"); return;
        }
        h.statusBar.hideFor(ticket, () -> h.failPromotion(ticket, "collect status-bar lease expired"));
        LiveSwapGeometry.Task app = new LiveSwapGeometry.Task(task.id, task.token, width, height,
                h.liveMainRect(geometry), h.liveCellRect(geometry, slot.index));
        h.statusBar.runAfterHiddenFrame(ticket, () -> h.afterLiveHostFrame(ticket, () -> {
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
                                result -> h.finishLiveResult(ticket, result), MiStepHost::log);
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
        if (!h.livePromotionBound(ticket) || ticket.nativeCompleting) return;
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
        h.restoreLiveSwapVisual(ticket);
        h.statusBar.finish(ticket);
        h.updateHint(slot);
        h.updateTouchRegion(slot);
        h.publishDesktopSideTasks();
        MiStepHost.log("live collect settled task=" + ticket.taskId + " side=" + result.sideOwned
                + " confirmed=" + result.rolesConfirmed + " animated=" + result.succeeded + " error=" + result.error);
        h.requestCompaction();
        h.finishPendingSuspend();
    }
}
