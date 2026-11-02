package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 固定主区旋转 / 任务旋转解析 / 副格系统栏策略触达 / 主 UI 旋转发布 */
final class HostEntryRotation {
    static final HostEntryRotation INSTANCE = new HostEntryRotation();

    private HostEntryRotation() {
    }

    void updateMainSystemUiRotation(Object window) {
        if (!HostEntry.sFullscreenRotationActive || !HostEntry.sZoomed) return;
        try {
            Object app = HostEntry.sMiStepFixedRotationApp;
            if (app != null && !HostEntry.sTaskRotations.containsKey(app)) app = null;
            publishMainUiConfiguration(app);
            HostEntry.sMainSystemUiRotation.update(window, HostEntry.sDisplayContent, app, HostEntry::log);
            HostEntry.sMainStatusBarClip.update(window, HostEntry.sDisplayContent, HostEntry::log);
            HostEntry.sMainImeProcessConfiguration.update(window, HostEntry.sDisplayContent, app, HostEntry::log);
        } catch (Throwable error) {
            if (!HostEntry.systemUiRotationFailureLogged) {
                HostEntry.systemUiRotationFailureLogged = true;
                HostEntry.log("main system UI rotation unavailable: " + error);
            }
        }
    }

    static void publishMainUiConfiguration(Object app) {
        try {
            Object state = app == null ? null : XposedHelpers.getObjectField(app, "mFixedRotationTransformState");
            if (state == HostEntry.sPublishedMainUiState) return;
            HostEntry.sPublishedMainUiState = state;
            final android.content.res.Configuration config = state == null ? null
                    : new android.content.res.Configuration((android.content.res.Configuration)
                            XposedHelpers.getObjectField(state, "mRotatedOverrideConfiguration"));
            Context context = (Context) (HostEntry.sSystemContext != null ? HostEntry.sSystemContext : HostEntry.sWmsContext);
            Handler bridge = HostEntry.sZoomBridgeHandler;
            if (context == null || bridge == null) return;
            // Never broadcast synchronously while holding WMS's global lock.
            bridge.post(() -> context.sendBroadcast(new Intent(MiStepMainUiConfiguration.ACTION)
                    .setPackage(HostEntry.SYSTEMUI).putExtra("configuration", config),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS));
        } catch (Throwable error) { HostEntry.log("main UI configuration publication unavailable: " + error); }
    }

