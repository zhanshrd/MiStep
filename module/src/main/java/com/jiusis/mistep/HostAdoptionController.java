package com.jiusis.mistep;

import android.content.Intent;
import android.os.Binder;
import android.os.Looper;
import android.os.UserHandle;
import android.graphics.Rect;
import android.view.View;
import java.util.function.Consumer;
import android.os.SystemClock;
import de.robv.android.xposed.XposedHelpers;

/** 收养 / 归还 / 外部提升管线（v1.2 拆分）。自 MiStepHost 原样搬出；宿主保留 shim。 */
final class HostAdoptionController {
    private final MiStepHost h;

    HostAdoptionController(MiStepHost host) { h = host; }

    void adoptExisting(MiStepHost.Slot slot, MiStepHost.LiveTask task, Runnable success, Consumer<String> failure) {
        adoptExisting(slot, task, false, success, failure);
    }

    void adoptExisting(MiStepHost.Slot slot, MiStepHost.LiveTask task, boolean collectFromMain,
            Runnable success, Consumer<String> failure) {
        if (!h.isActive(slot) || !h.boundsHooksReady || h.transferBusy || slot.frozen || slot.controller == null) {
            failure.accept("adoption busy/unavailable");
            return;
        }
        h.transferBusy = true;
        slot.promoting = true;
        slot.modeGuardUntil = SystemClock.uptimeMillis() + 2500L;
        slot.modeRepairAttempts = 0;
        slot.parked = false;
        slot.retiredTaskId = -1;
        h.prepareCellSurface(slot);
        final long epoch = h.sceneEpoch;
        if (!h.executeOnShell(() -> {
            boolean claimed = false;
            MiStepHost.NativeObservation observation = null;
            try {
                Object core = h.rootInfo(task);
                if (core == null && absentFromShellAndController(slot, task)) {
                    h.main.post(() -> {
                        h.transferBusy = false;
                        slot.promoting = false;
                        if (slot.taskId == task.id && task.token.equals(slot.taskToken)) h.clearSlot(slot);
                        failure.accept("task confirmed gone from Core/Shell/controller");
                    });
                    return;
                }
                if (!h.ordinaryRoot(core, 1) || (collectFromMain && !XposedHelpers.getBooleanField(core, "isVisible")))
                    throw new IllegalStateException("required fullscreen source no longer available");
                Object controller = slot.controller;
                h.resetIdleTaskView(slot);
                if (XposedHelpers.getObjectField(controller, "mTaskToken") != null)
                    throw new IllegalStateException("destination controller is occupied");
                if (!Boolean.TRUE.equals(XposedHelpers.getObjectField(controller, "mSurfaceCreated"))
                        || h.getSurfaceField(controller, "mSurfaceControl") == null)
                    throw new IllegalStateException("destination surface unavailable");
                Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
                Object lock = XposedHelpers.getObjectField(organizer, "mLock");
                synchronized (lock) {
                    Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTasks")).get(task.id);
                    if (appeared == null) throw new IllegalStateException("canonical task not in existing organizer");
                    Object info = XposedHelpers.callMethod(appeared, "getTaskInfo");
                    Object canonical = XposedHelpers.callMethod(appeared, "getLeash");
                    Object fallback = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTaskListeners")).get(-2);
                    Object explicit = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTaskListeners")).get(task.id);
                    if (!task.matches(info) || !h.ordinaryRoot(info, 1) || explicit != null
                            || fallback == null || XposedHelpers.callMethod(organizer, "getTaskListener", info, false) != fallback
                            || canonical == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(canonical, "isValid")))
                        throw new IllegalStateException("canonical identity/fullscreen ownership gate rejected");
                    Object wct = Class.forName("android.window.WindowContainerTransaction").getDeclaredConstructor().newInstance();
                    XposedHelpers.callMethod(wct, "setWindowingMode", task.token, 6);
                    XposedHelpers.callMethod(wct, "setBounds", task.token, h.logicalBounds(slot));
                    h.applyTopConfiguration(wct, task.token, slot);
                    XposedHelpers.callMethod(wct, "setForceTranslucent", task.token, true);
                    XposedHelpers.callMethod(wct, "setAlwaysOnTop", task.token, true);
                    XposedHelpers.callMethod(wct, "setFocusable", task.token, true);
                    XposedHelpers.callMethod(wct, "setHidden", task.token, false);
                    if (collectFromMain) XposedHelpers.callMethod(wct, "reorder", h.homeForCollection(task), true);
                    XposedHelpers.callMethod(wct, "reorder", task.token, true);
                    // Existing listener is retired by the organizer, never globally replaced.
                    claimed = true;
                    XposedHelpers.callMethod(organizer, "addListenerForTaskId", controller, task.id);
                    observation = h.observeNativeCommit(task.token,
                            () -> h.checkAdoption(slot, task, epoch, 0, success, failure));
                    XposedHelpers.callMethod(h.transitions, "startRootTask", controller, info, canonical, wct);
                    MiStepHost.log("adopt existing task=" + task.id + " into slot=" + slot.index + " logical=" + h.logicalBounds(slot));
                }
            } catch (Throwable error) {
                h.cancelNativeObservation(observation);
                final boolean changed = claimed;
                h.main.post(() -> adoptionFailed(slot, task, changed, "adopt: " + error, failure));
            }
        })) adoptionFailed(slot, task, false, "no Shell queue", failure);
    }

    /** A failed claim is handed back to the same native owner, never removed/restarted. */
    void adoptionFailed(MiStepHost.Slot slot, MiStepHost.LiveTask task, boolean claimed, String error, Consumer<String> failure) {
        h.transferBusy = false;
        slot.launching = false;
        if (!claimed) {
            slot.promoting = slot.frozen;
            if (slot.occupied && slot.taskId == task.id && task.token.equals(slot.taskToken))
                slot.parked = true;
            h.updateHint(slot);
            failure.accept(error);
            return;
        }
        h.bindTask(slot, task.id, task.token);
        slot.pkg = task.pkg;
        slot.occupied = true;
        returnToSystem(slot, false, () -> {
            h.clearSlot(slot);
            failure.accept(error + "; handed back alive");
        }, rollback -> {
            // Do not clear a still-owned controller or let a later selection overwrite it.
            slot.frozen = true;
            slot.promoting = true;
            MiStepHost.log("adoption rollback incomplete; frozen live task=" + task.id + ": " + rollback);
            failure.accept(error + "; binding frozen");
        });
    }

    void returnToSystem(MiStepHost.Slot slot, boolean front, Runnable success, Consumer<String> failure) {
        returnToSystem(slot,front,false,success,failure);
    }

    void returnToSystem(MiStepHost.Slot slot, boolean front, boolean liveMainReveal,
            Runnable success, Consumer<String> failure) {
        MiStepHost.Promotion ticket = new MiStepHost.Promotion(slot, new MiStepHost.Slot[0]);
        ticket.background = !front;
        ticket.liveMainReveal = liveMainReveal;
        if(liveMainReveal && h.topCtrl.topTransition!=null) {
            h.topCtrl.topTransition.setNativeRestore(()->{
                ticket.liveMainReveal=false;
                h.promotionController.restoreTopMainOpacity(ticket);
            });
        }
        ticket.sceneEpoch = h.sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        slot.promoting = true;
        h.transferBusy = true;
        h.recycleBuffered(slot);
        if (!h.executeOnShell(() -> {
            MiStepHost.NativeObservation observation = null;
            try {
                if (!returnTicketCurrent(ticket)) throw new IllegalStateException("stale return ticket");
                MiStepHost.LiveTask identity = new MiStepHost.LiveTask(ticket.taskId, ticket.token, slot.pkg);
                Object core = h.rootInfo(identity);
                if (core == null) {
                    if (!absentFromShellAndController(slot, identity))
                        throw new IllegalStateException("task missing from roots but still alive in Shell/controller");
                    h.main.post(() -> {
                        if (!returnTicketCurrent(ticket)) return;
                        h.transferBusy = false; h.clearSlot(slot); success.run();
                    });
                    return;
                }
                Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
                if (current == null && h.ordinaryRoot(core, 1)) {
                    h.main.post(() -> checkReturned(slot, ticket, 0, success, failure));
                    return;
                }
                if (!ticket.token.equals(current))
                    throw new IllegalStateException("return controller identity changed");
                observation = h.observeNativeCommit(ticket.token, () -> checkReturned(slot, ticket, 0, success, failure));
                if (!h.applyWct("return-live-task", h.promotionController.fullscreenExitTransaction(ticket)))
                    throw new IllegalStateException("return transaction failed");
            } catch (Throwable error) {
                h.cancelNativeObservation(observation);
                h.main.post(() -> {
                    h.transferBusy = false;
                    slot.promoting = slot.frozen;
                    failure.accept(error.toString());
                });
            }
         })) { h.transferBusy = false; slot.promoting = slot.frozen; failure.accept("no Shell queue"); }
    }

    boolean returnTicketCurrent(MiStepHost.Promotion ticket) {
        MiStepHost.Slot slot = ticket.slot;
        return h.isActive(slot) && h.sceneEpoch == ticket.sceneEpoch && slot.taskEpoch == ticket.bindingEpoch
                && slot.taskId == ticket.taskId && ticket.token.equals(slot.taskToken);
    }

    /** Root enumeration alone does not prove death: child/moved tasks remain in Shell. */
    boolean absentFromShellAndController(MiStepHost.Slot slot, MiStepHost.LiveTask task) {
        Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
        synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
            Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTasks")).get(task.id);
            if (appeared != null && task.matches(XposedHelpers.callMethod(appeared, "getTaskInfo"))) return false;
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            Object pending = XposedHelpers.getObjectField(slot.controller, "mPendingInfo");
            return !task.token.equals(current) && (pending == null || !task.matches(pending));
        }
    }

    void checkReturned(MiStepHost.Slot slot, MiStepHost.Promotion ticket, int attempt, Runnable success, Consumer<String> failure) {
        if (!returnTicketCurrent(ticket)) {
            h.transferBusy = false;
            failure.accept("stale return ticket; no new binding touched");
            return;
        }
        if (!h.executeOnShell(() -> {
            boolean ready = false, gone = false;
            String detail;
            try {
                if (!returnTicketCurrent(ticket)) throw new IllegalStateException("stale return on Shell queue");
                MiStepHost.LiveTask identity = new MiStepHost.LiveTask(ticket.taskId, ticket.token, slot.pkg);
                Object core = h.rootInfo(identity);
                if (core == null) {
                    gone = absentFromShellAndController(slot, identity);
                    ready = gone;
                } else if (h.ordinaryRoot(core, 1)) {
                    h.promotionController.handoffPromotedTask(ticket);
                    ready = ticket.handoffComplete
                            && XposedHelpers.getObjectField(slot.controller, "mTaskToken") == null;
                }
                detail = "native handback=" + ready + " gone=" + gone;
            } catch (Throwable error) { detail = error.toString(); }
            final boolean done = ready, removed = gone;
            final String result = detail;
            h.main.post(() -> {
                if (!returnTicketCurrent(ticket)) {
                    h.transferBusy = false;
                    failure.accept("stale return completion; no new binding touched");
                    return;
                }
                if (done) {
                    h.ownedTasks.remove(ticket.token);
                    if (removed) h.clearSlot(slot);
                    else {
                        slot.promoting = false;
                        slot.frozen = false;
                        slot.parked = true;
                        slot.taskVisible = false;
                        slot.scaleSurface = null;
                        slot.scaleTaskId = Integer.MIN_VALUE;
                        slot.scaleApplied = -1f;
                        h.updateHint(slot);
                    }
                    h.transferBusy = false;
                    h.removeCellBackground(slot);
                    MiStepHost.log("returned task id=" + ticket.taskId + " gone=" + removed + " front=" + !ticket.background);
                    success.run();
                } else if (attempt < 2 && !ticket.handoffAttempted) {
                    h.main.postDelayed(() -> checkReturned(slot, ticket, attempt + 1, success, failure), 500L);
                } else {
                    h.transferBusy = false;
                    slot.frozen |= ticket.handoffAttempted;
                    slot.promoting = slot.frozen;
                    failure.accept(result);
                }
            });
        })) { h.transferBusy = false; failure.accept("no Shell queue for handback"); }
    }

    void promoteExternalLaunch(MiStepHost.Slot slot) {
        if (slot == null || slot.index <= MiStepHost.SLOT_MAIN || !h.isActive(slot)) return;
        MiStepHost.log("external launcher launch detected slot=" + slot.index
                + " task=" + slot.taskId + " mode=" + slot.windowMode + "; promote");
        queueSidePromotion(slot, false);
    }

    /**
     * Some system apps reject WINDOWING_MODE_MULTI_WINDOW and remain fullscreen.
     * Keep their task alive by opening it in the h.main pane instead of forcing a mode
     * change, which makes the framework destroy the task.
     */
    void promoteUnsupportedSide(MiStepHost.Slot slot) {
        if (slot == null || slot.index <= MiStepHost.SLOT_MAIN || !h.isActive(slot)
                || slot.promoting || h.promotion != null || h.transferBusy) return;
        String pkg = slot.pkg == null ? "" : slot.pkg;
        slot.modeAdmissionPending = false;
        MiStepHost.log("side mode unsupported slot=" + slot.index + " task=" + slot.taskId
                + " mode=" + slot.windowMode + " pkg=" + pkg + "; open in main");
        android.widget.Toast.makeText(h.ctx, "该应用不支持副格，已在主区打开",
                android.widget.Toast.LENGTH_SHORT).show();
        slot.modeGuardUntil = 0L;
        h.onSlotTapped(slot.index);
    }

    void scheduleSideModeResolution(MiStepHost.Slot slot, int taskId) {
        long delay = Math.max(220L,
                slot.modeGuardUntil - SystemClock.uptimeMillis() + 150L);
        h.main.postDelayed(() -> {
            if (!h.isActive(slot) || slot.taskId != taskId || slot.promoting
                    || slot.launching || slot.parked || !slot.occupied) return;
            if (slot.windowMode == 6) {
                slot.modeAdmissionPending = false;
                slot.modeRepairAttempts = 0;
                h.reapplyScale(slot);
            } else if (slot.windowMode == 1 && slot.modeAdmissionPending) {
                repairSideWindowMode(slot, taskId);
            }
        }, delay);
    }

    void repairSideWindowMode(MiStepHost.Slot slot, int taskId) {
        if (!h.isActive(slot) || slot.taskId != taskId || slot.promoting
                || slot.launching || slot.parked || !slot.modeAdmissionPending) return;
        long now = SystemClock.uptimeMillis();
        if (now < slot.modeGuardUntil) return;
        if (slot.modeRepairAttempts >= 2) {
            promoteUnsupportedSide(slot);
            return;
        }
        slot.modeRepairAttempts++;
        slot.modeGuardUntil = now + 850L;
        try {
            Object wct = Class.forName("android.window.WindowContainerTransaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(wct, "setWindowingMode", slot.taskToken, 6);
            XposedHelpers.callMethod(wct, "setBounds", slot.taskToken, h.logicalBounds());
            XposedHelpers.callMethod(wct, "setForceTranslucent", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setAlwaysOnTop", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setFocusable", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setHidden", slot.taskToken, false);
            XposedHelpers.callMethod(wct, "reorder", slot.taskToken, true);
            boolean applied = h.applyWct("initial-side-mode-repair-" + slot.modeRepairAttempts, wct);
            MiStepHost.log("initial side mode repair slot=" + slot.index + " task=" + taskId
                    + " attempt=" + slot.modeRepairAttempts + " applied=" + applied);
        } catch (Throwable error) {
            MiStepHost.log("initial side mode repair failed slot=" + slot.index + ": " + error);
        }
        h.main.postDelayed(() -> scheduleSideModeResolution(slot, taskId), 900L);
    }

    void releaseNativeFreeformSide(MiStepHost.Slot slot, int taskId, Object token) {
        if (slot.nativeReleasePending) return;
        final long binding = slot.taskEpoch;
        final Object controller = slot.controller;
        slot.nativeReleasePending = true;
        slot.frozen = true;
        slot.windowMode = 5;
        slot.pendingSidePromotion = null;
        h.recycleBuffered(slot);
        if (!h.executeOnShell(() -> {
            boolean released = false;
            try {
                if (slot.taskEpoch == binding && slot.taskId == taskId && token.equals(slot.taskToken))
                    released = MiStepNativeSideRelease.release(controller, h.transitions, taskId, token);
            } catch (Throwable error) { MiStepHost.log("native side release task=" + taskId + " failed: " + error); }
            final boolean done = released;
            h.postForSlot(slot, owner -> {
                if (owner.taskEpoch != binding || owner.taskId != taskId
                        || !token.equals(owner.taskToken) || owner.controller != controller) return;
                owner.nativeReleasePending = false;
                owner.frozen = false;
                if (done) {
                    h.ownedTasks.remove(token);
                    h.clearSlot(owner);
                    h.compactCells();
                    MiStepHost.log("native freeform released side task=" + taskId + "; slot cleared, task retained");
                }
            });
        })) {
            slot.nativeReleasePending = false;
            slot.frozen = false;
        }
    }

    /** Keep the original notification dispatch until this exact side owner is released. */
    static boolean promoteForNotification(String pkg, int userId, Runnable dispatch) {
        MiStepHost h = MiStepHost.current();
        if (h == null || dispatch == null || pkg == null || Looper.myLooper() != h.main.getLooper()
                || h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked
                || !h.supportsTaskUser(userId) || h.promotion != null || h.transferBusy) return false;
        MiStepHost.Slot match = null;
        for (MiStepHost.Slot slot : h.slots) {
            if (slot.index <= MiStepHost.SLOT_MAIN || slot.index >= MiStepHost.CELL_SLOT_COUNT || !slot.occupied || slot.parked || slot.frozen
                    || slot.carrier || slot.windowMode != 6 || !pkg.equals(slot.pkg)
                    || !h.mayWriteSideSurface(slot)
                    || !h.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            try {
                Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
                if (XposedHelpers.getIntField(info, "taskId") != slot.taskId
                        || XposedHelpers.getIntField(info, "userId") != userId) continue;
            } catch (Throwable stale) { continue; }
            if (match != null) return false;
            match = slot;
        }
        if (match == null) return false;
        h.swapWithMain(match.index);
        MiStepHost.Promotion ticket = h.promotion;
        if (ticket == null || ticket.cancelled || ticket.slot != match) return false;
        ticket.notificationLaunch = dispatch;
        MiStepHost.log("notification waits for same-task promotion task=" + ticket.taskId + " package=" + pkg);
        return true;
    }

    void resumeNotificationLaunch(MiStepHost.Promotion ticket) {
        Runnable dispatch = ticket.notificationLaunch;
        ticket.notificationLaunch = null;
        if (dispatch != null) {
            MiStepHost.log("notification resumes original dispatch task=" + ticket.taskId);
            h.main.post(dispatch);
        }
    }

    void queueSidePromotion(MiStepHost.Slot slot, boolean desktopLaunch) {
        if (slot == null || slot.index <= MiStepHost.SLOT_MAIN || !h.isActive(slot)
                || !slot.occupied || slot.taskId < 0 || slot.taskToken == null
                || slot.parked || slot.frozen || slot.launching) return;
        MiStepHost.PendingSidePromotion pending = slot.pendingSidePromotion;
        if (pending != null && pending.taskId == slot.taskId
                && pending.token.equals(slot.taskToken)
                && pending.bindingEpoch == slot.taskEpoch
                && pending.sceneEpoch == h.sceneEpoch) {
            pending.desktopLaunch |= desktopLaunch;
            return;
        }
        pending = new MiStepHost.PendingSidePromotion(slot, h.sceneEpoch, desktopLaunch,
                SystemClock.uptimeMillis() + MiStepHost.SIDE_PROMOTION_TIMEOUT_MS);
        slot.pendingSidePromotion = pending;
        retrySidePromotion(pending);
    }

    void retrySidePromotion(MiStepHost.PendingSidePromotion request) {
        MiStepHost.Slot slot = request.slot;
        if (slot.pendingSidePromotion != request) return;
        boolean sameTask = h.scene == MiStepHost.ACTIVE && h.isActive(slot) && slot.occupied
                && !slot.parked && !slot.frozen && !slot.launching
                && SidePromotionPolicy.sameBinding(request.taskId, request.token,
                        request.sceneEpoch, request.bindingEpoch, request.pkg,
                        slot.taskId, slot.taskToken, h.sceneEpoch, slot.taskEpoch, slot.pkg);
        if (!sameTask) {
            slot.pendingSidePromotion = null;
            MiStepHost.log("pending side promotion cancelled: task binding changed slot=" + slot.index);
            return;
        }
        if (h.promotion != null && h.promotion.slot == slot
                && h.promotion.taskId == request.taskId && request.token.equals(h.promotion.token)) {
            slot.pendingSidePromotion = null;
            return;
        }
        if (slot.modeAdmissionPending && !request.desktopLaunch) {
            slot.pendingSidePromotion = null;
            return;
        }
        long now = SystemClock.uptimeMillis();
        long wait = SidePromotionPolicy.retryDelay(now, request.deadline,
                slot.modeGuardUntil, h.promotion != null || h.transferBusy || slot.promoting
                        || slot.windowMode != 1);
        if (wait < 0L || request.attempts >= 40) {
            slot.pendingSidePromotion = null;
            MiStepHost.log("pending side promotion expired slot=" + slot.index + " task=" + request.taskId
                    + " mode=" + slot.windowMode + " attempts=" + request.attempts);
            if (slot.windowMode == 6) h.reapplyScale(slot);
            return;
        }
        if (wait == 0L) {
            slot.pendingSidePromotion = null;
            MiStepHost.log("pending side promotion execute slot=" + slot.index + " task=" + request.taskId
                    + " desktop=" + request.desktopLaunch + " mode=" + slot.windowMode);
            // The system has already made this task fullscreen. Submitting another
            // fullscreen TaskView pending here repeats prepareOpen/updateBounds and
            // suppresses the original Launcher transition. Observe that transition.
            beginExternalPromotion(request);
            return;
        }
        request.attempts++;
        h.main.postDelayed(() -> retrySidePromotion(request), wait);
    }

    void beginExternalPromotion(MiStepHost.PendingSidePromotion request) {
        MiStepHost.Slot slot = request.slot;
        if (h.promotion != null || h.transferBusy || !h.isActive(slot)
                || !SidePromotionPolicy.sameBinding(request.taskId, request.token,
                        request.sceneEpoch, request.bindingEpoch, request.pkg,
                        slot.taskId, slot.taskToken, h.sceneEpoch, slot.taskEpoch, slot.pkg)
                || slot.windowMode != 1) return;
        MiStepHost.Promotion ticket = new MiStepHost.Promotion(slot, h.slots);
        ticket.sceneEpoch = h.sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        ticket.externalLaunch = true;
        ticket.previousMainOnRight = h.mainOnRight;
        ticket.targetMainOnRight = h.mainOnRight;
        h.promotion = ticket;
        slot.promoting = true;
        h.recycleBuffered(slot);
        MiStepClipboard.get(h.ctx).close();
        h.statusBar.hideFor(ticket, () -> {
            if (h.promotion == ticket && !ticket.cancelled)
                h.failPromotion(ticket, "external promotion status-bar lease expired");
        });
        if (!h.executeOnShell(() -> {
            try {
                if (!externalPromotionCurrent(ticket)) return;
                ticket.homeBeforeCover = h.existingHomeTask();
                h.traceSwap(ticket);
                h.observeNativeCommit(ticket.token, () -> afterExternalTransitionIdle(ticket));
                MiStepHost.log("observe external fullscreen task=" + ticket.taskId
                        + "; retain original launch, no second fullscreen pending");
            } catch (Throwable error) {
                h.main.post(() -> h.failPromotion(ticket, "external prepare: " + error));
            }
        })) h.failPromotion(ticket, "external promotion Shell executor unavailable");
    }

    boolean externalPromotionCurrent(MiStepHost.Promotion ticket) {
        return h.promotion == ticket && !ticket.cancelled && !ticket.cancelRequested && h.scene == MiStepHost.ACTIVE
                && h.screenOn && !h.keyguardLocked
                && h.sceneEpoch == ticket.sceneEpoch && h.isActive(ticket.slot)
                && ticket.slot.taskEpoch == ticket.bindingEpoch
                && ticket.slot.taskId == ticket.taskId
                && ticket.token.equals(ticket.slot.taskToken);
    }

    void afterExternalTransitionIdle(MiStepHost.Promotion ticket) {
        if (!externalPromotionCurrent(ticket)) return;
        if (!h.executeOnShell(() -> {
            try {
                if (!externalPromotionCurrent(ticket)) return;
                Object shellTransitions = XposedHelpers.getObjectField(h.transitions, "mTransitions");
                // The exact target ROM queues this until pending h.transitions and all
                // tracks are idle. Its finish observer runs before finishT.apply.
                XposedHelpers.callMethod(shellTransitions, "runOnIdle", (Runnable) () ->
                        h.main.post(() -> {
                            if (externalPromotionCurrent(ticket)) h.promotionController.checkPromotion(ticket, 0);
                        }));
            } catch (Throwable error) {
                h.main.post(() -> h.failPromotion(ticket, "external transition idle unavailable: " + error));
            }
        })) h.failPromotion(ticket, "external transition Shell queue unavailable");
    }

    void preannounceDesktopLaunch(android.os.Bundle binding) {
        if (binding == null || h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked
                || binding.getLong("scene", -1L) != h.sceneEpoch) return;
        int taskId = binding.getInt("taskId", -1);
        android.os.IBinder token = binding.getBinder("task");
        for (MiStepHost.Slot slot : h.slots) {
            if (slot.index <= 0 || !slot.occupied || slot.parked || slot.frozen
                    || slot.taskId != taskId || slot.taskToken == null
                    || slot.taskEpoch != binding.getLong("binding", -1L)
                    || !java.util.Objects.equals(slot.pkg, binding.getString("package"))) continue;
            try {
                if (!java.util.Objects.equals(token, XposedHelpers.callMethod(slot.taskToken, "asBinder"))) return;
                MiStepHost.log("desktop launch preannounced exact task=" + taskId + " slot=" + slot.index);
                if (binding.getBoolean("hostRoute", false)) {
                    if (SystemClock.uptimeMillis() > binding.getLong("deadline", 0L)) {
                        MiStepHost.log("desktop host route expired without changing task=" + taskId); return;
                    }
                    if (slot.windowMode != 6 || !h.mayWriteSideSurface(slot)) return;
                    if (h.transferBusy || h.promotion != null || slot.promoting || slot.launching) {
                        h.main.postDelayed(() -> preannounceDesktopLaunch(binding), 32L);
                    } else {
                        MiStepHost.log("desktop host route uses real promotion task=" + taskId);
                        h.onSlotTapped(slot.index);
                    }
                } else queueSidePromotion(slot, true);
            } catch (Throwable unavailable) { MiStepHost.log("desktop preannouncement rejected: " + unavailable); }
            return;
        }
    }

    boolean sideTaskMatchesUser(MiStepHost.Slot slot, int user) {
        if (user < 0 || slot == null || !slot.occupied || slot.parked || slot.frozen
                || slot.controller == null || slot.taskToken == null) return false;
        try {
            Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
            return h.controllerOwns(slot, slot.taskId, slot.taskToken)
                    && XposedHelpers.getIntField(info, "userId") == user;
        } catch (Throwable unavailable) { return false; }
    }
}
