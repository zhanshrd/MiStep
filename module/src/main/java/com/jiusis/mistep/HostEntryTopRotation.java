package com.jiusis.mistep;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;

import de.robv.android.xposed.XposedHelpers;

/**
 * 手机竖屏顶部小窗的本地视口旋转子系统（「无限小窗」配套，上游 v1.0.4 语义移植）。
 *
 * 上游把这一簇（约 580 行 + 6 个状态字段）直接写在 HostEntry 里；fork 的 HostEntry 是纯 shim 壳，
 * 旋转族已在 HostEntryRotation，所以整簇落为独立静态控制器。代码逐方法搬自上游 1.0.4 HostEntry
 * （掩码保护 + 成员表改写：宿主静态经 HostEntry.*，旋转族经 HostEntryRotation.INSTANCE/静态），
 * 未改语义。全成员 static（无实例状态）。
 *
 * 模型：fork 自己持有的固定旋转租约（sTaskRotations + HostEntry.sTaskRotationTransforms 弱引用）只属于
 * 当前页；同任务共享同一 mFixedRotationTransformState 的相邻页可"领养"租约（adoptShared）
 * 而不触发重建。本地旋转动画（MiStepRotationAnimation）是可选加速，任何失败都不能影响
 * 配置/输入/原生清理。TOP 页关闭中（closingTopRotations）在过渡结束后统一对账。
 */
final class HostEntryTopRotation {
    private HostEntryTopRotation() {
    }