    void installMainSystemUiRotation(ClassLoader cl) {
        try {
            HostEntry.sMainImeProcessConfiguration.install(cl);
            Class<?> organizer = XposedHelpers.findClass("com.android.server.wm.WindowOrganizerController", cl);
            XposedBridge.hookAllMethods(organizer, "applyTaskChanges", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!HostEntry.sFullscreenRotationActive || p.args.length < 2) return;
                    try { p.setObjectExtra("mistep.sideRole", MiStepSideRoleChange.enter(
                            p.args[0], p.args[1], HostEntry.sDisplayContent)); }
                    catch (Throwable error) { HostEntry.log("side role change observation unavailable: " + error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    MiStepSideRoleChange.exit(p.getObjectExtra("mistep.sideRole"));
                }
            });
            Class<?> policy = XposedHelpers.findClass("com.android.server.wm.DisplayPolicy", cl);
            XposedBridge.hookAllMethods(policy, "layoutWindowLw", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0) updateMainSystemUiRotation(p.args[0]);
                }
            });
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "getSurfaceTouchableRegion",
                    android.graphics.Region.class, WindowManager.LayoutParams.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                    if (p.hasThrowable() || !HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive
                            || target == null || target.display != HostEntry.sDisplayContent
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        MiStepMainPanelTouchRegion.crop(p.thisObject, target.display,
                                (android.graphics.Region) p.args[0],
                                (WindowManager.LayoutParams) p.args[1]);
                    } catch (Throwable error) {
                        HostEntry.log("main panel touch boundary unavailable: " + error);
                    }
                }
            });
            HostEntry.log("main notification shade modal touch limited to its own surface");
            XposedBridge.hookAllMethods(window, "prepareSurfaces", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    updateMainSystemUiRotation(p.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !HostEntry.sZoomed || HostEntry.sDisplayContent == null) return;
                    try {
                        Object spec = XposedHelpers.getObjectField(HostEntry.sDisplayContent, "mMagnificationSpec");
                        if (spec != null) MiStepSystemUiRotation.magnifyPrivacy(p.thisObject,
                                HostEntry.sDisplayContent, spec,
                                XposedHelpers.callMethod(HostEntry.sDisplayContent, "getPendingTransaction"));
                    } catch (Throwable error) {
                        if (!HostEntry.systemUiRotationFailureLogged) {
                            HostEntry.systemUiRotationFailureLogged = true;
                            HostEntry.log("permission window zoom unavailable: " + error);
                        }
                    }
                }
            });
            HostEntry.log("main system UI native token rotation and late permission-window zoom hooked");
        } catch (Throwable error) { HostEntry.log("main system UI rotation hooks unavailable: " + error); }
    }

    void installFixedMainRotationHooks(ClassLoader cl) {
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", cl);
            XposedBridge.hookAllMethods(activityRecord, "resolveOverrideConfiguration",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive
                            || p.args.length != 1
                            || !(p.args[0] instanceof android.content.res.Configuration)) return;
                    try {
                        if (XposedHelpers.getObjectField(p.thisObject, "mDisplayContent")
                                != HostEntry.sDisplayContent) return;
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        MiStepTopConfiguration.apply(p.thisObject, task,
                                (android.content.res.Configuration) p.args[0]);
                    } catch (Throwable error) {
                        HostEntry.log("upper Activity configuration unavailable: " + error);
                    }
                }
            });
            HostEntry.log("upper Activity configuration follows full task viewport");
        } catch (Throwable error) {
            HostEntry.log("upper Activity configuration hook unavailable: " + error);
        }
        try {
            Class<?> displayContent = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(displayContent,
                    "handleTopActivityLaunchingInDifferentOrientation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 1 || p.args[0] == null) return;
                    Boolean decision = applyMiStepFixedRotationLocked(
                            p.thisObject, p.args[0], "activity-orientation");
                    if (decision != null) p.setResult(decision);
                }
            });
            HostEntry.log("task rotation: activity path intercepted before display launch");
        } catch (Throwable error) {
            HostEntry.log("fixed rotation top-activity hook unavailable: " + error);
        }
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", cl);
            XposedBridge.hookAllMethods(activityRecord, "setRequestedOrientation",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0 && p.args[0] instanceof Integer) {
                        beforeApplicationOrientationRequest(p.thisObject, (Integer) p.args[0]);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!HostEntry.sFullscreenRotationActive || p.thisObject == null) return;
                    try {
                        Object display = XposedHelpers.getObjectField(
                                p.thisObject, "mDisplayContent");
                        // This path also covers non-top Mi Step side tasks. The stock
                        // DisplayContent hook only sees the current top activity.
                        applyMiStepFixedRotationLocked(display, p.thisObject,
                                "activity-requested-orientation");
                    } catch (Throwable error) {
                        HostEntry.log("task rotation requested-orientation refresh failed: " + error);
                    }
                }
            });
            HostEntry.log("task rotation: ActivityRecord requests hooked for side tasks");
        } catch (Throwable error) {
            HostEntry.log("fixed rotation ActivityRecord hook unavailable: " + error);
        }
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", cl);
            XposedBridge.hookAllMethods(activityRecord, "onConfigurationChanged",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!HostEntry.sFullscreenRotationActive || p.thisObject == null
                            || HostEntry.sUpdatingTaskRotation) return;
                    try {
                        Object display = XposedHelpers.getObjectField(
                                p.thisObject, "mDisplayContent");
                        applyMiStepFixedRotationLocked(display, p.thisObject,
                                "activity-configuration-changed");
                    } catch (Throwable error) {
                        HostEntry.log("task rotation configuration refresh failed: " + error);
                    }
                }
            });
            HostEntry.log("task rotation: configuration changes re-evaluate token orientation");
        } catch (Throwable error) {
            HostEntry.log("fixed rotation configuration hook unavailable: " + error);
        }
        try {
            Class<?> displayContent = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayContent", cl);
            for (String name : new String[]{"setFixedRotationLaunchingApp",
                    "setFixedRotationLaunchingAppUnchecked"}) {
                XposedBridge.hookAllMethods(displayContent, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (HostEntry.sFullscreenRotationActive && p.thisObject == HostEntry.sDisplayContent
                                && p.args.length > 0 && p.args[0] != null
                                && HostEntry.sTaskRotations.containsKey(p.args[0])) {
                            // OEM promotion paths also try to register an already transformed
                            // token as a display launch. Keep our transform local to that token.
                            p.setResult(null);
                        }
                    }
                });
            }
            HostEntry.log("task rotation: owned tokens excluded from display launch notifications");
        } catch (Throwable error) {
            HostEntry.log("task rotation display-launch exclusion unavailable: " + error);
        }
        try {
            Class<?> displayRotation = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayRotation", cl);
            HostEntry.sTaskRotationResolver = XposedHelpers.findMethodExact(displayRotation,
                    "rotationForOrientation", int.class, int.class);
            XposedBridge.hookMethod(HostEntry.sTaskRotationResolver, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object display = XposedHelpers.getObjectField(p.thisObject,
                                "mDisplayContent");
                        int displayId = (Integer) XposedHelpers.callMethod(display, "getDisplayId");
                        if (FullscreenRotationPolicy.shouldHoldDisplay(
                                HostEntry.sFullscreenRotationActive, displayId)
                                && display == HostEntry.sDisplayContent) {
                            p.setResult(HostEntry.sHeldDisplayRotation);
                        }
                    } catch (Throwable error) {
                        HostEntry.log("fixed rotation display hold skipped: " + error);
                    }
                }
            });
            HostEntry.log("fixed rotation: display 0 hold hooked");
            XposedBridge.hookAllMethods(displayRotation, "updateRotationUnchecked",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (HostEntry.sUpdatingTaskRotation) return;
                    try {
                        Object display = XposedHelpers.getObjectField(p.thisObject,
                                "mDisplayContent");
                        if (display != HostEntry.sDisplayContent) return;
                        evaluateNativeLandscapeLocked(display,
                                "display rotation updated");
                        if (!HostEntry.sFullscreenRotationActive) return;
                        refreshMiStepTaskRotationLocked(display);
                    } catch (Throwable error) {
                        HostEntry.log("task rotation policy refresh failed: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            HostEntry.log("fixed rotation display hold unavailable: " + error);
        }
    }

    void installFixedMainTouchableCropHook(ClassLoader cl) {
        try {
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "cropRegionToRootTaskBoundsIfNeeded",
                    android.graphics.Region.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 1 && p.args[0] instanceof android.graphics.Region
                            && cropFixedMainTouchableRegion(p.thisObject,
                                    (android.graphics.Region) p.args[0])) p.setResult(null);
                }
            });
            HostEntry.log("owned fixed main: root touch crop uses rotated display coordinates");
        } catch (Throwable error) {
            HostEntry.log("fixed main touch crop hook unavailable: " + error);
        }
    }

    static boolean cropFixedMainTouchableRegion(Object window,
            android.graphics.Region region) {
        HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
        if (!HostEntry.sZoomed || !HostEntry.sFullscreenRotationActive || window == null || region == null
                || target == null || target.display != HostEntry.sDisplayContent
                || !Thread.holdsLock(target.globalLock)) return false;
        try {
            if (XposedHelpers.callMethod(window, "getDisplayContent") != HostEntry.sDisplayContent)
                return false;
            Object activity = XposedHelpers.getObjectField(window, "mActivityRecord");
            if (activity == null || activity != HostEntry.sMiStepFixedRotationApp) return false;
            Integer rotation = HostEntry.sTaskRotations.get(activity);
            if (rotation == null || ((rotation - HostEntry.sHeldDisplayRotation) & 1) != 1) return false;
            Object task = XposedHelpers.callMethod(window, "getTask");
            if (task == null || !Integer.valueOf(1).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))) return false;
            Object root = XposedHelpers.callMethod(task, "getRootTask");
            if (root == null || !Integer.valueOf(1).equals(
                    XposedHelpers.callMethod(root, "getWindowingMode"))) return false;
            Rect displayBounds = (Rect) XposedHelpers.callMethod(HostEntry.sDisplayContent, "getBounds");
            Rect rootBounds = (Rect) XposedHelpers.callMethod(root, "getBounds");
            Rect rotatedBounds = (Rect) XposedHelpers.callMethod(activity,
                    "getFixedRotationTransformDisplayBounds");
            if (displayBounds == null || !displayBounds.equals(rootBounds)
                    || rotatedBounds == null || rotatedBounds.isEmpty()
                    || rotatedBounds.width() != displayBounds.height()
                    || rotatedBounds.height() != displayBounds.width()) return false;
            // Keep letterbox exclusions and nonrectangular regions. Only change the
            // coordinate system of this full-display root intersection.
            region.op(rotatedBounds, android.graphics.Region.Op.INTERSECT);
            return true;
        } catch (Throwable error) {
            HostEntry.log("fixed main touch crop skipped: " + error);
            return false;
        }
    }

    void beforeApplicationOrientationRequest(Object activity, int request) {
        if (!HostEntry.sFullscreenRotationActive || HostEntry.sUpdatingTaskRotation || activity == null
                || FullscreenRotationPolicy.requestsLandscape(request) || request == 14) return;
        try {
            if (XposedHelpers.getObjectField(activity, "mDisplayContent") != HostEntry.sDisplayContent) return;
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (task == null || Integer.valueOf(5).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))) return;
            if (XposedHelpers.callMethod(task, "topRunningActivity") != activity) return;
            TaskWindowState state = HostEntry.sTaskWindowStates.get(task);
            if (state != null) state.forgetRememberedRotation();
        } catch (Throwable error) {
            HostEntry.log("application orientation history reset unavailable: " + error);
        }
    }

    Boolean applyMiStepFixedRotationLocked(Object display, Object activity, String source) {
        if (!HostEntry.sFullscreenRotationActive || display == null || display != HostEntry.sDisplayContent
                || activity == null) return null;
        if (HostEntry.sUpdatingTaskRotation) return Boolean.FALSE;
        HostEntry.sUpdatingTaskRotation = true;
        try {
            boolean home = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    activity, "isActivityTypeHome"));
            boolean multiWindow = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    activity, "inMultiWindowMode"));
            boolean sideTask = isSideFixedTask(activity);
            Object task = XposedHelpers.callMethod(activity, "getTask");
            int windowingMode = task == null ? 1
                    : (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            if (isUpperLandscapeTask(task)) {
                if (XposedHelpers.callMethod(task,"topRunningActivity")==activity) {
                    HostEntry.RotationResolution upper=resolveTaskRotationLocked(display,activity,task,windowingMode,source);
                    recordTaskWindowState(activity,upper.overrideOrientation,upper.resolvedRotation,
                            upper.taskRotation,6,source+" upper-landscape");
                }
                releaseTaskRotationLocked(activity,"upper task uses native landscape bounds");
                return Boolean.FALSE;
            }
            if (windowingMode == FullscreenRotationPolicy.WINDOWING_MODE_FREEFORM) {
                // HyperOS owns native freeform configuration, surfaces and rotation.
                // Never turn its orientation callback into our fullscreen/TaskView path.
                captureTaskWindowState(display, activity, source + " native-freeform");
                releaseTaskRotationLocked(activity, "returned to native freeform owner");
                return null;
            }
            // Role changes reconfigure the whole back stack. Only its running top
            // activity can define this task's direction or replace the main owner.
            // A portrait page underneath a landscape video must not cancel that
            // video's token transform while mode1/mode6 is being exchanged.
            if (task != null && XposedHelpers.callMethod(task, "topRunningActivity") != activity) {
                return Boolean.FALSE;
            }
            if (home || (multiWindow && !sideTask)) {
                // A promoted token keeps its local transform in the side slot. Do not
                // let WM promote that transform to mFixedRotationLaunchingApp.
                Object previous = HostEntry.sMiStepFixedRotationApp;
                if (previous != null) {
                    if (!isSideFixedTask(previous)) {
                        releaseTaskRotationLocked(previous,
                                home ? "main activity left for home" : "main activity left fullscreen");
                    }
                    if (HostEntry.sMiStepFixedRotationApp == previous) HostEntry.sMiStepFixedRotationApp = null;
                }
                return Boolean.FALSE;
            }
            if (NativeLandscapePolicy.suppressesLocalRotation(
                    HostEntry.sNativeLandscapeState, sideTask, home)) {
                // The workspace is temporarily suspended while the native display owns
                // the full landscape app. Side tasks were returned to system ownership.
                return Boolean.FALSE;
            }
            Object previous = HostEntry.sMiStepFixedRotationApp;
            if (!sideTask && previous != null && previous != activity) {
                if (!isSideFixedTask(previous)) {
                    releaseTaskRotationLocked(previous, "main activity changed");
                }
                HostEntry.sMiStepFixedRotationApp = null;
            }
            HostEntry.RotationResolution resolution = resolveTaskRotationLocked(
                    display, activity, task, windowingMode, source);
            int overrideOrientation = resolution.overrideOrientation;
            int resolvedRotation = resolution.resolvedRotation;
            int rotation = resolution.taskRotation;
            if (resolution.lands() && NativeLandscapePolicy.shouldUseNativeFullscreen(
                    HostEntry.sZoomed, sideTask, home)) {
                recordTaskWindowState(activity, overrideOrientation, resolvedRotation,
                        rotation, 1, source + " native-fullscreen");
                requestNativeLandscapeLocked(activity, source);
                return Boolean.FALSE;
            }
            recordTaskWindowState(activity, overrideOrientation, resolvedRotation,
                    rotation, sideTask ? 6 : 1, source);
            if (rotation == HostEntry.sHeldDisplayRotation) {
                releaseTaskRotationLocked(activity, source + " -> portrait/locked");
                return Boolean.FALSE;
            }
            boolean transformed = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    activity, "hasFixedRotationTransform"));
            Integer existingRotation = HostEntry.sTaskRotations.get(activity);
            if (transformed && existingRotation == null) {
                // Leave a native, in-flight rotation under its original owner's lifecycle.
                return Boolean.FALSE;
            }
            if (!transformed || existingRotation == null || existingRotation != rotation) {
                releaseTaskRotationLocked(activity, "task direction changed");
                if (!sideTask) HostEntry.sMiStepFixedRotationApp = activity;
                HostEntry.sTaskRotations.put(activity, rotation);
                try {
                    XposedHelpers.callMethod(display, "startFixedRotationTransform",
                            activity, rotation);
                } catch (Throwable error) {
                    releaseTaskRotationLocked(activity, "failed transform cleanup");
                    throw error;
                }
                HostEntry.log("task rotation: task=" + XposedHelpers.callMethod(activity, "getRootTaskId")
                        + " request=" + overrideOrientation + " resolved=" + resolvedRotation
                        + " rotationLocked=" + resolution.rotationLocked
                        + " held=" + HostEntry.sHeldDisplayRotation
                        + " existing=" + existingRotation + " taskRotation=" + rotation
                        + " localToken=true source=" + source);
            } else {
                if (!sideTask) HostEntry.sMiStepFixedRotationApp = activity;
            }
            return Boolean.TRUE;
        } catch (Throwable error) {
            HostEntry.log("fixed rotation apply failed source=" + source + ": " + error);
            XposedBridge.log(error);
            return Boolean.FALSE;
        } finally {
            HostEntry.sUpdatingTaskRotation = false;
        }
    }

    HostEntry.RotationResolution resolveTaskRotationLocked(Object display, Object activity,
            Object task, int windowingMode, String source) throws Throwable {
        int overrideOrientation = (Integer) XposedHelpers.callMethod(
                activity, "getOverrideOrientation");
        TaskWindowState state = HostEntry.sTaskWindowStates.get(task);
        if (state == null) {
            captureTaskWindowState(display, activity, source + " initial");
            state = HostEntry.sTaskWindowStates.get(task);
        }
        int rememberedRotation = state == null ? TaskWindowState.UNKNOWN_ROTATION
                : state.preferredRememberedRotation();
        boolean rotationLocked = isSystemRotationLocked(display);
        int lastRotation = HostEntry.sTaskRotations.containsKey(activity)
                ? HostEntry.sTaskRotations.get(activity)
                : rememberedRotation >= 0 ? rememberedRotation : HostEntry.sHeldDisplayRotation;
        int resolvedRotation = HostEntry.sHeldDisplayRotation;
        if (!rotationLocked
                || !FullscreenRotationPolicy.usesSensorOrUserRotation(overrideOrientation)
                || FullscreenRotationPolicy.requestsLandscape(overrideOrientation)) {
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            // Invoke WM's original resolver, bypassing only our display-hold hook.
            // This retains OEM policy, current-user settings and sensor filtering.
            resolvedRotation = (Integer) XposedBridge.invokeOriginalMethod(
                    HostEntry.sTaskRotationResolver, displayRotation,
                    new Object[]{overrideOrientation, lastRotation});
        }
        int rotation = FullscreenRotationPolicy.taskRotation(
                overrideOrientation, resolvedRotation, rotationLocked,
                windowingMode, rememberedRotation);
        return new HostEntry.RotationResolution(
                overrideOrientation, resolvedRotation, rotation, rotationLocked);
    }

    void requestNativeLandscapeLocked(Object activity, String source) {
        int next = NativeLandscapePolicy.onLandscapeRequested(HostEntry.sNativeLandscapeState);
        if (next == HostEntry.sNativeLandscapeState) return;
        HostEntry.sNativeLandscapeState = next;
        sendNativeLandscapeMode(true);
        HostEntry.log("native landscape requested: task="
                + XposedHelpers.callMethod(activity, "getRootTaskId") + " source=" + source);
    }

    void evaluateNativeLandscapeLocked(Object display, String source) {
        if (HostEntry.sNativeLandscapeState != NativeLandscapePolicy.FULLSCREEN
                || display == null || display != HostEntry.sDisplayContent) return;
        try {
            Object top = XposedHelpers.callMethod(display, "topRunningActivity");
            if (top == null) return;
            boolean home = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    top, "isActivityTypeHome"));
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            int rotation = (Integer) XposedHelpers.callMethod(displayRotation, "getRotation");
            boolean landscape = rotation == 1 || rotation == 3;
            if (!NativeLandscapePolicy.needsResume(
                    HostEntry.sNativeLandscapeState, landscape, home)) return;
            HostEntry.sNativeLandscapeState = NativeLandscapePolicy.onResumeRequested(
                    HostEntry.sNativeLandscapeState);
            sendNativeLandscapeMode(false);
            HostEntry.log("restore workspace after native landscape: rotation=" + rotation
                    + " home=" + home + " source=" + source);
        } catch (Throwable error) {
            HostEntry.log("native landscape restore check failed: " + error);
        }
    }

    void sendNativeLandscapeMode(boolean fullscreen) {
        try {
            Object rawContext = HostEntry.sSystemContext != null ? HostEntry.sSystemContext : HostEntry.sWmsContext;
            Context context = (Context) rawContext;
            if (context == null) throw new IllegalStateException("system context unavailable");
            final Intent intent = new Intent(MiStepHost.LANDSCAPE_MODE_ACTION)
                    .setPackage(HostEntry.SYSTEMUI).putExtra("fullscreen", fullscreen);
            final Handler bridge = HostEntry.sZoomBridgeHandler;
            final Runnable send = () -> {
                try {
                    context.sendBroadcast(intent,
                            android.Manifest.permission.WRITE_SECURE_SETTINGS);
                } catch (Throwable error) {
                    HostEntry.log("native landscape bridge send failed: " + error);
                }
            };
            // requestNativeLandscapeLocked runs under WindowManagerGlobalLock.
            // A synchronous broadcast can invert with ActivityManagerService and
            // deadlock system_server, so hand it to the existing private bridge thread.
            if (bridge != null) bridge.post(send);
            else new Thread(send, "mistep-landscape-bridge").start();
        } catch (Throwable error) {
            HostEntry.sNativeLandscapeState = NativeLandscapePolicy.INACTIVE;
            HostEntry.log("native landscape bridge failed: " + error);
        }
    }

    static boolean releaseTaskRotationLocked(Object activity, String reason) {
        if (!HostEntry.sTaskRotations.containsKey(activity)) return false;
        try {
            XposedHelpers.callMethod(activity, "finishFixedRotationTransform");
            HostEntry.sTaskRotations.remove(activity);
            if (HostEntry.sMiStepFixedRotationApp == activity) HostEntry.sMiStepFixedRotationApp = null;
            HostEntry.log("task rotation released: " + reason);
            return true;
        } catch (Throwable error) {
            HostEntry.log("fixed rotation release failed: " + reason + ": " + error);
            return false;
        }
    }

    static void recordTaskWindowState(Object activity, int requestedOrientation,
            int resolvedRotation, int taskRotation, int fallbackWindowingMode, String source) {
        try {
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (task == null) return;
            int taskId = (Integer) XposedHelpers.callMethod(activity, "getRootTaskId");
            int windowingMode = fallbackWindowingMode;
            try {
                windowingMode = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            } catch (Throwable ignored) { }
            int configurationOrientation = 0;
            try {
                Object config = XposedHelpers.callMethod(activity, "getConfiguration");
                configurationOrientation = XposedHelpers.getIntField(config, "orientation");
            } catch (Throwable ignored) { }
            TaskWindowState state = HostEntry.sTaskWindowStates.get(task);
            if (state == null) {
                state = new TaskWindowState(taskId);
                HostEntry.sTaskWindowStates.put(task, state);
            }
            state.update(-1, configurationOrientation, requestedOrientation,
                    resolvedRotation == -1 ? taskRotation : resolvedRotation,
                    windowingMode, windowingMode == 1, true, taskRotation);
            sendTopTaskDirection(task,taskId,requestedOrientation,resolvedRotation,taskRotation,windowingMode);
            HostEntry.log("task window state task=" + taskId + " mode=" + windowingMode
                    + " configOrientation=" + configurationOrientation
                    + " request=" + requestedOrientation + " resolved=" + resolvedRotation
                    + " localRotation=" + taskRotation + " source=" + source);
        } catch (Throwable error) {
            HostEntry.log("task window state unavailable: " + error);
        }
    }

    static void sendTopTaskDirection(Object task,int id,int request,int resolved,int rotation,int mode) {
        if(!HostEntry.sZoomed) {HostEntry.topTaskSignals.clear();return;}
        if(task==null || (mode!=1 && !isUpperLandscapeTask(task)))return;
        try {
            Object info=XposedHelpers.callMethod(task,"getTaskInfo");
            int user=XposedHelpers.getIntField(info,"userId");
            Object token=XposedHelpers.getObjectField(info,"token");
            boolean landscape=FullscreenRotationPolicy.requestsLandscape(request)
                    || FullscreenRotationPolicy.usesSensorOrUserRotation(request) && (rotation==1 || rotation==3);
            boolean focused=XposedHelpers.getBooleanField(info,"isFocused");
            String signature=HostEntry.topWorkspaceGeneration+":"+user+":"+mode+":"+landscape+":"+focused;
            if(signature.equals(HostEntry.topTaskSignals.get(task)))return;
            HostEntry.topTaskSignals.put(task,signature);
            Context context=(Context)(HostEntry.sSystemContext!=null?HostEntry.sSystemContext:HostEntry.sWmsContext);
            Handler bridge=HostEntry.sZoomBridgeHandler;
            if(context==null || bridge==null || !(token instanceof android.os.Parcelable))return;
            Intent signal=new Intent(MiStepHost.TOP_TASK_STATE_ACTION).setPackage(HostEntry.SYSTEMUI)
                    .putExtra("task",id).putExtra("user",user).putExtra("token",(android.os.Parcelable)token)
                    .putExtra("landscape",landscape);
            bridge.post(()->{
                try {context.sendBroadcast(signal,android.Manifest.permission.WRITE_SECURE_SETTINGS);}
                catch(Throwable error){HostEntry.log("upper direction bridge: "+error);}
            });
        } catch(Throwable error) {HostEntry.log("upper task direction unavailable: "+error);}
    }

    static boolean isUpperLandscapeTask(Object task) {
        try {
            if(!MiStepSideRoleChange.isSide(task))return false;
            android.graphics.Rect bounds=(android.graphics.Rect)XposedHelpers.callMethod(task,"getBounds");
            return bounds!=null && bounds.width()>bounds.height();
        } catch(Throwable unavailable) {return false;}
    }

    static void captureTaskWindowState(Object display, Object activity, String source) {
        if (activity == null) return;
        try {
            int request = (Integer) XposedHelpers.callMethod(activity, "getOverrideOrientation");
            Object configuration = XposedHelpers.callMethod(activity, "getConfiguration");
            int orientation = XposedHelpers.getIntField(configuration, "orientation");
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            int physicalRotation = (Integer) XposedHelpers.callMethod(displayRotation, "getRotation");
            int rotation = FullscreenRotationPolicy.observedTaskRotation(
                    orientation, request, physicalRotation);
            recordTaskWindowState(activity, request, rotation, rotation, 1, source);
        } catch (Throwable error) {
            HostEntry.log("pre-hold task state unavailable: " + error);
        }
    }

    static void releaseAllTaskRotationsLocked(String reason) {
        boolean wasUpdating = HostEntry.sUpdatingTaskRotation;
        HostEntry.sUpdatingTaskRotation = true;
        try {
            for (Object activity : new java.util.ArrayList<>(HostEntry.sTaskRotations.keySet())) {
                releaseTaskRotationLocked(activity, reason);
            }
        } finally {
            HostEntry.sUpdatingTaskRotation = wasUpdating;
        }
    }

    void refreshMiStepTaskRotationLocked(Object display) {
        Object top = XposedHelpers.callMethod(display, "topRunningActivity");
        for (Object activity : new java.util.ArrayList<>(HostEntry.sTaskRotations.keySet())) {
            if (activity != top) {
                applyMiStepFixedRotationLocked(display, activity, "side-rotation-policy");
            }
        }
        applyMiStepFixedRotationLocked(display, top, "system-rotation-policy");
    }

    boolean isSideFixedTask(Object activity) {
        try {
            Object task = XposedHelpers.callMethod(activity, "getTask");
            return MiStepSideRoleChange.isSide(task);
        } catch (Throwable error) {
            HostEntry.log("fixed rotation side-state lookup failed: " + error);
            return false;
        }
    }

    static boolean isMiStepSideTask(Object task) {
        try {
            return Integer.valueOf(6).equals(XposedHelpers.callMethod(
                            task, "getWindowingMode"))
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(
                            task, "isForceTranslucent"));
        } catch (Throwable error) {
            return false;
        }
    }

    boolean isSystemRotationLocked(Object display) {
        try {
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            try {
                // The target's native getter reads ACCELEROMETER_ROTATION for USER_CURRENT,
                // including the active HyperOS profile/display policy.
                return Boolean.TRUE.equals(XposedHelpers.callMethod(
                        displayRotation, "isRotationFrozen"));
            } catch (Throwable ignored) { }
            try {
                return Integer.valueOf(1).equals(XposedHelpers.callMethod(
                        displayRotation, "getUserRotationMode"));
            } catch (Throwable ignored) { }
        } catch (Throwable error) {
            HostEntry.log("fixed rotation mode lookup unavailable: " + error);
        }
        try {
            Context context = (Context) HostEntry.sSystemContext;
            if (context != null) {
                return Integer.valueOf(0).equals(XposedHelpers.callStaticMethod(
                        android.provider.Settings.System.class, "getIntForUser",
                        context.getContentResolver(),
                        android.provider.Settings.System.ACCELEROMETER_ROTATION, 0, -2));
            }
        } catch (Throwable error) {
            HostEntry.log("fixed rotation setting lookup unavailable: " + error);
        }
        // Do not turn an unavailable policy query into permission to use a sensor.
        return true;
    }

    static void beginFixedMainRotationLocked(Object display) {
        if (HostEntry.sFullscreenRotationActive || display == null) return;
        HostEntry.sTaskWindowStates.clear();
        // Capture the application's real direction before the workspace's portrait
        // configuration can overwrite it, including manual fullscreen video under lock.
        captureTaskWindowState(display, XposedHelpers.callMethod(display, "topRunningActivity"),
                "before-workspace-portrait");
        releaseAllTaskRotationsLocked("new Mi Step session");
        HostEntry.sHeldDisplayRotation = FullscreenRotationPolicy.portraitDisplayRotation();
        HostEntry.sFullscreenRotationActive = true;
        HostEntry.log("fixed rotation active; Mi Step display held at portrait rotation "
                + HostEntry.sHeldDisplayRotation);
        recomputeDisplayOrientationLocked(display, "enter Mi Step");
    }

    static void endFixedMainRotationLocked(Object display) {
        HostEntry.sMainImeProcessConfiguration.clear(HostEntry::log);
        HostEntry.sMainSystemUiRotation.clear(HostEntry::log);
        publishMainUiConfiguration(null);
        if (!HostEntry.sFullscreenRotationActive) {
            HostEntry.sMainStatusBarClip.clear(HostEntry::log);
            releaseAllTaskRotationsLocked("inactive cleanup");
            return;
        }
        releaseAllTaskRotationsLocked("host exit");
        HostEntry.sFullscreenRotationActive = false;
        HostEntry.sMainStatusBarClip.clear(HostEntry::log);
        recomputeDisplayOrientationLocked(display, "exit Mi Step");
    }

    static void recomputeDisplayOrientationLocked(Object display, String reason) {
        try {
            boolean changed = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    display, "updateOrientation"));
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            // updateOrientation alone can early-return when the top app has not changed.
            // Re-run WM's regular rotation pipeline, including when entering from landscape.
            changed |= Boolean.TRUE.equals(XposedHelpers.callMethod(
                    displayRotation, "updateRotationUnchecked", true));
            if (changed) {
                XposedHelpers.callMethod(display, "sendNewConfiguration");
            }
        } catch (Throwable error) {
            HostEntry.log("fixed rotation: orientation refresh unavailable on " + reason + ": " + error);
        }
    }

}
