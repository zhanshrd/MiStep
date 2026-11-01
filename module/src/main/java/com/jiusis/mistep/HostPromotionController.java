package com.jiusis.mistep;

import android.graphics.Rect;

import de.robv.android.xposed.XposedHelpers;

/**
 * 交换预览 / 提升确认与收尾（promotion finishing）子系统。
 * 自 MiStepHost 原样搬出；宿主内保留同名委托 shim。
 * 状态字段 promotion / swapPreview / swapPreviewStatusOwner 留在宿主（生命周期路径也在用），经 h.* 读写。
 */
final class HostPromotionController {
    private final MiStepHost h;

    HostPromotionController(MiStepHost host) {
        h = host;
    }

    private void enqueuePromotion(MiStepHost.Promotion ticket) {
        if (h.promotion != ticket || ticket.cancelled) return;
        if (!h.executeOnShell(() -> {
            MiStepHost.NativeObservation observation = null;
            try {
                if (ticket.cancelled) return;
                Object wct = fullscreenExitTransaction(ticket);
                h.traceSwapEvent("before-fullscreen-enqueue");
                // Our independent h.root is not a BubbleHelper child. Use the native queue with
                // the verified non-bubble transaction, without changing the global helper.
                // Keep the side matrix until the native mode change has settled. The actual
                // parent/matrix handoff is one atomic surface transaction in checkPromotion.
                observation = h.observeNativeCommit(ticket.token, () -> checkPromotion(ticket, 0));
                XposedHelpers.callMethod(h.transitions, "lambda$moveTaskViewToFullscreen$5",
                        wct, ticket.slot.controller);
            } catch (Throwable t) {
                h.cancelNativeObservation(observation);
                h.main.post(() -> failPromotion(ticket, "enqueue: " + t));
            }
        })) {
            failPromotion(ticket, "Shell executor unavailable");
        }
    }

    void closeSwapPreview() {
        closeSwapPreview(null);
    }

    private void closeSwapPreview(Object fallbackStatusOwner) {
        if (h.promotion != null) stopLiveLift(h.promotion);
        if (h.swapPreview == null) {
            h.statusBar.finish(fallbackStatusOwner);
            return;
        }
        Object statusOwner = h.swapPreviewStatusOwner;
        SwapPreview closing = h.swapPreview;
        h.swapPreview = null;
        h.swapPreviewStatusOwner = null;
        // removeView can defer teardown: restore only after the matching h.root detaches.
        closing.close(() -> {
            h.statusBar.finish(statusOwner);
            h.statusBar.finish(fallbackStatusOwner);
        });
        if (h.root != null) h.root.requestLayout();
    }

    private void stopLiveLift(MiStepHost.Promotion ticket) {
        if (ticket == null) return;
        if (ticket.liveLift != null) {
            if (h.swapPreview != null) h.swapPreview.seal();
            int[] current = ticket.slot.rect;
            Rect restore = current == null ? null
                    : new Rect(current[0], current[1], current[2], current[3]);
            ticket.liveLift.closeAndRestore(restore);
            ticket.liveLift = null;
        }
        if (ticket.slot.view != null) {
            ticket.slot.view.setElevation(0f);
            ticket.slot.view.requestLayout();
        }
    }

    void closeCellMovePreview() {
        if (h.cellMovePreview == null) return;
        h.cellMovePreview.close();
        h.cellMovePreview = null;
        if (h.root != null) h.root.requestLayout();
    }

    private static void releaseCompactSnapshot(MiStepHost.Promotion ticket) {
        if (ticket == null) return;
        if (ticket.compactSnapshot != null && !ticket.compactSnapshot.isRecycled()) {
            ticket.compactSnapshot.recycle();
        }
        ticket.compactSnapshot = null;
        ticket.compactSnapshotRect = null;
    }

    private void finishSwapPreview() {
        finishSwapPreview(0L);
    }

    private void finishSwapPreview(long settleMs) {
        finishSwapPreview(null, settleMs);
    }

