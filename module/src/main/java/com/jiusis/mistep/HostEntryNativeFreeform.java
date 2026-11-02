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

/** 原生 freeform 共存簇：层级压制 / 触摸排除 / pin 管理 / 启动收尾 */
final class HostEntryNativeFreeform {
    static final HostEntryNativeFreeform INSTANCE = new HostEntryNativeFreeform();

    private HostEntryNativeFreeform() {
    }

    static void finishNativeFreeformStart(HostEntry.ZoomTarget target, Object start) {
        synchronized (target.globalLock) {
            if (!HostEntry.sNativeFreeformStart.finish(target.display, start) || target != HostEntry.sZoomTarget) return;
            try {
                if (HostEntry.sZoomed) {
                    Object transaction = XposedHelpers.callMethod(target.display, "getPendingTransaction");
                    XposedHelpers.callMethod(target.display, "reapplyMagnificationSpec", transaction);
                    XposedHelpers.callMethod(transaction, "apply");
                } else HostEntry.restoreExitedMagnificationLocked(target);
                HostEntry.log("native freeform start committed; normal magnification writes resumed");
            } catch (Throwable error) { HostEntry.log("native freeform committed handoff skipped: " + error); }
        }
    }

    void installNativeFreeformCoexistence(ClassLoader cl) {
        installNativeFreeformTouchExclusion(cl);
        installNativeFreeformPin(cl);
        try {
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(display, "assignChildLayers", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject != HostEntry.sDisplayContent || p.args.length != 1
                            || (!HostEntry.sZoomed && HostEntry.sNativeFreeformHostLayers.isEmpty())) return;
                    try { placeHostBelowNativeFreeforms(p.thisObject, p.args[0]); }
                    catch (Throwable error) { HostEntry.log("native freeform layering skipped: " + error); }
                }
            });
            HostEntry.log("native freeform inherits main zoom and layers above MiStepHost");
        } catch (Throwable error) { HostEntry.log("native freeform coexistence hook unavailable: " + error); }
    }

    static void installNativeFreeformPin(ClassLoader cl) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.android.server.wm.MiuiFreeFormManagerService", cl);
            try {
                HostEntry.sNativeFreeformPinManager = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                        "com.android.server.wm.MiuiFreeFormManagerServiceStub", cl), "getInstance");
            } catch (Throwable notCreatedYet) { /* The first pin decision supplies the live instance. */ }
            XposedHelpers.findAndHookMethod(manager, "shouldForegroundPin", int.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    HostEntry.sNativeFreeformPinManager = p.thisObject;
                    if (Boolean.TRUE.equals(p.getResult()))
                        releaseNativeForegroundPinLayers(p.thisObject, (Integer) p.args[0]);
                }
            });
            HostEntry.log("native foreground pin releases host layers before Shell hides task");
        } catch (Throwable error) { HostEntry.log("native foreground pin hook unavailable: " + error); }
    }

    static boolean isNativeForegroundPin(Object task) {
        Object manager = HostEntry.sNativeFreeformPinManager != null
                ? HostEntry.sNativeFreeformPinManager : HostEntry.sNativeTouchExclusionManager;
        return manager != null && Boolean.TRUE.equals(XposedHelpers.callMethod(manager,
                "getMiuiFreeformIsForegroundPin", XposedHelpers.getIntField(task, "mTaskId")));
    }

    static void releaseNativeForegroundPinLayers(Object manager, int taskId) {
        HostEntry.sNativeFreeformPinManager = manager;
        HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
        if (!HostEntry.sZoomed || target == null) return;
        synchronized (target.globalLock) {
            if (!HostEntry.sZoomed || target != HostEntry.sZoomTarget || HostEntry.sNativeFreeformHostLayers.isEmpty()) return;
            try {
                // The ROM has just stored mIsForegroundPin, but pin2/3 need not
                // assign child layers. Clear BOTH live and pending dependencies
                // synchronously, before the Binder reply permits Shell hideTask.
                Object transaction = Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor().newInstance();
                try {
                    placeHostBelowNativeFreeforms(target.display, transaction);
                    XposedHelpers.callMethod(transaction, "apply");
                } finally { XposedHelpers.callMethod(transaction, "close"); }
                HostEntry.log("native foreground pin layers reconciled task=" + taskId);
            } catch (Throwable error) { HostEntry.log("native foreground pin layer release failed: " + error); }
        }
    }

    static void installNativeFreeformTouchExclusion(ClassLoader cl) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.android.server.wm.MiuiFreeFormManagerService", cl);
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedHelpers.findAndHookMethod(manager, "updateMiuiFreeformTouchExcludeRegion", display,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    HostEntry.sNativeTouchExclusionManager = p.thisObject;
                    p.setObjectExtra("mistepPreviousExcludeDisplay", HostEntry.sNativeTouchExclusionDisplay.get());
                    HostEntry.sNativeTouchExclusionDisplay.set(p.args[0]);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object previous = p.getObjectExtra("mistepPreviousExcludeDisplay");
                    if (previous == null) HostEntry.sNativeTouchExclusionDisplay.remove();
                    else HostEntry.sNativeTouchExclusionDisplay.set(previous);
                }
            });
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "getTouchableRegion", android.graphics.Region.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object currentDisplay = HostEntry.sNativeTouchExclusionDisplay.get();
                    if (currentDisplay == null || p.hasThrowable()) return;
                    HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                    if (target == null || !Thread.holdsLock(target.globalLock)) return;
                    HostEntry.NativeHostLayer state = HostEntry.sNativeFreeformHostLayers.get(p.thisObject);
                    try {
                        boolean sameSurfaceAndAnchor = state != null
                                && state.nativeAnchor != null
                                && state.nativeAnchor == HostEntry.sNativeFreeformLayerAnchor
                                && state.surface == XposedHelpers.callMethod(p.thisObject, "getSurfaceControl");
                        if (NativeFreeformPolicy.omitFromTouchExclusion(HostEntry.sZoomed,
                                currentDisplay == target.display
                                && currentDisplay == XposedHelpers.callMethod(p.thisObject, "getDisplayContent"),
                                state != null, sameSurfaceAndAnchor)) {
                            // Only OEM's exclusion-union scratch Region changes. Its ordinary
                            // InputDispatcher region and all other windows retain native behavior.
                            ((android.graphics.Region) p.args[0]).setEmpty();
                        }
                    } catch (Throwable error) { HostEntry.log("native touch exclusion skipped: " + error); }
                }
            });
            HostEntry.log("native touch exclusion follows scoped MiStep relative layers");
        } catch (Throwable error) { HostEntry.log("native touch exclusion hook unavailable: " + error); }
    }

    static void placeHostBelowNativeFreeforms(Object display, Object transaction) {
        boolean touchExclusionChanged = false;
        java.util.List<Object> hosts = new java.util.ArrayList<>();
        Object[] lowestFreeform = {null};
        Consumer<Object> pickHost = window -> {
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                    XposedHelpers.getObjectField(window, "mAttrs");
            String title = String.valueOf(attrs.getTitle());
            boolean hostOverlay = ("MiStepHost".equals(title)
                    || "MiStepTransition".equals(title) || "MiStepMirrorLayer".equals(title)
                    || "MiStepClipboard".equals(title))
                    && attrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            // This status bar has been magnified into the main region. Keep it
            // with main chrome, not above an OEM freeform crossing that region.
            boolean mainStatusBar = HostEntry.sZoomed && attrs.type == 2000 && "StatusBar".equals(title);
            if (HostEntry.SYSTEMUI.equals(attrs.packageName) && (hostOverlay || mainStatusBar)
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(window, "isVisible"))) hosts.add(window);
        };
        XposedHelpers.callMethod(display, "forAllWindows", pickHost, false);
        Consumer<Object> pickFreeform = task -> {
            if (!HostEntry.sZoomed || lowestFreeform[0] != null || (!Integer.valueOf(5).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))
                    && !NativeFreeformHandoff.keepNativeTransform(task, false))
                    || XposedHelpers.callMethod(task, "getRootTask") != task
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisible"))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisibleRequested"))) return;
            // Foreground pin retains WM visibility while Shell hides its canonical
            // surface. Relative children would otherwise disappear with that task.
            if (isNativeForegroundPin(task)) return;
            Object surface = XposedHelpers.callMethod(task, "getSurfaceControl");
            if (surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
                lowestFreeform[0] = surface;
        };
        XposedHelpers.callMethod(display, "forAllTasks", pickFreeform, false);
        if (HostEntry.sNativeFreeformLayerAnchor != lowestFreeform[0]) {
            // A newly excluded leaf does not undo a spec already on its shared ancestor.
            // Re-split that ancestor in this same WM transaction before showing freeform.
            if (HostEntry.sZoomed) XposedHelpers.callMethod(display, "reapplyMagnificationSpec", transaction);
            HostEntry.sNativeFreeformLayerAnchor = lowestFreeform[0];
            HostEntry.log("native freeform anchor changed present=" + (lowestFreeform[0] != null)
                    + " hostWindows=" + hosts.size());
            HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
            if (target != null && target.display == display) target.handler.post(() -> {
                synchronized (target.globalLock) {
                    if (target == HostEntry.sZoomTarget)
                        HostEntry.applyGestureSurfacesLocked(target, HostEntry.sZoomScale, HostEntry.sZoomOffset, HostEntry.sZoomOffsetX);
                }
            });
        }

        java.util.Iterator<java.util.Map.Entry<Object, HostEntry.NativeHostLayer>> entries =
                HostEntry.sNativeFreeformHostLayers.entrySet().iterator();
        while (entries.hasNext()) {
            java.util.Map.Entry<Object, HostEntry.NativeHostLayer> entry = entries.next();
            Object host = entry.getKey();
            HostEntry.NativeHostLayer state = entry.getValue();
            if (XposedHelpers.callMethod(host, "getSurfaceControl") != state.surface
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(state.surface, "isValid"))) {
                entries.remove();
                touchExclusionChanged = true;
                continue;
            }
            if (lowestFreeform[0] != null && hosts.contains(host)) continue;
            rememberNativeHostNormalLayer(host, state);
            restoreNativeHostLayer(host, state, transaction);
            Object pending = XposedHelpers.callMethod(display, "getPendingTransaction");
            if (pending != transaction) restoreNativeHostLayer(host, state, pending);
            entries.remove();
            touchExclusionChanged = true;
        }
        if (lowestFreeform[0] != null) {
            for (Object host : hosts) {
                Object surface = XposedHelpers.callMethod(host, "getSurfaceControl");
                if (surface == null || !Boolean.TRUE.equals(
                        XposedHelpers.callMethod(surface, "isValid"))) continue;
                HostEntry.NativeHostLayer state = HostEntry.sNativeFreeformHostLayers.get(host);
                if (state == null || state.surface != surface) {
                    state = new HostEntry.NativeHostLayer(surface);
                    HostEntry.sNativeFreeformHostLayers.put(host, state);
                }
                rememberNativeHostNormalLayer(host, state);
                WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                        XposedHelpers.getObjectField(host, "mAttrs");
                int layer = attrs.type == 2000 ? -5
                        : "MiStepClipboard".contentEquals(attrs.getTitle()) ? -1
                        : "MiStepTransition".contentEquals(attrs.getTitle()) ? -2
                        : "MiStepMirrorLayer".contentEquals(attrs.getTitle()) ? -3 : -4;
                if (state.nativeAnchor != lowestFreeform[0]) touchExclusionChanged = true;
                XposedHelpers.callMethod(host, "assignRelativeLayer", transaction,
                        lowestFreeform[0], layer, true);
                state.nativeAnchor = lowestFreeform[0];
                state.nativeLayer = layer;
            }
        }
        if (touchExclusionChanged && HostEntry.sNativeTouchExclusionManager != null) {
            try {
                XposedHelpers.callMethod(HostEntry.sNativeTouchExclusionManager,
                        "updateMiuiFreeformTouchExcludeRegion", display);
            } catch (Throwable error) { HostEntry.log("native touch exclusion refresh skipped: " + error); }
        }
    }

    static void rememberNativeHostNormalLayer(Object host, HostEntry.NativeHostLayer state) {
        int currentLayer = XposedHelpers.getIntField(host, "mLastLayer");
        Object currentRelative = XposedHelpers.getObjectField(host, "mLastRelativeToLayer");
        if (state.nativeAnchor == null || currentRelative != state.nativeAnchor
                || currentLayer != state.nativeLayer) {
            state.normalLayer = currentLayer;
            state.normalRelativeTo = currentRelative;
        }
    }

    static void restoreNativeHostLayer(Object host, HostEntry.NativeHostLayer state, Object transaction) {
        // assignLayer may return early while WM builds transition layers. Use its
        // SurfaceAnimator setter for our own window, preserving any animation leash.
        // Never change the OEM task's parent, layer, visibility or transform.
        if (state.normalRelativeTo == null) {
            XposedHelpers.callMethod(host, "setLayer", transaction, state.normalLayer);
        } else {
            XposedHelpers.callMethod(host, "setRelativeLayer", transaction,
                    state.normalRelativeTo, state.normalLayer);
        }
        XposedHelpers.setIntField(host, "mLastLayer", state.normalLayer);
        XposedHelpers.setObjectField(host, "mLastRelativeToLayer", state.normalRelativeTo);
    }

}
