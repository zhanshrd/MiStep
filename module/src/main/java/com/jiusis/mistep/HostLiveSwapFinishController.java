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

/** live 交换收尾 / 焦点归还 / 缩放接收器 / 视觉恢复 / 提升放弃路由 */
final class HostLiveSwapFinishController {
    private final MiStepHost h;
    private final HostSwapController sc;

    HostLiveSwapFinishController(HostSwapController sc, MiStepHost host) {
        this.sc = sc;
        h = host;
}

    void afterLiveHostFrame(Promotion ticket, Runnable action) {
        final View drawingRoot = sc.h.root;
        if (!sc.h.externalPromotionCurrent(ticket) || drawingRoot == null
                || !drawingRoot.isAttachedToWindow() || !drawingRoot.isHardwareAccelerated()) {
            sc.h.failPromotion(ticket, "live Host draw cannot commit");
            return;
        }
        try {
            drawingRoot.getViewTreeObserver().registerFrameCommitCallback(() -> sc.h.main.post(() -> {
                if (sc.h.root == drawingRoot && sc.h.externalPromotionCurrent(ticket)) action.run();
                else sc.h.failPromotion(ticket, "live Host frame scene changed");
            }));
            drawingRoot.invalidate();
        } catch (Throwable error) { sc.h.failPromotion(ticket, "live Host frame registration: " + error); }
    }

    boolean holdsLiveFlipZoom() {
        Promotion active = sc.h.promotion;
        return active != null && active.nativeFlip && !active.cancelled;
    }

    boolean livePromotionBound(Promotion ticket) {
        return sc.h.promotion == ticket && !ticket.cancelled && sc.h.isActive(ticket.slot)
                && sc.h.sceneEpoch == ticket.sceneEpoch && ticket.slot.taskEpoch == ticket.bindingEpoch
                && ticket.slot.taskId == ticket.taskId && ticket.token.equals(ticket.slot.taskToken);
    }