    static boolean retainTaskRotationOnNativeFinishLocked(Object activity) {
        if (!HostEntry.sFullscreenRotationActive || HostEntry.sUpdatingTaskRotation || activity == null) return false;
        // This hook belongs to WindowToken, including IME/Toast tokens with no task.
        // WindowContainer returns null; only ActivityRecord returns itself.
        if (XposedHelpers.callMethod(activity, "asActivityRecord") == null) return false;
        // Native linkFixedRotationTransform shares one state with the previous page.
        // Even a finishing old page must not reset the current page's entire association.
        if (retainSharedTopRotationLocked(activity)) return true;
        if (!HostEntry.sTaskRotations.containsKey(activity)) return false;
        try {
            java.lang.ref.WeakReference<Object> lease = HostEntry.sTaskRotationTransforms.get(activity);
            Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            if (lease == null || state == null || lease.get() != state
                    || XposedHelpers.getObjectField(activity, "mDisplayContent") != HostEntry.sDisplayContent
                    || Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "mRemovingFromDisplay"))
                    || XposedHelpers.callMethod(activity, "getParent") == null) return false;
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "finishing")))
                return retainClosingTopRotationLocked(activity, task);
            if (isLiveTopPageLocked(activity, task))
                return Integer.valueOf(resolveTopRotationLocked(HostEntry.sDisplayContent,
                        XposedHelpers.callMethod(task, "topRunningActivity")))
                        .equals(HostEntry.sTaskRotations.get(activity));
            if (task == null || XposedHelpers.callMethod(task, "topRunningActivity") != activity
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "isActivityTypeHome")))
                return false;
            if (HostEntryRotation.isUpperLandscapeTask(task))
                return HostEntry.sZoomed && MiStepTopRotation.phone(HostEntry.sDisplayContent)
                        && Integer.valueOf(resolveTopRotationLocked(HostEntry.sDisplayContent, activity))
                                .equals(HostEntry.sTaskRotations.get(activity));
            int mode = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            boolean side = HostEntryRotation.INSTANCE.isSideFixedTask(activity);
            if ((mode != 1 && !side) || NativeLandscapePolicy.suppressesLocalRotation(
                    HostEntry.sNativeLandscapeState, side, false)) return false;
            if (!side && (activity != HostEntry.sMiStepFixedRotationApp || !HostEntry.sZoomed
                    || XposedHelpers.callMethod(HostEntry.sDisplayContent, "topRunningActivity") != activity
                    && !MiStepDesktopLaunchBridge.rotationMain(HostEntry.sDisplayContent, activity)))
                return false;
            HostEntry.RotationResolution desired = HostEntryRotation.INSTANCE.resolveTaskRotationLocked(
                    HostEntry.sDisplayContent, activity, task, mode, "native-transform-finish");
            return desired.taskRotation != HostEntry.sHeldDisplayRotation
                    && Integer.valueOf(desired.taskRotation).equals(HostEntry.sTaskRotations.get(activity));
        } catch (Throwable error) {
            HostEntry.log("task rotation lifetime check unavailable: " + error);
            return false;
        }
    }

    static final java.util.Set<Object> closingTopRotations =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    static final java.util.Set<Object> topRotationHandoffs =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    static boolean retainClosingTopRotationLocked(Object activity, Object task) {
        if (!HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive || !sTopRotationBounds.containsKey(activity)
                || !HostEntryRotation.isUpperLandscapeTask(task) || !MiStepTopRotation.phone(HostEntry.sDisplayContent)
                || !ownsTaskRotationTransform(activity)
                || XposedHelpers.getObjectField(activity, "mDisplayContent") != HostEntry.sDisplayContent
                || !Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "finishing"))
                || Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "mRemovingFromDisplay"))
                || XposedHelpers.callMethod(activity, "getParent") == null
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "inTransition"))) return false;
        Object surface = XposedHelpers.callMethod(activity, "getSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(
                XposedHelpers.callMethod(surface, "isValid"))) return false;
        closingTopRotations.add(activity);
        return true;
    }

    static void reconcileTopRotationTransitionsLocked(Object display) {
        if (display != HostEntry.sDisplayContent) return;
        if (!HostEntry.sFullscreenRotationActive) {
            HostEntryRotation.releaseAllTaskRotationsLocked("transition completed after workspace exit");
            return;
        }
        for (Object page : new java.util.ArrayList<>(closingTopRotations)) {
            Object task = XposedHelpers.callMethod(page, "getTask");
            if (retainClosingTopRotationLocked(page, task)) continue;
            if (XposedHelpers.callMethod(page, "getParent") == null
                    || Boolean.TRUE.equals(XposedHelpers.getObjectField(page, "finishing"))) {
                if (retainSharedTopRotationLocked(page)) continue;
                if (HostEntry.sTaskRotations.containsKey(page)
                        && !HostEntryRotation.releaseTaskRotationLocked(page, "TOP page transition ended")) continue;
            }
            closingTopRotations.remove(page);
        }
        for (Object page : new java.util.ArrayList<>(topRotationHandoffs)) {
            if (MiStepRotationAnimation.inTransition(page)) continue;
            topRotationHandoffs.remove(page);
            HostEntryRotation.INSTANCE.applyMiStepFixedRotationLocked(display, page, "TOP native transition settled");
        }
    }

    static void onTopRotationTransitionEnd() {
        Object display = HostEntry.sDisplayContent;
        Handler handler = HostEntry.sZoomBridgeHandler;
        if (display == null || handler == null) return;
        if (HostEntry.sFullscreenRotationActive
                ? closingTopRotations.isEmpty() && topRotationHandoffs.isEmpty()
                : HostEntry.sTaskRotations.isEmpty() && !MiStepRotationAnimation.hasOwners()) return;
        int epoch = inactiveRotationCleanupEpoch;
        handler.post(() -> {
            try {
                Object service = XposedHelpers.getObjectField(display, "mWmService");
                synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                    if (epoch != inactiveRotationCleanupEpoch) return;
                    reconcileTopRotationTransitionsLocked(display);
                }
            } catch (Throwable error) { HostEntry.log("TOP transition reconciliation unavailable: " + error); }
        });
    }

    static boolean isLiveTopPageLocked(Object activity, Object task) {
        if (!HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive || activity == null
                || !HostEntryRotation.isUpperLandscapeTask(task) || !MiStepTopRotation.phone(HostEntry.sDisplayContent))
            return false;
        Object top = XposedHelpers.callMethod(task, "topRunningActivity");
        return top != null
                && !Boolean.TRUE.equals(XposedHelpers.getObjectField(top, "finishing"))
                && !Boolean.TRUE.equals(XposedHelpers.getObjectField(top, "mRemovingFromDisplay"))
                && XposedHelpers.callMethod(top, "getParent") != null
                && XposedHelpers.getObjectField(activity, "mDisplayContent") == HostEntry.sDisplayContent
                && !Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "finishing"))
                && !Boolean.TRUE.equals(XposedHelpers.getObjectField(activity, "mRemovingFromDisplay"))
                && XposedHelpers.callMethod(activity, "getParent") != null
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "isActivityTypeHome"));
    }

    static void restoreTopRotationVisibilityLocked(Object activity) {
        if (!ownsTaskRotationTransform(activity)
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "isVisible"))) return;
        Object task = XposedHelpers.callMethod(activity, "getTask");
        if (!isLiveTopPageLocked(activity, task)
                || !sTopRotationBounds.get(activity).equals(XposedHelpers.callMethod(task, "getBounds"))
                || MiStepRotationAnimation.inTransition(activity)) return;
        Object leash = XposedHelpers.callMethod(activity, "getFixedRotationLeash");
        if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) return;
        Object window = XposedHelpers.callMethod(activity, "findMainWindow", false);
        if (window == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(window, "isDrawn"))) return;
        Object animator = XposedHelpers.getObjectField(window, "mWinAnimator");
        if (animator == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(animator, "getShown"))) return;
        // A swipe-back preview can show the Activity without a Shell OPEN for its rotation parent.
        XposedHelpers.callMethod(XposedHelpers.callMethod(activity, "getSyncTransaction"), "show", leash);
    }

    static boolean adoptSharedTopRotationLocked(Object activity, Object task, Object state,
            int desired) {
        if (state == null || !HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive
                || desired == HostEntry.sHeldDisplayRotation || !HostEntryRotation.isUpperLandscapeTask(task)) return false;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        for (Object peer : new java.util.ArrayList<>(sTopRotationBounds.keySet())) {
            if (!bounds.equals(sTopRotationBounds.get(peer))
                    || !Integer.valueOf(desired).equals(HostEntry.sTaskRotations.get(peer))
                    || XposedHelpers.callMethod(peer, "getTask") != task
                    || !ownsTaskRotationTransform(peer)
                    || XposedHelpers.getObjectField(peer, "mFixedRotationTransformState") != state)
                continue;
            HostEntry.sTaskRotations.put(activity, desired);
            HostEntry.sTaskRotationTransforms.put(activity, new java.lang.ref.WeakReference<>(state));
            sTopRotationBounds.put(activity, new Rect(bounds));
            return true;
        }
        return false;
    }

    static boolean retainSharedTopRotationLocked(Object activity) {
        if (!HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive || activity == null) return false;
        try {
            if (XposedHelpers.getObjectField(activity, "mDisplayContent") != HostEntry.sDisplayContent
                    || !MiStepTopRotation.phone(HostEntry.sDisplayContent)) return false;
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (!HostEntryRotation.isUpperLandscapeTask(task)) return false;
            Object top = XposedHelpers.callMethod(task, "topRunningActivity");
            if (top == null || top == activity
                    || Boolean.TRUE.equals(XposedHelpers.getObjectField(top, "finishing"))
                    || Boolean.TRUE.equals(XposedHelpers.getObjectField(top, "mRemovingFromDisplay"))
                    || XposedHelpers.callMethod(top, "getParent") == null) return false;
            Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            return state != null
                    && XposedHelpers.getObjectField(top, "mFixedRotationTransformState") == state
                    && adoptSharedTopRotationLocked(top, task, state,
                            resolveTopRotationLocked(HostEntry.sDisplayContent, top));
        } catch (Throwable error) {
            HostEntry.log("TOP shared rotation lifetime unavailable: " + error);
            return false;
        }
    }

    static android.content.res.Configuration ownedTopRotationConfigurationLocked(
            Object activity, Object task) throws Throwable {
        android.content.res.Configuration applying =
                MiStepTopRotation.applyingConfiguration(activity, task);
        if (applying != null) return applying;
        if (!HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive || !HostEntryRotation.isUpperLandscapeTask(task)
                || !MiStepTopRotation.phone(HostEntry.sDisplayContent)) return null;
        Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
        if (state == null) return null;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if ((!ownsTaskRotationTransform(activity)
                || !bounds.equals(sTopRotationBounds.get(activity)))
                && !adoptSharedTopRotationLocked(activity, task, state,
                        resolveTopRotationLocked(HostEntry.sDisplayContent, activity))) return null;
        return (android.content.res.Configuration) XposedHelpers.getObjectField(
                state, "mRotatedOverrideConfiguration");
    }

    static boolean releaseTaskRotationLocked(Object activity, String reason) {
        if (!HostEntry.sTaskRotations.containsKey(activity)) return false;
        boolean wasUpdating = HostEntry.sUpdatingTaskRotation;
        HostEntry.sUpdatingTaskRotation = true;
        try {
            java.lang.ref.WeakReference<Object> lease = HostEntry.sTaskRotationTransforms.get(activity);
            Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            java.util.ArrayList<Object> members = new java.util.ArrayList<>();
            members.add(activity);
            if (state != null && lease != null && lease.get() == state) {
                for (Object peer : new java.util.ArrayList<>(HostEntry.sTaskRotations.keySet())) {
                    if (peer != activity && ownsTaskRotationTransform(peer)
                            && XposedHelpers.getObjectField(peer, "mFixedRotationTransformState")
                                    == state) members.add(peer);
                }
            }
            try {
                for (Object member : members) {
                    if (!MiStepRotationAnimation.cancel(member)) {
                        HostEntry.log("local rotation animation still owned; defer transform release");
                        return false;
                    }
                }
            } catch (Throwable error) {
                HostEntry.log("local rotation animation cleanup unavailable: " + error);
                return false;
            }
            if (state != null && (lease == null || lease.get() == state)) {
                Object display = XposedHelpers.callMethod(activity, "getDisplayContent");
                for (Object member : members) {
                    if (sTopRotationBounds.containsKey(member))
                        sTopRotationConfigurationRelease.add(member);
                }
                try {
                    if (display != null && MiStepTopRotation.phone(display))
                        MiStepRotationAnimation.finishLocalRotation(activity);
                    else XposedHelpers.callMethod(activity, "finishFixedRotationTransform");
                } finally {
                    sTopRotationConfigurationRelease.removeAll(members);
                }
            }
            for (Object member : members) {
                closingTopRotations.remove(member);
                topRotationHandoffs.remove(member);
                HostEntry.sTaskRotations.remove(member);
                HostEntry.sTaskRotationTransforms.remove(member);
                sTopRotationBounds.remove(member);
                if (HostEntry.sMiStepFixedRotationApp == member) HostEntry.sMiStepFixedRotationApp = null;
            }
            HostEntry.log("task rotation released: " + reason);
            return true;
        } catch (Throwable error) {
            HostEntry.log("fixed rotation release failed: " + reason + ": " + error);
            return false;
        } finally {
            HostEntry.sUpdatingTaskRotation = wasUpdating;
        }
    }

    static int inactiveRotationCleanupEpoch;

    static void queueInactiveRotationCleanupLocked(Object display) {
        int epoch = ++inactiveRotationCleanupEpoch;
        queueInactiveRotationCleanupLocked(display, epoch, 3);
    }

    static void queueInactiveRotationCleanupLocked(Object display, int epoch, int remaining) {
        if (HostEntry.sFullscreenRotationActive || HostEntry.sTaskRotations.isEmpty() && !MiStepRotationAnimation.hasOwners()
                || display != HostEntry.sDisplayContent) return;
        if (remaining == 0) {
            HostEntry.log("inactive rotation cleanup remains blocked by native animation");
            return;
        }
        Handler bridge = HostEntry.sZoomBridgeHandler;
        if (bridge == null) {
            HostEntry.log("inactive rotation cleanup unavailable: no bridge handler");
            return;
        }
        bridge.postDelayed(() -> {
            try {
                Object service = XposedHelpers.getObjectField(display, "mWmService");
                synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                    if (epoch != inactiveRotationCleanupEpoch || HostEntry.sFullscreenRotationActive
                            || display != HostEntry.sDisplayContent) return;
                    HostEntryRotation.releaseAllTaskRotationsLocked("deferred inactive cleanup");
                    queueInactiveRotationCleanupLocked(display, epoch, remaining - 1);
                }
            } catch (Throwable error) {
                HostEntry.log("inactive rotation cleanup failed: " + error);
            }
        }, 400);
    }

    static boolean workspaceRotationRefreshPending;

    /** A proposal can change while the physical display remains held at zero. */
    static void queueWorkspaceRotationRefresh(Object rotation) {
        if (!HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive) return;
        try {
            Object display = XposedHelpers.getObjectField(rotation, "mDisplayContent");
            if (display != HostEntry.sDisplayContent || HostEntry.sZoomBridgeHandler == null) return;
            synchronized (HostEntryTopRotation.class) {
                if (workspaceRotationRefreshPending) return;
                workspaceRotationRefreshPending = true;
            }
            boolean posted = HostEntry.sZoomBridgeHandler.post(() -> {
                synchronized (HostEntryTopRotation.class) { workspaceRotationRefreshPending = false; }
                try {
                    Object service = XposedHelpers.getObjectField(display, "mWmService");
                    synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                        if (HostEntry.sZoomed && HostEntry.sFullscreenRotationActive && display == HostEntry.sDisplayContent
                                && !HostEntry.sUpdatingTaskRotation) HostEntryRotation.INSTANCE.refreshMiStepTaskRotationLocked(display);
                    }
                } catch (Throwable error) { HostEntry.log("workspace direction proposal refresh failed: " + error); }
            });
            if (!posted) synchronized (HostEntryTopRotation.class) { workspaceRotationRefreshPending = false; }
        } catch (Throwable error) {
            synchronized (HostEntryTopRotation.class) { workspaceRotationRefreshPending = false; }
            HostEntry.log("workspace direction proposal unavailable: " + error);
        }
    }

    /** TOP follows the system USER policy, independent of its app's landscape request. */
    static int resolveTopRotationLocked(Object display, Object activity) throws Throwable {
        Object resolver = XposedHelpers.getObjectField(display, "mDisplayRotation");
        int last = HostEntry.sTaskRotations.getOrDefault(activity, HostEntry.sHeldDisplayRotation);
        int resolved = (Integer) de.robv.android.xposed.XposedBridge.invokeOriginalMethod(HostEntry.sTaskRotationResolver, resolver,
                new Object[]{2, last});
        return MiStepTopRotation.rotation(resolved);
    }

    static final java.util.Map<Object, Rect> sTopRotationBounds = new java.util.WeakHashMap<>();
    static final java.util.Set<Object> sTopRotationConfigurationRelease =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    static final class TopRelaunchScope {
        android.content.res.Configuration previous;
    }
    static final java.util.Map<Object, TopRelaunchScope>
            sTopRotationRelaunchScopes = new java.util.WeakHashMap<>();

    static boolean preserveTopWindowForRelaunch(Object activity, int changes) {
        TopRelaunchScope scope = sTopRotationRelaunchScopes.get(activity);
        return scope != null && scope.previous != null
                && keepTopActivityForRotationConfig(activity, changes, scope.previous);
    }

    static boolean keepTopActivityForRotationConfig(Object activity, int changes, Object previous) {
        if (!HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive || activity == null
                || XposedHelpers.callMethod(activity, "getDisplayContent") != HostEntry.sDisplayContent)
            return false;
        if (!Integer.valueOf(HostEntry.sHeldDisplayRotation).equals(XposedHelpers.callMethod(
                XposedHelpers.getObjectField(HostEntry.sDisplayContent, "mDisplayRotation"), "getRotation")))
            return false;
        Object task = XposedHelpers.callMethod(activity, "getTask");
        if (!isLiveTopPageLocked(activity, task))
            return false;
        Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
        // Native apply dispatches configuration synchronously before it returns the state
        // for our long-lived lease. The helper's exact task/config call scope covers that gap.
        if (MiStepTopRotation.isApplying(activity, task))
            return MiStepTopRotation.shouldKeepLiveSurface(
                    activity, changes, previous, state, false);
        java.lang.ref.WeakReference<Object> lease = HostEntry.sTaskRotationTransforms.get(activity);
        Rect expected = sTopRotationBounds.get(activity);
        if (lease == null || lease.get() == null || expected == null
                || !expected.equals(XposedHelpers.callMethod(task, "getBounds"))) return false;
        return MiStepTopRotation.shouldKeepLiveSurface(activity, changes, previous,
                lease.get(), sTopRotationConfigurationRelease.contains(activity));
    }

    static boolean ownsTaskRotationTransform(Object activity) {
        java.lang.ref.WeakReference<Object> lease = HostEntry.sTaskRotationTransforms.get(activity);
        return lease != null && lease.get() != null && lease.get() == XposedHelpers.getObjectField(
                activity, "mFixedRotationTransformState");
    }

    static void animateTaskRotationLocked(Object activity, int oldRotation, int newRotation) {
        if (!HostEntry.sZoomed || oldRotation == newRotation) return;
        try {
            if (HostEntryRotation.isUpperLandscapeTask(XposedHelpers.callMethod(activity, "getTask"))
                    && MiStepRotationAnimation.inTransition(activity)) {
                // Shell owns the visible motion; reconcile the final viewport without replaying it.
                topRotationHandoffs.add(activity);
                return;
            }
            if (MiStepTopRotation.phone(HostEntry.sDisplayContent)
                    && MiStepRotationAnimation.start(activity, oldRotation, newRotation))
                HostEntry.log("local rotation animation native-window=true old=" + oldRotation
                        + " new=" + newRotation);
        } catch (Throwable error) {
            // Animation availability must never break configuration, input or native cleanup.
            HostEntry.log("local rotation animation unavailable: " + error);
        }
    }

    static Boolean applyTopRotationLocked(Object display, Object activity, Object task, String source)
            throws Throwable {
        int desired = resolveTopRotationLocked(display,
                XposedHelpers.callMethod(task, "topRunningActivity"));
        Integer previous = HostEntry.sTaskRotations.get(activity);
        if (desired == HostEntry.sHeldDisplayRotation) {
            boolean owned = ownsTaskRotationTransform(activity);
            boolean released = HostEntryRotation.releaseTaskRotationLocked(activity, "TOP returned to landscape viewport");
            if (released && owned && previous != null)
                animateTaskRotationLocked(activity, previous, desired);
            return Boolean.FALSE;
        }
        Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
        java.lang.ref.WeakReference<Object> lease = HostEntry.sTaskRotationTransforms.get(activity);
        if (state != null && (previous == null || lease == null || lease.get() != state)) {
            if (adoptSharedTopRotationLocked(activity, task, state, desired)) {
                MiStepTopRotation.ensureSurface(activity);
                HostEntry.log("TOP joined owned native page rotation source=" + source);
                return Boolean.TRUE;
            }
            if (previous != null) HostEntryRotation.releaseTaskRotationLocked(activity, "TOP native owner replaced");
            return Boolean.FALSE;
        }
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if (state != null && Integer.valueOf(desired).equals(previous)
                && bounds.equals(sTopRotationBounds.get(activity))) {
            MiStepTopRotation.ensureSurface(activity);
            return Boolean.TRUE;
        }
        if (HostEntry.sTaskRotations.containsKey(activity)
                && !HostEntryRotation.releaseTaskRotationLocked(activity, "TOP direction changed"))
            return Boolean.FALSE;
        HostEntry.sTaskRotations.put(activity, desired);
        try {
            MiStepTopRotation.apply(display, activity, task, desired);
            Object installed = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            if (installed == null) throw new IllegalStateException("TOP native rotation not installed");
            HostEntry.sTaskRotationTransforms.put(activity, new java.lang.ref.WeakReference<>(installed));
            sTopRotationBounds.put(activity, new Rect(bounds));
            animateTaskRotationLocked(activity,
                    previous == null ? HostEntry.sHeldDisplayRotation : previous, desired);
        } catch (Throwable error) {
            // The helper may have installed state before its native leash operation failed.
            Object installed = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
            if (installed != null) HostEntry.sTaskRotationTransforms.put(activity, new java.lang.ref.WeakReference<>(installed));
            HostEntryRotation.releaseTaskRotationLocked(activity, "TOP rotation failed cleanup");
            throw error;
        }
        HostEntry.log("TOP local viewport rotation=" + desired + " source=" + source);
        return Boolean.TRUE;
    }
}