    private void finishSwapPreview(Object statusOwner, long settleMs) {
        // Two host frames settle the replacement SurfaceView before revealing it.
        SwapPreview expected = h.swapPreview;
        Runnable reveal = () -> {
            // A no-preview completion also owns a lease. Old callbacks may never
            // restore a newer exchange, even when both captured a null bridge.
            if (h.swapPreview == expected) closeSwapPreview(statusOwner);
        };
        if (h.root != null) h.root.postOnAnimation(() -> h.root.postOnAnimation(() -> {
            if (settleMs <= 0L) reveal.run();
            else h.main.postDelayed(reveal, settleMs);
        }));
        else reveal.run();
    }

    Object fullscreenExitTransaction(MiStepHost.Promotion ticket) throws Exception {
        Object wct = Class.forName("android.window.WindowContainerTransaction")
                .getDeclaredConstructor().newInstance();
        Object token = ticket.token;
        // 显式 FULLSCREEN：UNDEFINED(0) 会从父容器继承——而父容器正是 always-on-top
        // 多窗口 RootTask，等于把任务留在原地（真机 BUG-1：QQ 被钉死在置顶栈）。
        XposedHelpers.callMethod(wct, "setWindowingMode", token, 1);
        XposedHelpers.callMethod(wct, "setInterceptBackPressedOnTaskRoot", token, false);
        XposedHelpers.callMethod(wct, "setTaskForceExcludedFromRecents", token, false);
        XposedHelpers.callMethod(wct, "setDisablePip", token, false);
        XposedHelpers.callMethod(wct, "setDisableLaunchAdjacent", token, false);
        XposedHelpers.callMethod(wct, "setAlwaysOnTop", token, false);
        XposedHelpers.callMethod(wct, "setFocusable", token, true);
        XposedHelpers.callMethod(wct, "setBounds", token, new Rect());
        if (ticket.slot.index == MiStepHost.SLOT_TOP) {
            XposedHelpers.callMethod(wct, "setAppBounds", token, (Object) null);
            XposedHelpers.callMethod(wct, "setScreenSizeDp", token, 0, 0);
            XposedHelpers.callMethod(wct, "setSmallestScreenWidthDp", token, 0);
            XposedHelpers.callMethod(wct, "setDensityDpi", token, 0);
        }
        // Keep the actual desktop/app below visible while the returning live surface is small.
        // Surface scaling alone does not prevent WM from occluding that underlying task.
        XposedHelpers.callMethod(wct, "setForceTranslucent", token, ticket.liveMainReveal);
        XposedHelpers.callMethod(wct, "setHidden", token, false);
        XposedHelpers.callMethod(wct, "setTaskTrimmableFromRecents", token, true);
        XposedHelpers.callMethod(wct, "reorder", token, !ticket.background);
        // Side roots must remain above the opaque h.main task in WM's visibility traversal.
        // Their surfaces stay inside their own TaskViews and do not cover the h.main area.
        for (Object sideToken : ticket.otherSideTokens) {
            XposedHelpers.callMethod(wct, "setForceTranslucent", sideToken, true);
            XposedHelpers.callMethod(wct, "setAlwaysOnTop", sideToken, true);
            XposedHelpers.callMethod(wct, "setFocusable", sideToken, true);
            XposedHelpers.callMethod(wct, "reorder", sideToken, true);
        }
        Object owner = XposedHelpers.getObjectField(ticket.slot.controller, "mCaptionInsetsOwner");
        if (owner != null) {
            XposedHelpers.callMethod(wct, "removeInsetsSource", token, owner, 0,
                    android.view.WindowInsets.Type.captionBar());
        }
        return wct;
    }