    boolean registerLiveZoomReceiver() {
        if (sc.liveZoomReceiver != null) return true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                LiveZoomRequest pending = sc.pendingLiveZoom;
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
            sc.h.ctx.registerReceiver(receiver, new IntentFilter(LiveSwapZoomProtocol.ACK),
                    LiveSwapZoomProtocol.PERMISSION, sc.h.main, Context.RECEIVER_EXPORTED);
            sc.liveZoomReceiver = receiver;
            return true;
        } catch (Throwable error) {
            MiStepHost.log("live zoom receiver unavailable: " + error);
            return false;
        }
    }

    void applyLiveZoom(Promotion ticket, LiveSwapZoom.Zoom target,
            java.util.function.Consumer<LiveSwapZoom.Ack> completed) {
        sc.h.main.post(() -> {
            LiveZoomRequest request = new LiveZoomRequest(h, sc, ticket, target, completed);
            if (!livePromotionBound(ticket) || !ticket.nativeFlip
                    || !registerLiveZoomReceiver()) {
                request.failed("live zoom ticket/receiver unavailable");
                return;
            }
            if (sc.pendingLiveZoom != null) sc.pendingLiveZoom.failed("live zoom superseded by recovery");
            sc.pendingLiveZoom = request;
            sc.h.zoomCommandGeneration = Math.max(sc.h.zoomCommandGeneration, target.generation);
            sc.h.zoomAnimUntil = SystemClock.uptimeMillis() + LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS;
            ticket.liveZoomGuardUntil = sc.h.zoomAnimUntil;
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
            sc.h.main.postDelayed(request, LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS);
            try { sc.h.ctx.sendBroadcast(intent, LiveSwapZoomProtocol.PERMISSION); }
            catch (Throwable error) { request.failed("live zoom dispatch failed: " + error); }
        });
    }

    void applyLiveMainLayout(Promotion ticket, LiveSwapZoom.Zoom zoom, boolean restoring,
            java.util.function.Consumer<LiveSwapZoom.FrameAck> completed) {
        sc.h.main.post(() -> {
            MiStepLayout target = restoring ? ticket.previousLayout : ticket.targetLayout;
            View drawingRoot = sc.h.root;
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
                drawingRoot.getViewTreeObserver().registerFrameCommitCallback(() -> sc.h.main.post(() -> {
                    if (!livePromotionBound(ticket) || sc.h.root != drawingRoot || sc.h.layout != target) {
                        fail.accept("live Host layout changed before frame commit");
                    } else if (delivered.compareAndSet(false, true)) {
                        sc.h.main.removeCallbacks(timeout);
                        completed.accept(new LiveSwapZoom.FrameAck(zoom.generation, true, null));
                    }
                }));
                sc.h.main.postDelayed(timeout, LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS);
                sc.h.applyMainLayout(target);
            } catch (Throwable error) {
                sc.h.main.removeCallbacks(timeout);
                fail.accept("live Host layout failed: " + error);
            }
        });
    }

    void finishLiveResult(Promotion ticket, MiStepLiveSwap.Result result) {
        boolean mainReady = result.rolesConfirmed && (ticket.nativeCollect ? result.sideOwned
                : ticket.nativeHome ? result.succeeded && !result.sideOwned : result.sideOwned);
        if (mainReady && sc.h.externalPromotionCurrent(ticket)) {
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
        sc.h.main.post(() -> {
            if (ticket.nativeCollect) sc.h.finishLiveCollect(ticket, result);
            else if (ticket.nativeHome) finishLiveHome(ticket, result);
            else finishLiveSwap(ticket, result);
        });
    }

    PromotionFocusTransfer.Result requestLiveMainFocus(Promotion ticket) throws Exception {
        final LiveTask home = ticket.nativeCollect ? ticket.collectionHome : null;
        if (ticket.nativeCollect && home == null) return PromotionFocusTransfer.Result.UNAVAILABLE;
        final int id = home == null ? ticket.taskId : home.id;
        final Object token = home == null ? ticket.token : home.token;
        final int user = ticket.liveMainUserId;
        if (user < 0) return PromotionFocusTransfer.Result.UNAVAILABLE;
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
                            || (home == null ? !sc.h.ordinaryRoot(info, 1)
                                    : !sc.h.isHomeRoot(info) || sc.h.windowModeOf(info) != 1)) continue;
                    return new PromotionFocusTransfer.State(XposedHelpers.getObjectField(info, "token"),
                            XposedHelpers.getIntField(info, "displayId"),
                            XposedHelpers.getIntField(info, "userId"), sc.h.windowModeOf(info),
                            sc.h.mainTaskVisible(info),
                            XposedHelpers.getBooleanField(info, "isFocused"));
                }
                return null;
            }
            public void focus(int taskId) { XposedHelpers.callMethod(service, "setFocusedTask", taskId); }
        }, id, token, user);
    }

    void finishLiveSwap(Promotion ticket, MiStepLiveSwap.Result result) {
        if (sc.h.promotion != ticket || ticket.cancelled || ticket.nativeCompleting || !sc.h.isActive(ticket.slot)) return;
        if (sc.h.sceneEpoch != ticket.sceneEpoch || ticket.slot.taskEpoch != ticket.bindingEpoch
                || ticket.slot.taskId != ticket.taskId || !ticket.token.equals(ticket.slot.taskToken)) {
            MiStepHost.log("stale native completion rejected; scene or original slot binding changed");
            forceAbandonLivePromotion(ticket, "stale native completion", true);
            return;
        }
        ticket.nativeCompleting = true;
        // This is a confirmed native finish, after the exact WMS/Host commits.
        // REQUEST_TIMEOUT_MS is a pending-request deadline, not a tap cooldown.
        if (result.rolesConfirmed && ticket.nativeFlip && sc.pendingLiveZoom == null
                && ticket.liveZoomGuardUntil > 0L && sc.h.zoomAnimUntil == ticket.liveZoomGuardUntil) {
            sc.h.zoomAnimUntil = 0L;
            sc.h.writeZoom(true);
        }
        Slot slot = ticket.slot;
        if (result.sideOwned) {
            // Facade has retired B and bound the same controller to A already.
            // Clear only the old logical slot; never adopt/relaunch A a second time.
            sc.h.ownedTasks.remove(ticket.token);
            sc.h.clearSlot(slot);
            sc.h.bindTask(slot, ticket.previousMain.id, ticket.previousMain.token);
            if (ticket.previousMainCandidate != null) ticket.previousMainCandidate.recoveryCandidate = false;
            slot.pkg = ticket.previousMain.pkg;
            slot.occupied = true;
            slot.taskVisible = true;
            slot.windowMode = 6;
            slot.promoting = false;
            slot.launching = false;
            slot.parked = false;
            sc.h.updateHint(slot);
            sc.h.finishPromotionFocus(ticket, () -> {
                if (sc.h.promotion != ticket || ticket.cancelled) return;
                sc.h.traceSwapEvent("single-native-finish-and-focus");
                ticket.cancelled = true;
                sc.h.promotion = null;
                sc.h.coveredHome = null;
                restoreLiveSwapVisual(ticket);
                sc.h.statusBar.finish(ticket);
                sc.h.publishDesktopSideTasks();
                MiStepHost.log("single native swap roles settled main=" + ticket.taskId + " side="
                        + slot.taskId + " animated=" + result.succeeded + " error=" + result.error);
                sc.h.finishPendingSuspend();
            }, 0);
        } else {
            ticket.cancelled = true;
            sc.h.promotion = null;
            boolean original = sc.h.controllerOwns(slot, ticket.taskId, ticket.token)
                    && sc.h.mayWriteSideSurface(slot) && result.rolesConfirmed;
            if (original) {
                if (ticket.previousMainCandidate != null)
                    sc.h.ownedTasks.remove(ticket.previousMainCandidate.token, ticket.previousMainCandidate);
                OwnedTask retained = sc.h.ownedTasks.get(ticket.token);
                if (retained != null) retained.recoveryCandidate = false;
            } else if (!ticket.nativeSubmitted && ticket.previousMainCandidate != null) {
                // Facade refused before a WCT, so this fullscreen A was never ours.
                sc.h.ownedTasks.remove(ticket.previousMainCandidate.token, ticket.previousMainCandidate);
            }
            slot.promoting = !original;
            slot.frozen = !original;
            restoreLiveSwapVisual(ticket);
            sc.h.statusBar.finish(ticket);
            sc.h.publishDesktopSideTasks();
            MiStepHost.log("single native swap unconfirmed; original side retained=" + original + " error=" + result.error);
            sc.h.finishPendingSuspend();
        }
    }

    void finishLiveHome(Promotion ticket, MiStepLiveSwap.Result result) {
        if (ticket.nativeCompleting) return;
        if (!livePromotionBound(ticket)) {
            if (sc.h.promotion == ticket && !ticket.cancelled)
                forceAbandonLivePromotion(ticket, "stale live home completion", true);
            return;
        }
        ticket.nativeCompleting = true;
        Slot slot = ticket.slot;
        if (result.succeeded && !result.sideOwned && result.rolesConfirmed) {
            // The facade already gave B to the existing fullscreen listener and committed finishT.
            ticket.handoffAttempted = ticket.handoffComplete = true;
            sc.h.ownedTasks.remove(ticket.token);
            sc.h.clearSlot(slot);
            sc.h.finishPromotionFocus(ticket, () -> {
                if (sc.h.promotion != ticket || ticket.cancelled) return;
                restoreLiveSwapVisual(ticket);
                sc.h.statusBar.finish(ticket);
                sc.h.completeHomePromotion(ticket);
            }, 0);
        } else {
            boolean original = result.sideOwned && result.rolesConfirmed
                    && sc.h.controllerOwns(slot, ticket.taskId, ticket.token) && sc.h.mayWriteSideSurface(slot);
            OwnedTask retained = sc.h.ownedTasks.get(ticket.token);
            if (retained != null && original) retained.recoveryCandidate = false;
            ticket.cancelled = true;
            sc.h.promotion = null;
            slot.promoting = !original;
            slot.frozen = !original;
            restoreLiveSwapVisual(ticket);
            sc.h.statusBar.finish(ticket);
            sc.h.publishDesktopSideTasks();
            MiStepHost.log("live Home promotion unconfirmed; original side retained=" + original + " error=" + result.error);
            sc.h.finishPendingSuspend();
        }
    }

    void forceAbandonLivePromotion(Promotion ticket, String reason, boolean keepCellUnfrozen) {
        if (sc.h.promotion != ticket || ticket.cancelled) return;
        ticket.cancelled = true;
        sc.h.promotion = null;
        Slot slot = ticket.slot;
        slot.promoting = !keepCellUnfrozen;
        slot.frozen = !keepCellUnfrozen;
        restoreLiveSwapVisual(ticket);
        // 对齐 completeLiveCollect 约定：按槽位状态重新推导提示与触区
        // （collect 的 prepareCollectHandoff 在租约外把 hint 置 GONE，必须显式恢复）。
        sc.h.updateHint(slot);
        sc.h.updateTouchRegion(slot);
        sc.h.statusBar.finish(ticket);
        sc.h.publishDesktopSideTasks();
        MiStepHost.log("native promotion force-abandoned; cell frozen=" + !keepCellUnfrozen + ": " + reason);
        sc.h.finishPendingSuspend();
    }

    void restoreLiveSwapVisual(Promotion ticket) {
        LiveSwapVisualLease visual = ticket.liveVisual;
        ticket.liveVisual = null;
        if (visual != null) visual.restore();
        for (LiveSwapVisualLease peer : ticket.peerVisuals) peer.restore();
        ticket.peerVisuals.clear();
        // Native ownership and the final logical bindings now agree. Reconcile
        // once, after the replacement, rather than hiding between clear/bind.
        for (Slot cell : sc.h.slots) if (cell.index > MiStepHost.SLOT_MAIN) sc.h.updateCellSurfaceVisibility(cell);
        if (sc.h.root != null) { sc.h.root.requestLayout(); sc.h.root.invalidate(); }
        if (ticket.liveBackdrop != null) {
            ticket.liveBackdrop.closeAfterFrame(sc.h.root); ticket.liveBackdrop = null;
        }
        if (sc.h.geometryRefreshAfterLive) {
            sc.h.geometryRefreshAfterLive = false;
            sc.h.scheduleDisplayGeometryRefresh();
        }
        // The draw also restores backing/hints and removes the wallpaper lease.
        // A gesture begun while the real tasks were borrowed remains consumed to UP.
        sc.setLiveSwapInputBlocked(false);
        sc.h.resumeNotificationLaunch(ticket);
        // The WMS ACK receiver outlived its consumer once the ticket is done; park it.
        // registerLiveZoomReceiver lazily re-arms it on the next flip.
        if (sc.pendingLiveZoom == null && sc.liveZoomReceiver != null) {
            try { sc.h.ctx.unregisterReceiver(sc.liveZoomReceiver); }
            catch (Throwable error) { MiStepHost.log("live zoom receiver unregister failed: " + error); }
            sc.liveZoomReceiver = null;
        }
    }

}