    private void resetTaskMatrix(Object leash) throws Exception {
        if (leash == null) return;
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) return;
        Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                .getDeclaredConstructor().newInstance();
        try {
            XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f);
            XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
            XposedHelpers.callMethod(tx, "setWindowCrop", leash, new Rect(0, 0, h.screenW, h.screenH));
            XposedHelpers.callMethod(tx, "apply");
        } finally {
            h.closeSurfaceObject(tx, "close");
        }
    }

    /** Retire only our binding, then return its canonical task surface to the existing DA. */
    void handoffPromotedTask(MiStepHost.Promotion ticket) throws Throwable {
        if (ticket.handoffAttempted || ticket.cancelled) return;
        Object controller = ticket.slot.controller;
        Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
        Object lock = XposedHelpers.getObjectField(organizer, "mLock");
        synchronized (lock) {
            Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                    organizer, "mTasks")).get(ticket.taskId);
            if (appeared == null) return;
            Object info = XposedHelpers.callMethod(appeared, "getTaskInfo");
            Object token = XposedHelpers.getObjectField(info, "token");
            Object current = XposedHelpers.getObjectField(controller, "mTaskToken");
            if (!ticket.token.equals(token)
                    || XposedHelpers.getIntField(info, "taskId") != ticket.taskId
                    || XposedHelpers.getIntField(info, "displayId") != 0
                    || !h.supportsTaskUser(XposedHelpers.getIntField(info, "userId"))
                    || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                    || (!ticket.background && !XposedHelpers.getBooleanField(info, "isVisible"))
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))) return;
            Object assigned = XposedHelpers.callMethod(organizer, "getTaskListener", info, false);
            Object fallback = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                    organizer, "mTaskListeners")).get(-2); // target fullscreen listener type
            TaskHandoffPolicy.Owner owner = TaskHandoffPolicy.owner(
                    ticket.token, current, assigned, controller, fallback);
            if (ticket.candidateRecovery && assigned == fallback && fallback != null
                    && fallback != controller && !ticket.token.equals(current))
                owner = TaskHandoffPolicy.Owner.NATIVE_FULLSCREEN;
            if (owner == TaskHandoffPolicy.Owner.UNAVAILABLE) {
                throw new IllegalStateException("native fullscreen route is unavailable");
            }
            Object area = XposedHelpers.getObjectField(organizer, "mRootTaskDisplayAreaOrganizer");
            Object areaInfo = XposedHelpers.callMethod(area, "getDisplayAreaInfo", 0);
            Object parent = XposedHelpers.callMethod(area, "getDisplayAreaLeash", 0);
            Object canonical = XposedHelpers.callMethod(appeared, "getLeash");
            Object copy = h.getSurfaceField(controller, "mTaskLeash");
            if (areaInfo == null || XposedHelpers.getIntField(areaInfo, "featureId") != 1
                    || XposedHelpers.getIntField(areaInfo, "displayId") != 0
                    || parent == null || canonical == null
                    || (owner == TaskHandoffPolicy.Owner.OUR_CONTROLLER
                            && (copy == null || canonical == copy))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(parent, "isValid"))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(canonical, "isValid"))) {
                throw new IllegalStateException("native parent/canonical surface is unavailable");
            }
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            boolean unregistered = false;
            try {
                // Prepare every surface operation before changing listener ownership.
                XposedHelpers.callMethod(area, "reparentToDisplayArea", 0, canonical, tx);
                boolean liveCenterReveal=ticket.liveMainReveal && h.topTransition!=null;
                ticket.liveMainReveal=liveCenterReveal;
                float initialScale=liveCenterReveal
                        ?TopTaskReturnPolicy.CENTER_START_SCALE:1f;
                float initialX=liveCenterReveal
                        ?TopTaskReturnPolicy.centeredOffset(h.screenW,initialScale):0f;
                float initialY=liveCenterReveal
                        ?TopTaskReturnPolicy.centeredOffset(h.screenH,initialScale):0f;
                XposedHelpers.callMethod(tx, "setMatrix", canonical,
                        initialScale, 0f, 0f, initialScale);
                XposedHelpers.callMethod(tx, "setPosition", canonical, initialX, initialY);
                XposedHelpers.callMethod(tx, "setWindowCrop", canonical,
                        new Rect(0, 0, h.screenW, h.screenH));
                XposedHelpers.callMethod(tx, "setAlpha", canonical, 1f);
                XposedHelpers.callMethod(tx, ticket.background ? "hide" : "show", canonical);
                ticket.handoffAttempted = true;
                if (owner == TaskHandoffPolicy.Owner.OUR_CONTROLLER) {
                    XposedHelpers.callMethod(h.transitions, "unregisterTaskView", controller);
                    unregistered = true;
                    XposedHelpers.callMethod(organizer, "removeListener", controller);
                    if (XposedHelpers.callMethod(organizer, "getTaskListener", info, false) != fallback) {
                        throw new IllegalStateException("native fullscreen listener did not take ownership");
                    }
                }
                XposedHelpers.callMethod(tx, "apply");
                if(liveCenterReveal) {
                    ticket.liveRevealSurface=canonical;
                    h.topTransition.setLiveSurface(canonical);
                }
                // Native removeListener hands off to fallback but does not notify the old owner.
                // This native callback retires/release its own copied handle, never canonical.
                if (owner == TaskHandoffPolicy.Owner.OUR_CONTROLLER) {
                    XposedHelpers.callMethod(controller, "onTaskVanished", info);
                    XposedHelpers.callMethod(h.transitions, "registerTaskView", controller);
                }
                unregistered = false;
                ticket.handoffComplete = true;
                h.traceSwapEvent("after-native-handoff");
                MiStepHost.log("promote native handoff task=" + ticket.taskId + " owner="
                        + fallback.getClass().getName() + " parent=default-task-display-area");
            } finally {
                h.closeSurfaceObject(tx, "close");
                // A completed native detach may leave an empty view if re-registration failed.
                // Keep it frozen for exit rather than restoring a task now owned by fullscreen.
                if (unregistered) MiStepHost.log("promote handoff interrupted; keep target frozen until exit");
            }
        }
    }

    /** At most three observations; notification or transaction submission alone is insufficient. */
    void checkPromotion(MiStepHost.Promotion ticket, int attempt) {
        if (h.promotion != ticket || ticket.cancelled || !h.isActive(ticket.slot)) return;
        if (!h.executeOnShell(() -> {
            boolean success = false;
            String detail;
            try {
                Object current = XposedHelpers.getObjectField(ticket.slot.controller, "mTaskToken");
                Object service = XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityTaskManager"), "getService");
                java.util.List<?> roots = (java.util.List<?>) XposedHelpers.callMethod(
                        service, "getAllRootTaskInfos");
                detail = "detached=" + (current == null);
                int visibleSides = 0;
                for (Object info : roots) {
                    Object rootToken = XposedHelpers.getObjectField(info, "token");
                    if (ticket.otherSideTokens.contains(rootToken)
                            && XposedHelpers.getBooleanField(info, "isVisible")) visibleSides++;
                }
                detail += " otherSidesVisible=" + visibleSides + "/" + ticket.otherSideTokens.size();
                for (Object info : roots) {
                    if (XposedHelpers.getIntField(info, "taskId") != ticket.taskId) continue;
                    Object config = XposedHelpers.getObjectField(info, "configuration");
                    Object winConfig = XposedHelpers.getObjectField(config, "windowConfiguration");
                    int mode = (Integer) XposedHelpers.callMethod(winConfig, "getWindowingMode");
                    boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
                    boolean ready = ticket.token.equals(XposedHelpers.getObjectField(info, "token"))
                            && mode == 1 && visible
                            && visibleSides == ticket.otherSideTokens.size();
                    if (ready && current != null && !ticket.handoffAttempted) {
                        handoffPromotedTask(ticket);
                        current = XposedHelpers.getObjectField(ticket.slot.controller, "mTaskToken");
                    }
                    success = current == null && mode == 1 && visible
                            && ready && (!ticket.handoffAttempted || ticket.handoffComplete);
                    detail += " mode=" + mode + " visible=" + visible
                            + " detachedNow=" + (current == null)
                            + " nativeHandoff=" + ticket.handoffComplete;
                    break;
                }
            } catch (Throwable t) {
                detail = "observe: " + t;
            }
            final boolean done = success;
            final String result = detail;
            h.main.post(() -> {
                if (h.promotion != ticket || ticket.cancelled || !h.isActive(ticket.slot)) return;
                MiStepHost.log("promote observation task=" + ticket.taskId + " attempt=" + attempt + " " + result);
                if (done) {
                    h.traceSwapEvent("promotion-mode1-confirmed");
                    stopLiveLift(ticket);
                    h.ownedTasks.remove(ticket.token);
                    h.clearSlot(ticket.slot);
                    if (ticket.previousMain != null) {
                        releaseCompactSnapshot(ticket);
                        h.adoptExisting(ticket.slot, ticket.previousMain, () -> finishPromotionFocus(ticket, () -> {
                            h.traceSwapEvent("backfill-mode6-and-focus-confirmed");
                            ticket.cancelled = true;
                            h.promotion = null;
                            h.resumeNotificationLaunch(ticket);
                            // Keep the final captured frame opaque across the task-surface
                            // handoff; closing immediately exposed one black host frame.
                            h.coveredHome = null;
                            finishSwapPreview(ticket, 120L);
                            MiStepHost.log("swap confirmed main=" + ticket.taskId + " side="
                                    + ticket.previousMain.id + " sameSlot=" + ticket.slot.index);
                            // 提升后的新主任务之前在副格里被设过 crop，必须清掉；否则主区顶部会残一条应用画面。
                            h.resetMainTaskLeash(ticket.taskId);
                            h.scheduleMainCropClear(400L);
                            h.finishPendingSuspend();
                        }, 0), error -> finishPromotionFocus(ticket, () -> {
                            ticket.cancelled = true;
                            h.promotion = null;
                            h.resumeNotificationLaunch(ticket);
                            closeSwapPreview(ticket);
                            MiStepHost.log("swap backfill failed; both system tasks retained, no relaunch: " + error);
                            h.finishPendingSuspend();
                        }, 0));
                    } else {
                        finishPromotionFocus(ticket, () -> completeHomePromotion(ticket), 0);
                    }
                } else if (attempt < 2) {
                    h.main.postDelayed(() -> checkPromotion(ticket, attempt + 1), 700L);
                } else {
                    failPromotion(ticket, result);
                }
            });
        })) failPromotion(ticket, "Shell executor unavailable during observation");
    }

    /** The promoted binding is already retired; move the remaining owners into its gap. */
    void completeHomePromotion(MiStepHost.Promotion ticket) {
        if (h.promotion != ticket || ticket.cancelled) return;
        h.traceSwapEvent("Home-cover-and-focus-confirmed");
        ticket.cancelled = true;
        h.promotion = null;
        h.resumeNotificationLaunch(ticket);
        h.coveredHome = new HomeTaskReturn(ticket.taskId, ticket.token,
                ticket.homeBeforeCover.id, ticket.homeBeforeCover.token);
        releaseCompactSnapshot(ticket);
        finishSwapPreview(ticket, 0L);
        // Home and h.main geometry remain anchored. Only side owner positions change;
        // their controllers, task tokens and window/input state travel together.
        h.compactCells();
        if (h.root != null) h.root.requestLayout();
        MiStepHost.log("home promotion confirmed task=" + ticket.taskId + "; side gap compacted");
        h.finishPendingSuspend();
    }

    /** Backfill can take focus again; make the focus request after that last WCT. */
    void finishPromotionFocus(MiStepHost.Promotion ticket, Runnable complete, int attempt) {
        if (h.promotion != ticket || ticket.cancelled) return;
        if (ticket.liveMainFocusPrepared) {
            // The live Shell callback already requested the exact h.main task.
            // Restore Host now; final-frame ownership still protects the handoff.
            complete.run();
            return;
        }
        final long epoch = h.sceneEpoch;
        final MiStepHost.LiveTask home = ticket.nativeCollect ? ticket.collectionHome : null;
        if (ticket.nativeCollect && home == null) { complete.run(); return; }
        final int focusId = home == null ? ticket.taskId : home.id;
        final Object focusToken = home == null ? ticket.token : home.token;
        final int focusUser = ticket.liveMainUserId >= 0 ? ticket.liveMainUserId : h.currentUserId();
        if (h.scene != MiStepHost.ACTIVE || h.suspendRequested || !h.screenOn) {
            complete.run();
            return;
        }
        if (!h.executeOnShell(() -> {
            PromotionFocusTransfer.Result result = PromotionFocusTransfer.Result.UNAVAILABLE;
            try {
                if (h.promotion == ticket && !ticket.cancelled && h.sceneEpoch == epoch
                        && h.scene == MiStepHost.ACTIVE && !h.suspendRequested && h.screenOn) {
                    Object service = XposedHelpers.callStaticMethod(
                            Class.forName("android.app.ActivityTaskManager"), "getService");
                    PromotionFocusTransfer.Tasks tasks = new PromotionFocusTransfer.Tasks() {
                        public PromotionFocusTransfer.State read(int id) {
                            java.util.List<?> roots = (java.util.List<?>) XposedHelpers.callMethod(
                                    service, "getAllRootTaskInfos");
                            for (Object info : roots) {
                                if (XposedHelpers.getIntField(info, "taskId") != id
                                        || (home == null ? !h.ordinaryRoot(info, 1)
                                                : !h.isHomeRoot(info) || h.windowModeOf(info) != 1)) continue;
                                return new PromotionFocusTransfer.State(
                                        XposedHelpers.getObjectField(info, "token"),
                                        XposedHelpers.getIntField(info, "displayId"),
                                        XposedHelpers.getIntField(info, "userId"), h.windowModeOf(info),
                                        h.mainTaskVisible(info),
                                        XposedHelpers.getBooleanField(info, "isFocused"));
                            }
                            return null;
                        }
                        public void focus(int id) {
                            XposedHelpers.callMethod(service, "setFocusedTask", id);
                        }
                    };
                    PromotionFocusTransfer.State initial = tasks.read(focusId);
                    if (initial != null) {
                        // Collection already revealed Home in its WCT. Never refocus it here:
                        // a new h.main app or native freeform may have taken focus during finish.
                        result = attempt == 0 && home == null
                                ? PromotionFocusTransfer.transfer(tasks, focusId,
                                        focusToken, focusUser)
                                : PromotionFocusTransfer.observe(tasks, focusId,
                                        focusToken, focusUser);
                    }
                }
            } catch (Throwable error) {
                MiStepHost.log("promotion focus request failed task=" + focusId + ": " + error);
            }
            final PromotionFocusTransfer.Result observed = result;
            h.main.post(() -> {
                if (h.promotion != ticket || ticket.cancelled) return;
                MiStepHost.log("promotion focus task=" + focusId + " attempt=" + attempt
                        + " result=" + observed + " afterBackfill=true");
                if (observed == PromotionFocusTransfer.Result.PENDING && attempt == 0
                        && h.sceneEpoch == epoch && h.scene == MiStepHost.ACTIVE && !h.suspendRequested && h.screenOn) {
                    h.main.postDelayed(() -> finishPromotionFocus(ticket, complete, 1), 120L);
                } else complete.run();
            });
        })) {
            MiStepHost.log("promotion focus unavailable: no Shell queue task=" + focusId);
            complete.run();
        }
    }

    void failPromotion(MiStepHost.Promotion ticket, String reason) {
        h.traceSwapEvent("promotion-failure");
        if (h.promotion != ticket || ticket.cancelled) return;
        if (ticket.nativeSwap) {
            if (requestLiveSwapCancellation(ticket, reason)) return;
            // 兜底（看门狗 20s 强制的实际执行体）：取消路径已证明失效（cancelRequested/
            // nativeCompleting 闩死）。不做任何 WM 写入（native 事务结局未知），按未确认
            // 惯例冻结格子并释放全局闩，避免 promotion/input-blocker 永久卡死。
            // keepCellUnfrozen = nativeCompleting：完成窗口内（角色已落定、槽位已重绑）
            // 到达的 failPromotion 不得冻结已确认的新绑定；未完成的楔死（cancelRequested
            // 闩死、nativeCompleting 仍 false）维持未知结局冻结惯例。
            h.swapController.forceAbandonLivePromotion(ticket, "cancellation inert: " + reason, ticket.nativeCompleting);
            return;
        }
        ticket.cancelled = true;
        h.promotion = null;
        releaseCompactSnapshot(ticket);
        if (ticket.liveBackdrop != null) { ticket.liveBackdrop.close(); ticket.liveBackdrop = null; }
        stopLiveLift(ticket);
        // 桥没有把主任务交给系统就失败时，必须把 view 级动画的变换还原，避免主窗留在格子里。
        if (ticket.mainLeash != null && !ticket.handoffAttempted) {
            try {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(ticket.mainLeash, "isValid"))) {
                    h.applyLeashTransform(ticket.mainLeash, 0f, 0f, 1f, 1f);
                    MiStepHost.log("live main shrink restored after failure task=" + ticket.taskId);
                }
            } catch (Throwable error) {
                MiStepHost.log("live main shrink restore failed: " + error);
            }
        }
        ticket.mainLeash = null;
        if (!ticket.handoffAttempted && ticket.targetMainOnRight != ticket.previousMainOnRight
                && h.mainOnRight != ticket.previousMainOnRight) {
            h.setMainSide(ticket.previousMainOnRight);
        }
        closeSwapPreview(ticket);
        MiStepHost.Slot slot = ticket.slot;
        h.resumeNotificationLaunch(ticket);
        MiStepHost.log("promote unconfirmed task=" + ticket.taskId + "; retain all slots: " + reason);
        if (ticket.externalLaunch) {
            // The native Launcher launch must never be undone by a mode6 rollback.
            // Retain the exact binding for the ordinary exit recovery path.
            slot.frozen = true;
            slot.promoting = true;
            MiStepHost.log("external fullscreen handoff unconfirmed; retain binding for exit, no mode6 rollback");
            h.main.post(h::finishPendingSuspend);
            return;
        }
        if (ticket.handoffAttempted) {
            slot.frozen = true;
            MiStepHost.log("promote native handoff was attempted; freeze until exit, without reclaiming task");
            h.main.post(h::finishPendingSuspend);
            return;
        }
        // Only restore a task still owned by this controller. Never remove/relaunch other cells.
        try {
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            if (ticket.token.equals(current) && h.isActive(slot)) {
                Object rollback = Class.forName("android.window.WindowContainerTransaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(rollback, "setWindowingMode", ticket.token, 6);
                XposedHelpers.callMethod(rollback, "setBounds", ticket.token, h.logicalBounds(slot));
                h.applyTopConfiguration(rollback, ticket.token, slot);
                XposedHelpers.callMethod(rollback, "setForceTranslucent", ticket.token, true);
                XposedHelpers.callMethod(rollback, "setAlwaysOnTop", ticket.token, true);
                XposedHelpers.callMethod(rollback, "setFocusable", ticket.token, true);
                h.applyWct("swap-rollback-same-task", rollback);
                slot.promoting = false;
                slot.scaleSurface = null;
                slot.scaleTaskId = Integer.MIN_VALUE;
                slot.scaleApplied = -1f;
                h.applyFullscreenBounds(slot);
                h.applySlotScale(slot);
            } else {
                MiStepHost.log("promote task already detached; freeze cell until exit, without relaunching task");
            }
        } catch (Throwable t) {
            MiStepHost.log("promote restore side failed: " + t);
        }
        h.main.post(h::finishPendingSuspend);
    }

    boolean requestLiveSwapCancellation(MiStepHost.Promotion ticket, String reason) {
        if (h.promotion != ticket || ticket.cancelled || ticket.cancelRequested || ticket.nativeCompleting)
            return false;
        // WCT may already be applied while Shell still waits for onTransitionReady.
        // Keep this binding/ticket until the facade's unique recovery completion.
        ticket.cancelRequested = true;
        MiStepHost.log("native swap cancellation requested; retain owner until recovery: " + reason);
        if (!h.executeOnShell(() -> {
            if (h.promotion != ticket || ticket.cancelled || ticket.nativeCompleting) return;
            if (ticket.nativeSubmitted) MiStepLiveSwap.cancel(ticket.slot.controller);
            else h.main.post(() -> {
                MiStepLiveSwap.Result result = new MiStepLiveSwap.Result(false, ticket.nativeHome,
                        new IllegalStateException("native swap cancelled before submission: " + reason));
                if (ticket.nativeCollect) h.finishLiveCollect(ticket, result);
                else if (ticket.nativeHome) h.finishLiveHome(ticket, result);
                else h.finishLiveSwap(ticket, result);
            });
        })) {
            // cancel() serializes itself on the original Shell executor.
            if (ticket.nativeSubmitted) MiStepLiveSwap.cancel(ticket.slot.controller);
            else {
                MiStepLiveSwap.Result result = new MiStepLiveSwap.Result(false, ticket.nativeHome,
                        new IllegalStateException("native cancellation Shell queue unavailable"));
                if (ticket.nativeCollect) h.finishLiveCollect(ticket, result);
                else if (ticket.nativeHome) h.finishLiveHome(ticket, result);
                else h.finishLiveSwap(ticket, result);
            }
        }
        return true;
    }

    /** A wedged live reveal must never leave the desktop translucent behind the returned app. */
    void restoreTopMainOpacity(MiStepHost.Promotion ticket) {
        if(!h.executeOnShell(()->{
            try {
                Object info=h.rootInfo(new MiStepHost.LiveTask(ticket.taskId,ticket.token,ticket.slot.pkg));
                // A late completion must not change a newly adopted freeform task.
                if(!h.ordinaryRoot(info,1))return;
                Object wct=Class.forName("android.window.WindowContainerTransaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(wct,"setForceTranslucent",ticket.token,false);
                if(!h.applyWct("upper-live-main-opacity-restore",wct))
                    MiStepHost.log("upper live main opacity restore not committed task="+ticket.taskId);
            } catch(Throwable error) {MiStepHost.log("upper live main opacity restore: "+error);}
        }))MiStepHost.log("upper live main opacity restore unavailable: no Shell queue");
    }

    void bindTask(MiStepHost.Slot slot, int taskId, Object token) {
        if (slot.taskId != taskId || !java.util.Objects.equals(slot.taskToken, token)) {
            slot.pendingSidePromotion = null;
            slot.taskEpoch++;
            slot.removalCheckTask = -1;
            slot.visibilityRepair.reset();
            slot.scaleSurface = null;
            slot.scaleOnLeash = false;
            slot.scaleTaskId = Integer.MIN_VALUE;
            slot.scaleApplied = -1f;
            slot.scaleAttempts = 0;
            slot.taskUid = 0;
            slot.reassertPending = false;
            slot.windowMode = -1;
            slot.modeRepairAttempts = 0;
            h.recycleBuffered(slot);
        }
        slot.taskId = taskId;
        slot.taskToken = token;
        synchronized (h.taskWindowStatesLock) {
            slot.windowState = h.taskWindowStates.get(taskId);
            if (slot.windowState == null) {
                slot.windowState = new TaskWindowState(taskId);
                h.taskWindowStates.put(taskId, slot.windowState);
            }
        }
        slot.windowState.slotIndex = slot.index;
        MiStepHost.OwnedTask retained = h.ownedTasks.computeIfAbsent(token, ignored -> new MiStepHost.OwnedTask(taskId, token));
        retained.recoveryController = slot.controller;
    }
}
