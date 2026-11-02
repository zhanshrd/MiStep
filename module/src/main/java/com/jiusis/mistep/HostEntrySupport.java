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

/** 系统服务钩子 / 触发器与调试广播 / TaskView 装载与启动 / 小工具 */
final class HostEntrySupport {
    static final HostEntrySupport INSTANCE = new HostEntrySupport();

    private HostEntrySupport() {
    }

    void hookSystemServer(ClassLoader cl) {
        HostEntry.log("=== attached to system_server (android) build=1.2.0 release ===");
        MiStepRecents.install(cl);
        NativeRawInputBridge.install();
        try {
            Class<?> dc = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(dc, "configureSurfaces", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    try {
                        Object type = XposedHelpers.callMethod(self, "getDisplayId");
                        if (type instanceof Integer && ((Integer) type).intValue() != 0) {
                            return;
                        }
                        HostEntry.sDisplayContent = self;
                        Object service = XposedHelpers.getObjectField(self, "mWmService");
                        Object lock = XposedHelpers.getObjectField(service, "mGlobalLock");
                        Object handler = XposedHelpers.getObjectField(service, "mH");
                        if (!(handler instanceof Handler)) {
                            throw new IllegalStateException("WMS.mH is not a Handler");
                        }
                        // configureSurfaces creates a new display surface even for the same DC.
                        // New identity invalidates deduplication after a real surface rebuild.
                        HostEntry.cancelZoomAnimation(null);
                        HostEntry.sZoomAnimKey = null;
                        HostEntry.sZoomTarget = new HostEntry.ZoomTarget(self, lock, (Handler) handler);
                        HostEntry.armZoomBridge(service);
                        HostEntry.dispatchCurrentZoom();
                        HostEntry.log("captured DisplayContent(display 0) with WM handler/global lock -> " + self);
                    } catch (Throwable t) {
                        HostEntry.sZoomTarget = null;
                        HostEntry.log("capture DisplayContent failed: " + t);
                    }
                }
            });
            HostEntry.log("DisplayContent#configureSurfaces hooked");
        } catch (Throwable t) {
            HostEntry.log("hookSystemServer hook failed: " + t);
            return;
        }
        MiStepRecentsInput.install(cl, () -> HostEntry.sZoomed
                ? new float[]{HostEntry.sZoomScale, HostEntry.sZoomOffset, HostEntry.sZoomOffsetX} : null);
        MiStepWallpaperScale.install(cl, () -> {
            HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
            return HostEntry.sZoomed && target != null && target.display == HostEntry.sDisplayContent
                    ? target.display : null;
        });
        installMagnificationSurfaceTracking(cl);
        HostEntry.hookMagnificationReplayAfterTransition(cl);
        HostEntry.installNativeFreeformCoexistence(cl);
        HostEntry.installFixedMainRotationHooks(cl);
        HostEntry.installMainSystemUiRotation(cl);
        HostEntry.installFixedMainTouchableCropHook(cl);
        installSideSystemBarPolicy(cl);
        installWorkspaceFocus(cl);
        installWorkspaceRunningTasks(cl);
        // Use WM's own visual AND input-window magnification path for system input UI.
        // The target normally excludes IME and conditionally excludes the navigation bar.
        // Do not change accessibility settings or pretend that a mouse is connected.
        try {
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "shouldMagnify", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!HostEntry.sZoomed || HostEntry.sDisplayContent == null) return;
                    try {
                        if (XposedHelpers.callMethod(p.thisObject, "getDisplayContent") != HostEntry.sDisplayContent) return;
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                                XposedHelpers.getObjectField(p.thisObject, "mAttrs");
                        if (task == null && (attrs.type == 2011 || attrs.type == 2012)) {
                            Object target = XposedHelpers.callMethod(HostEntry.sDisplayContent, "getImeInputTarget");
                            Object window = target == null ? null
                                    : XposedHelpers.callMethod(target, "getWindowState");
                            task = window == null ? null : XposedHelpers.callMethod(window, "getTask");
                        }
                        // Native freeforms inherit the same WM parent transform as main apps.
                        // Their canonical leash retains the OEM's own mini/freeform scale.
                        int flags = XposedHelpers.getIntField(attrs, "privateFlags");
                        // Native gesture windows keep the ROM's own magnification policy.
                        if (InputMagnificationPolicy.shouldForceMagnification(attrs.type, flags,
                                attrs.packageName, attrs.getTitle()))
                            p.setResult(Boolean.TRUE);
                    } catch (Throwable error) { HostEntry.log("input UI magnification skipped: " + error); }
                }
            });
            HostEntry.log("scoped IME/navigation magnification admitted on default display");
        } catch (Throwable error) { HostEntry.log("input UI magnification unavailable: " + error); }
        try {
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "canBeImeLayeringTarget",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!HostEntry.sZoomed || !Boolean.TRUE.equals(p.getResult())) return;
                    try {
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        if (task != null
                                && Integer.valueOf(6).equals(XposedHelpers.callMethod(
                                        task, "getWindowingMode"))
                                && Boolean.TRUE.equals(XposedHelpers.callMethod(
                                        task, "isForceTranslucent"))) {
                            p.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable error) {
                        HostEntry.log("side IME exclusion skipped: " + error);
                    }
                }
            });
            HostEntry.log("side TaskViews excluded from IME layering while zoomed");
        } catch (Throwable error) { HostEntry.log("side IME exclusion unavailable: " + error); }
        try {
            Class<?> spy = XposedHelpers.findClass("com.android.server.input.GestureMonitorSpyWindow", cl);
            XposedBridge.hookAllConstructors(spy, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length != 7 || !Integer.valueOf(0).equals(p.args[2])) return;
                    String name = String.valueOf(p.args[1]);
                    try {
                        Context context = (Context) HostEntry.sSystemContext;
                        String[] packages = context == null ? null
                                : context.getPackageManager().getPackagesForUid((Integer) p.args[4]);
                        String kind = NativeFreeformPolicy.gestureKind((Integer) p.args[2],
                                name, (Integer) p.args[4], packages);
                        if (kind == null) return;
                        Object surface = XposedHelpers.getObjectField(p.thisObject, "mInputSurface");
                        synchronized (HostEntry.sGestureSurfaces) {
                            HostEntry.sGestureSurfaces.put(surface, kind);
                        }
                        HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                        if (target != null) target.handler.post(() -> {
                            synchronized (target.globalLock) {
                                if (HostEntry.sZoomTarget == target)
                                    HostEntry.applyGestureSurfacesLocked(target, HostEntry.sZoomScale,
                                            HostEntry.sZoomOffset, HostEntry.sZoomOffsetX);
                            }
                        });
                        HostEntry.log("captured gesture monitor name=" + name + " pid=" + p.args[3]
                                + " uid=" + p.args[4] + " packages="
                                + (packages == null ? "unknown" : java.util.Arrays.toString(packages)));
                        if ("return".equals(kind)) {
                            StackTraceElement[] frames = Thread.currentThread().getStackTrace();
                            StringBuilder callers = new StringBuilder();
                            for (int i = 1; i < frames.length && i < 9; i++) {
                                if (callers.length() > 0) callers.append(" <- ");
                                callers.append(frames[i].getClassName()).append('.')
                                        .append(frames[i].getMethodName());
                            }
                            HostEntry.log("return gesture monitor creator " + callers);
                        }
                    } catch (Throwable error) { HostEntry.log("navigation surface admission skipped: " + error); }
                }
            });
        } catch (Throwable error) { HostEntry.log("navigation input geometry unavailable: " + error); }
        // Keep the non-resizable override inside Mi Step-owned side tasks. A process-wide
        // override also changes Home and unrelated windowing decisions.
        try {
            Class<?> taskCls = XposedHelpers.findClass("com.android.server.wm.Task", cl);
            XposedBridge.hookAllMethods(taskCls, "isResizeable", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!HostEntry.sFullscreenRotationActive) return;
                    try {
                        if (HostEntry.isMiStepSideTask(param.thisObject)) param.setResult(Boolean.TRUE);
                    } catch (Throwable error) {
                        HostEntry.log("scoped resizeability check skipped: " + error);
                    }
                }
            });
            HostEntry.log("Task#isResizeable override scoped to active Mi Step side tasks");
        } catch (Throwable t) {
            HostEntry.log("Task#isResizeable hook failed: " + t);
        }
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", cl);
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            Object ctx = thread == null ? null
                    : XposedHelpers.callMethod(thread, "getSystemContext");
            HostEntry.sSystemContext = ctx;
            HostEntry.log("system context -> " + ctx);
        } catch (Throwable t) {
            HostEntry.log("system context failed: " + t);
        }
        installDesktopIconLaunchBridge(cl);
        // 文件只作备用；所有通道共享有时限的最新请求，读取失败不能撤销有效广播。
        //   mistep-zoom 内容 "scale offsetY offsetX"（如 "0.7472 622 273"），"1 0 0" = 还原；
        //   动画行为 "anim <scale> <offsetY> <offsetX> <durationMs>"，从当前 spec 起步。
        // SystemUI 私有文件用 FileObserver 即时触发（宿主自己写，用于呼出动画与后续交换动画）；
        // 250ms 轮询只作兜底，并保留 adb/root 写 /data/system/mistep-zoom 的调试通道。
        try {
            Thread watcher = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            java.io.File f1 = new java.io.File("/data/system/mistep-zoom");
                            java.io.File f2 = new java.io.File(
                                    "/data/user_de/0/com.android.systemui/files/mistep-zoom");
                            java.io.File f = f1;
                            if (f2.exists() && (!f1.exists()
                                    || f2.lastModified() > f1.lastModified())) {
                                f = f2;
                            }
                            HostEntry.readAndDispatchZoom(f);
                            Thread.sleep(250L);
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Throwable t) {
                            try {
                                Thread.sleep(2000L);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            }, "mistep-zoom-watch");
            watcher.setDaemon(true);
            watcher.start();
            HostEntry.log("ZOOM lease watcher armed; transforms only on locked WMS handler");
        } catch (Throwable t) {
            HostEntry.log("ZOOM watcher failed: " + t);
        }
        HostEntry.installZoomFileObserver();
    }

    static void transferSideTouch(Intent intent) {
        HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
        android.os.Bundle tokens = intent.getBundleExtra("tokens");
        if (target == null || tokens == null) return;
        android.os.IBinder hostToken = tokens.getBinder("host");
        android.os.IBinder taskToken = tokens.getBinder("task");
        int taskId = intent.getIntExtra("taskId", -1);
        long eventTime = intent.getLongExtra("eventTime", 0L);
        target.handler.post(() -> {
            long age = android.os.SystemClock.uptimeMillis() - eventTime;
            if (HostEntry.sZoomTarget != target || HostEntry.sZoomScale >= 1f || age < 0 || age > 500
                    || hostToken == null || taskToken == null || taskId < 0) return;
            try {
                Object from;
                Object to;
                Object input;
                synchronized (target.globalLock) {
                    Object service = XposedHelpers.getObjectField(target.display, "mWmService");
                    java.util.Map<?, ?> windows = (java.util.Map<?, ?>)
                            XposedHelpers.getObjectField(service, "mWindowMap");
                    Object host = windows.get(hostToken);
                    if (host == null) return;
                    WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                            XposedHelpers.getObjectField(host, "mAttrs");
                    if (!"MiStepHost".contentEquals(attrs.getTitle())
                            || !HostEntry.SYSTEMUI.equals(attrs.packageName)
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(host, "isVisible"))) return;
                    Class<?> container = XposedHelpers.findClass(
                            "com.android.server.wm.WindowContainer", target.display.getClass().getClassLoader());
                    Object task = XposedHelpers.callStaticMethod(container, "fromBinder", taskToken);
                    if (task == null || XposedHelpers.getIntField(task, "mTaskId") != taskId
                            || !Integer.valueOf(6).equals(XposedHelpers.callMethod(task, "getWindowingMode"))
                            || XposedHelpers.callMethod(task, "getDisplayContent") != target.display) return;
                    Object[] app = {null};
                    Consumer<Object> pick = window -> {
                        if (app[0] != null) return;
                        WindowManager.LayoutParams wa = (WindowManager.LayoutParams)
                                XposedHelpers.getObjectField(window, "mAttrs");
                        if ((wa.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                                || wa.type == WindowManager.LayoutParams.TYPE_APPLICATION)
                                && Boolean.TRUE.equals(XposedHelpers.callMethod(window, "isVisible"))
                                && XposedHelpers.getObjectField(window, "mInputChannelToken") != null) {
                            app[0] = window;
                        }
                    };
                    XposedHelpers.callMethod(task, "forAllWindows", pick, true);
                    if (app[0] == null) return;
                    from = XposedHelpers.getObjectField(host, "mInputChannelToken");
                    to = XposedHelpers.getObjectField(app[0], "mInputChannelToken");
                    input = XposedHelpers.getObjectField(service, "mInputManager");
                }
                if (from == null || to == null) return;
                Object result;
                try {
                    result = XposedHelpers.callMethod(input, "transferTouchGesture", from, to, true);
                } catch (NoSuchMethodError olderApi) {
                    result = XposedHelpers.callMethod(input, "transferTouchGesture", from, to);
                }
                HostEntry.log("side touch transferred task=" + taskId + " result=" + result + " age=" + age);
            } catch (Throwable error) {
                HostEntry.log("side touch transfer failed task=" + taskId + ": " + error);
            }
        });
    }

    void installMagnificationSurfaceTracking(ClassLoader cl) {
        try {
            Class<?> container = XposedHelpers.findClass("com.android.server.wm.WindowContainer", cl);
            Class<?> transaction = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
            Class<?> spec = XposedHelpers.findClass("android.view.MagnificationSpec", cl);
            XposedHelpers.findAndHookMethod(container, "applyMagnificationSpec", transaction,
                    spec, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                    if (!HostEntry.sZoomed || target == null || target.display != HostEntry.sDisplayContent
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        boolean finalSnapshot = Boolean.TRUE.equals(HostEntry.sFinalMagnificationSnapshot.get());
                        // Even an empty concurrent transition builds a display-wide finish.
                        // Only Home's own delayed finish may restore children below its Root.
                        boolean homeFinish = finalSnapshot && HomeTransitionMagnification.hasInfo(
                                HostEntry.sMagnificationFinishInfo.get());
                        if (!homeFinish && HomeTransitionMagnification.owns(
                                p.thisObject, target.display)) {
                            p.setObjectExtra("mistep.sideMagnification", Boolean.TRUE);
                            p.setResult(null);
                            return;
                        }
                        if (MiStepSideMagnification.ownsGeometry(p.thisObject, target.display)) {
                            p.setObjectExtra("mistep.sideMagnification", Boolean.TRUE);
                            p.setResult(null);
                            return;
                        }
                        p.args[0] = HostEntry.sNativeFreeformStart.route(p.thisObject, target.display,
                                p.args[0], Boolean.TRUE.equals(HostEntry.sFinalMagnificationSnapshot.get()));
                        p.args[0] = HostEntry.sLiveSwapZoomSurfaces.route(p.thisObject, target.display,
                                p.args[0], Boolean.TRUE.equals(HostEntry.sFinalMagnificationSnapshot.get()));
                        java.util.Map<Object, Object> tracked = HostEntry.sMagnifiedSurfaces;
                        if (!homeFinish && HomeTransitionMagnification.activeOn(target.display)) {
                            tracked = new java.util.IdentityHashMap<>(tracked);
                            tracked.entrySet().removeIf(entry ->
                                    HomeTransitionMagnification.owns(entry.getKey(), target.display));
                        }
                        MiStepMagnificationTree.prepare(p.thisObject, target.display,
                                p.args[0], p.args[1], tracked);
                    } catch (Throwable error) {
                        HostEntry.log("magnification replay surface cleanup skipped: " + error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()
                            && !Boolean.TRUE.equals(p.getObjectExtra("mistep.sideMagnification")))
                        HostEntry.rememberMagnifiedSurface(p.thisObject, p.args[1]);
                }
            });
            XposedHelpers.findAndHookMethod(container, "clearMagnificationSpec", transaction,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                    if (!HostEntry.sZoomed || target == null || target.display != HostEntry.sDisplayContent
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        // Native clear recurses before apply. Skipping apply alone lets
                        // an older WM spec reset the TaskView leash to identity first.
                        boolean homeFinish = Boolean.TRUE.equals(HostEntry.sFinalMagnificationSnapshot.get())
                                && HomeTransitionMagnification.hasInfo(HostEntry.sMagnificationFinishInfo.get());
                        if (!homeFinish
                                && HomeTransitionMagnification.owns(p.thisObject, target.display)) {
                            p.setResult(null);
                            return;
                        }
                        if (MiStepSideMagnification.ownsGeometry(p.thisObject, target.display))
                            p.setResult(null);
                    } catch (Throwable error) {
                        HostEntry.log("side magnification clear guard unavailable: " + error);
                    }
                }
            });
            HostEntry.log("WM magnification surface identities tracked for exit reconciliation");
        } catch (Throwable error) {
            HostEntry.log("WM magnification surface tracking unavailable: " + error);
        }
    }

    void installWorkspaceRunningTasks(ClassLoader cl) {
        try {
            Class<?> running = XposedHelpers.findClass("com.android.server.wm.RunningTasks", cl);
            Class<?> taskClass = XposedHelpers.findClass("com.android.server.wm.Task", cl);
            // The ROM passes the original caller at argument 5, before clearing Binder identity.
            for (java.lang.reflect.Method method : running.getDeclaredMethods()) {
                if (!method.getName().equals("getTasks") || method.getParameterCount() != 7
                        || method.getParameterTypes()[5] != int.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        p.setObjectExtra("mistepPreviousTaskQuery", HostEntry.sWorkspaceLauncherTaskQuery.get());
                        boolean launcher = false;
                        try {
                            Context context = (Context) HostEntry.sSystemContext;
                            String[] packages = context == null ? null : context.getPackageManager()
                                    .getPackagesForUid((Integer) p.args[5]);
                            launcher = packages != null
                                    && java.util.Arrays.asList(packages).contains("com.miui.home");
                        } catch (Throwable unavailable) { /* Preserve native queries on lookup failure. */ }
                        HostEntry.sWorkspaceLauncherTaskQuery.set(HostEntry.sZoomed && launcher);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Boolean previous = (Boolean) p.getObjectExtra("mistepPreviousTaskQuery");
                        if (previous == null) HostEntry.sWorkspaceLauncherTaskQuery.remove();
                        else HostEntry.sWorkspaceLauncherTaskQuery.set(previous);
                    }
                });
            }
            XposedHelpers.findAndHookMethod(running, "accept", taskClass, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!HostEntry.sZoomed || !Boolean.TRUE.equals(HostEntry.sWorkspaceLauncherTaskQuery.get())) return;
                    try {
                        Object task = p.args[0];
                        if (XposedHelpers.callMethod(task, "getDisplayContent") != HostEntry.sDisplayContent) return;
                        Object lease = MiStepDesktopLaunchBridge.recentsLease(task);
                        if (lease != null && MiStepDesktopLaunchBridge.recentsLeaseAlive(lease))
                            p.setResult(null);
                    } catch (Throwable unavailable) { /* An uncertain task remains a native candidate. */ }
                }
            });
            HostEntry.log("workspace Launcher running-task scan skips exact side leases before maxNum");
        } catch (Throwable unavailable) { HostEntry.log("workspace running-task hook unavailable: " + unavailable); }
    }

    void installWorkspaceFocus(ClassLoader cl) {
        try {
            Class<?> area = XposedHelpers.findClass("com.android.server.wm.TaskDisplayArea", cl);
            XC_MethodHook selection = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!HostEntry.sZoomed || p.hasThrowable() || p.getResult() == null
                            || Boolean.TRUE.equals(HostEntry.sReadingWorkspaceFocus.get())) return;
                    HostEntry.sReadingWorkspaceFocus.set(Boolean.TRUE);
                    try {
                        Object original = p.getResult();
                        Object lease = MiStepDesktopLaunchBridge.recentsLease(original);
                        boolean upperFallback="getNextFocusableRootTask".equals(p.method.getName()) && HostEntry.isUpperLandscapeTask(original);
                        if ((!upperFallback && (lease == null || !MiStepDesktopLaunchBridge.recentsLeaseAlive(lease)))
                                || XposedHelpers.callMethod(original, "getDisplayContent") != HostEntry.sDisplayContent) return;
                        Object ignored = p.args.length >= 2 && Boolean.TRUE.equals(p.args[1]) ? p.args[0] : null;
                        boolean excludeMode = p.args.length == 4 && Boolean.TRUE.equals(p.args[2]);
                        int mode = excludeMode ? (Integer) p.args[3] : 0;
                        Object target = WorkspaceFocus.next(p.thisObject, ignored, excludeMode, mode, task -> {
                            Object binding = MiStepDesktopLaunchBridge.recentsLease(task);
                            return HostEntry.isUpperLandscapeTask(task)
                                    || binding != null && MiStepDesktopLaunchBridge.recentsLeaseAlive(binding);
                        });
                        // No replacement means WM keeps its native fallback; never return a hidden task.
                        if (target != null) p.setResult(target);
                    } catch (Throwable unavailable) {
                        HostEntry.log("workspace focus selection unavailable: " + unavailable);
                    } finally { HostEntry.sReadingWorkspaceFocus.remove(); }
                }
            };
            XposedBridge.hookAllMethods(area, "getFocusedRootTask", selection);
            XposedBridge.hookAllMethods(area, "getNextFocusableRootTask", selection);
            HostEntry.log("workspace native focus selection skips exact side-task leases");
        } catch (Throwable unavailable) { HostEntry.log("workspace focus hook unavailable: " + unavailable); }
    }

    void installSideSystemBarPolicy(ClassLoader cl) {
        try {
            Class<?> windowState = XposedHelpers.findClass(
                    "com.android.server.wm.WindowState", cl);
            XposedBridge.hookAllMethods(windowState, "getSystemUiVisibility",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!HostEntry.sFullscreenRotationActive || !(p.getResult() instanceof Integer)) return;
                    try {
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        if (!HostEntry.isMiStepSideTask(task)) return;
                        int original = (Integer) p.getResult();
                        int visible = SystemBarVisibilityPolicy.forSideTask(original);
                        if (visible != original) p.setResult(visible);
                    } catch (Throwable error) {
                        HostEntry.log("side task system-bar policy skipped: " + error);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(windowState, "canAffectSystemUiFlags", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!HostEntry.sZoomed || !Boolean.TRUE.equals(p.getResult())) return;
                    try {
                        if (XposedHelpers.callMethod(p.thisObject, "getDisplayContent") != HostEntry.sDisplayContent) return;
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        Object lease = MiStepDesktopLaunchBridge.recentsLease(task);
                        if (lease != null && MiStepDesktopLaunchBridge.recentsLeaseAlive(lease))
                            p.setResult(Boolean.FALSE);
                    } catch (Throwable stale) { /* Preserve the native decision for unrelated windows. */ }
                }
            });
            HostEntry.log("side task fullscreen system-ui flags isolated from display bars");
        } catch (Throwable error) {
            HostEntry.log("side task system-bar policy unavailable: " + error);
        }
    }

    void installDesktopIconLaunchBridge(ClassLoader cl) {
        try {
            Class<?> atms = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerService", cl);
            int hooked = 0;
            for (java.lang.reflect.Method method : atms.getDeclaredMethods()) {
                String name = method.getName();
                final boolean fromRecents = name.equals("startActivityFromRecents");
                if (!(fromRecents || name.equals("startActivity") || name.equals("startActivityAsUser")
                        || name.equals("startActivityAsUserWithFeature"))) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        boolean handled = fromRecents
                                ? MiStepDesktopLaunchBridge.beforeRecents((Context) HostEntry.sSystemContext, p.thisObject, p.args)
                                : MiStepDesktopLaunchBridge.before((Context) HostEntry.sSystemContext, p.thisObject, p.args,
                                        desktopLaunchUser(method, p.args));
                        if (handled) {
                            p.setObjectExtra("mistepHostLaunch", true);
                            p.setResult(2); // ActivityManager.START_TASK_TO_FRONT
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (fromRecents || Boolean.TRUE.equals(p.getObjectExtra("mistepHostLaunch"))) return;
                        if (p.getThrowable() != null) return;
                        Object result = p.getResult();
                        if (result instanceof Integer && (Integer) result < 0) return;
                        forwardDesktopIconLaunch(p.args, desktopLaunchUser(method, p.args));
                    }
                });
                hooked++;
            }
            HostEntry.log("desktop icon launch bridge hooked ATMS methods=" + hooked);
        } catch (Throwable error) {
            HostEntry.log("desktop icon launch bridge unavailable: " + error);
        }
    }

    static int desktopLaunchUser(java.lang.reflect.Method method, Object[] args) {
        int callerUser = (Integer) XposedHelpers.callStaticMethod(android.os.UserHandle.class,
                "getUserId", android.os.Binder.getCallingUid());
        return DesktopIconLaunchPolicy.launchUser(method.getName(), method.getParameterTypes(), args, callerUser);
    }

    static void forwardDesktopIconLaunch(Object[] args, int launchUser) {
        if (args == null || launchUser < 0) return;
        String callerPackage = null;
        android.content.Intent launch = null;
        for (Object arg : args) {
            if (arg instanceof String && "com.miui.home".equals(arg)) {
                callerPackage = (String) arg;
            } else if (arg instanceof android.content.Intent) {
                launch = (android.content.Intent) arg;
            }
        }
        String pkg = DesktopIconLaunchPolicy.packageForLauncherEntry(
                callerPackage,
                launch == null ? null : launch.getAction(),
                launch != null && launch.hasCategory(android.content.Intent.CATEGORY_LAUNCHER),
                launch == null || launch.getComponent() == null
                        ? null : launch.getComponent().getPackageName());
        if (pkg == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (HostEntry.class) {
            if (pkg.equals(HostEntry.sLastDesktopLaunchPackage) && launchUser == HostEntry.sLastDesktopLaunchUser
                    && now - HostEntry.sLastDesktopLaunchAt < 350L) return;
            HostEntry.sLastDesktopLaunchPackage = pkg;
            HostEntry.sLastDesktopLaunchUser = launchUser;
            HostEntry.sLastDesktopLaunchAt = now;
        }
        try {
            Context context = (Context) HostEntry.sSystemContext;
            if (context == null) return;
            android.content.Intent signal = new android.content.Intent(HostEntry.DESKTOP_ICON_LAUNCH_ACTION)
                    .setPackage(HostEntry.SYSTEMUI)
                    .putExtra("package", pkg).putExtra("userId", launchUser);
            context.sendBroadcast(signal);
            HostEntry.log("desktop icon launch forwarded package=" + pkg + " user=" + launchUser);
        } catch (Throwable error) {
            HostEntry.log("desktop icon launch forward failed: " + error);
        }
    }

    void hookApplication(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.android.systemui.SystemUIService", cl);
            XposedBridge.hookAllMethods(c, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    if (self instanceof Context) {
                        HostEntry.sContext = (Context) self;
                        MiStepClipboard.start(HostEntry.sContext);
                        MiStepSystemActions.start(HostEntry.sContext, cl);
                        MiStepWarmup.start(HostEntry.sContext, cl);
                        HostEntry.log("captured Context -> " + HostEntry.sContext);
                        HostEntry.scheduleStart();
                    }
                }
            });
            HostEntry.log("hooked SystemUIService#onCreate");
        } catch (Throwable t) {
            HostEntry.log("could not hook SystemUIService: " + t);
        }
    }

    void hookBackGestureDiagnostics(ClassLoader cl) {
        try {
            Class<?> receiver = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl$inputEventReceiver$1", cl);
            XposedBridge.hookAllMethods(receiver, "onInputEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 1 && p.args[0] instanceof android.view.MotionEvent)
                        logBackEvent("DisplayBackGestureHandler", (android.view.MotionEvent) p.args[0]);
                }
            });
            HostEntry.log("DisplayBackGestureHandler input diagnostics installed");
        } catch (Throwable error) {
            HostEntry.log("DisplayBackGestureHandler input diagnostics unavailable: " + error);
        }
        try {
            Class<?> panel = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.gestural.BackPanelController", cl);
            XposedBridge.hookAllMethods(panel, "onMotionEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 1 && p.args[0] instanceof android.view.MotionEvent)
                        logBackEvent("BackPanelController", (android.view.MotionEvent) p.args[0]);
                }
            });
            HostEntry.log("BackPanelController input diagnostics installed");
        } catch (Throwable error) {
            HostEntry.log("BackPanelController input diagnostics unavailable: " + error);
        }
    }

    void hookTransitionBounds(ClassLoader cl) {
        // Native windows now share the main parent coordinate system.
        // Physical/main conversion and pre-return position suppression are unnecessary.
        try {
            Class<?> util = XposedHelpers.findClass(
                    "com.android.wm.shell.common.transition.MiuiTransitionHandlerUtil", cl);
            XposedBridge.hookAllMethods(util, "getAnimBounds", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    Rect main = MiStepHost.activeMainBounds();
                    if (!(result instanceof Rect) || main == null || param.args == null
                            || param.args.length < 2
                            || !MiStepHost.shouldClampAnimationBounds(
                                    param.args[0], param.args[1])) return;
                    Rect original = (Rect) result;
                    Rect clipped = TransitionBounds.clamp(original, main);
                    if (clipped == original || clipped.equals(original)) return;
                    param.setResult(clipped);
                    HostEntry.log("transition animation clipped " + original + " -> " + clipped
                            + " main=" + main);
                }
            });
            HostEntry.log("MIUI transition animation bounds clamped to active main pane");
        } catch (Throwable error) {
            HostEntry.log("transition animation clamp unavailable: " + error);
        }

    }

    static void logBackEvent(String source, android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action != android.view.MotionEvent.ACTION_DOWN
                && action != android.view.MotionEvent.ACTION_UP
                && action != android.view.MotionEvent.ACTION_CANCEL) return;
        int display = -1;
        try { display = (Integer) XposedHelpers.callMethod(event, "getDisplayId"); }
        catch (Throwable ignored) {}
        HostEntry.log("back event source=" + source + " action=" + action + " display=" + display
                + " local=" + event.getX() + "," + event.getY()
                + " raw=" + event.getRawX() + "," + event.getRawY()
                + " hostZoom=" + HostEntry.sHostZoomed);
    }

    void hookRecentsWallpaper(ClassLoader cl) {
        try {
            Class<?> util = XposedHelpers.findClass("com.android.wm.shell.shared.TransitionUtil", cl);
            XposedBridge.hookAllMethods(util, "newTarget", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length != 5 || p.getResult() == null
                            || !(p.args[4] instanceof java.util.Map)) return;
                    try {
                        if (MiStepRecentsWallpaper.stage(p.args[2], p.args[0],
                                XposedHelpers.getObjectField(p.getResult(), "leash"), p.args[3],
                                MiStepHost.activeMainTransform(), MiStepHost.activeMainBounds(),
                                (java.util.Map) p.args[4]))
                            HostEntry.log("Recents wallpaper capture fill staged behind workspace wallpaper");
                    } catch (Throwable error) {
                        HostEntry.log("Recents wallpaper capture unchanged: " + error);
                    }
                }
            });
        } catch (Throwable error) { HostEntry.log("Recents wallpaper hook unavailable: " + error); }
    }

    void hookShellProviders(ClassLoader cl) {
        Class<?> module;
        try {
            module = XposedHelpers.findClass("com.android.wm.shell.dagger.WMShellBaseModule", cl);
        } catch (Throwable t) {
            HostEntry.log("WMShellBaseModule not found");
            return;
        }
        int hooked = 0;
        for (final Method m : module.getDeclaredMethods()) {
            if (!m.getReturnType().getName().contains("TaskView")
                    && !m.getName().startsWith("provideTaskView")) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        String n = m.getName();
                        if ("provideTaskViewTransitions".equals(n)) {
                            HostEntry.sTaskViewTransitions = r;
                        } else if ("provideTaskViewFactoryController".equals(n)) {
                            Object[] args = param.args;
                            if (args != null && args.length > 1 && args[1] instanceof Executor) {
                                HostEntry.sShellExecutor = (Executor) args[1];
                                HostEntry.log("captured ShellExecutor -> " + HostEntry.sShellExecutor);
                            }
                        } else if ("provideTaskViewFactory".equals(n) && r instanceof Optional) {
                            HostEntry.sTaskViewFactory = ((Optional<?>) r).orElse(null);
                            HostEntry.log("captured TaskViewFactory -> " + HostEntry.sTaskViewFactory);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                HostEntry.log("hook failed for " + m.getName() + ": " + t);
            }
        }
        HostEntry.log("provider hooks installed: " + hooked);
    }

    void installTriggers() {
        final Runnable toggle = new Runnable() {
            @Override
            public void run() {
                if (!MiStepEntryGuard.canOpen(HostEntry.sContext)) return;
                try {
                    MiStepHost.toggleOrStart(HostEntry.sContext, HostEntry.sShellExecutor, HostEntry.sTaskViewFactory, HostEntry.sTaskViewTransitions);
                } catch (Throwable t) {
                    HostEntry.log("toggle FAILED: " + t);
                    XposedBridge.log(t);
                }
            }
        };
        HotZoneGesture.install(HostEntry.sContext, toggle);
        try {
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    String action = intent.getAction();
                    if ("com.jiusis.mistep.DEBUG".equals(action)) {
                        handleDebugBroadcast(intent);
                        return;
                    }
                    if (MiStepHost.LANDSCAPE_MODE_ACTION.equals(action)) {
                        MiStepHost.onLandscapeMode(intent.getBooleanExtra("fullscreen", false));
                        return;
                    }
                    if(MiStepHost.TOP_TASK_STATE_ACTION.equals(action)) {
                        MiStepHost.onTopTaskState(intent);return;
                    }
                    HostEntry.log("broadcast " + action + " -> toggle");
                    toggle.run();
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction("com.jiusis.mistep.TOGGLE");
            filter.addAction("com.jiusis.mistep.DEBUG");
            filter.addAction(MiStepHost.LANDSCAPE_MODE_ACTION);
            filter.addAction(MiStepHost.TOP_TASK_STATE_ACTION);
            HostEntry.sContext.registerReceiver(receiver, filter, android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    null, android.content.Context.RECEIVER_EXPORTED);
            HostEntry.log("broadcast trigger armed: TOGGLE / DEBUG");
        } catch (Throwable t) {
            HostEntry.log("broadcast trigger failed: " + t);
        }
        try {
            android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    if (android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                        String reason = intent.getStringExtra("reason");
                        HostEntry.log("native system dialogs close reason=" + reason);
                        if (MiStepNavigationPolicy.shouldSuspend(reason))
                            MiStepHost.onSystemNavigation(reason);
                        else if ("gestureNav".equals(reason) || "recentapps".equals(reason))
                            HostEntry.log("native gesture navigation leaves Mi Step active");
                        return;
                    }
                    if (android.content.Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        HostEntry.log("user present -> clear keyguard privacy");
                        MiStepHost.onUserPresent();
                        return;
                    }
                    boolean on = android.content.Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                    if (!on || !MiStepEntryGuard.canOpen(HostEntry.sContext)) MiStepInputTrace.stop();
                    HostEntry.log("screen " + (on ? "ON" : "OFF"));
                    MiStepHost.onScreenState(on);
                }
            };
            android.content.IntentFilter screenFilter = new android.content.IntentFilter();
            screenFilter.addAction(android.content.Intent.ACTION_SCREEN_ON);
            screenFilter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            screenFilter.addAction(android.content.Intent.ACTION_USER_PRESENT);
            screenFilter.addAction(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            HostEntry.sContext.registerReceiver(screenReceiver, screenFilter,
                    android.content.Context.RECEIVER_EXPORTED);
            HostEntry.log("screen trigger armed");
        } catch (Throwable t) {
            HostEntry.log("screen trigger failed: " + t);
        }
    }

    void handleDebugBroadcast(android.content.Intent intent) {
        if (intent.getBooleanExtra("stopInputTrace", false)) {
            MiStepInputTrace.stop();
            if (HostEntry.sContext != null) HostEntry.sContext.sendBroadcast(new Intent(MiStepInputTrace.ACTION)
                    .setPackage("android").putExtra("stopInputTrace", true));
            return;
        }
        if (!MiStepEntryGuard.canOpen(HostEntry.sContext)) return;
        if (intent.getBooleanExtra("traceInput", false)) {
            MiStepInputTrace.arm(HostEntry.sContext, false, HostEntry.sContext.getClassLoader());
            HostEntry.sContext.sendBroadcast(new Intent(MiStepInputTrace.ACTION).setPackage("android"));
            return;
        }
        if (intent.getBooleanExtra("traceNextSwap", false)) {
            MiStepHost.debugTraceNextSwap();
            return;
        }
        int slot = intent.getIntExtra("slot", -1);
        String pkg = intent.getStringExtra("pkg");
        boolean swap = intent.getBooleanExtra("swap", false);
        if (intent.getBooleanExtra("dumpViews", false)) {
            HostEntry.log("broadcast DEBUG dumpViews");
            MiStepHost.debugDumpViews();
            return;
        }
        if (intent.getBooleanExtra("roots", false)) {
            HostEntry.log("broadcast DEBUG roots");
            MiStepHost.debugRoots();
            return;
        }
        if (intent.getBooleanExtra("leashAnim", false)) {
            final int animSlot = intent.getIntExtra("slot", -1);
            final int duration = intent.getIntExtra("durationMs", 400);
            final String animPkg = intent.getStringExtra("pkg");
            HostEntry.log("broadcast DEBUG leashAnim slot=" + animSlot + " duration=" + duration
                    + " pkg=" + animPkg);
            if (HostEntry.sTaskViewFactory == null || HostEntry.sShellExecutor == null || HostEntry.sTaskViewTransitions == null) {
                HostEntry.log("broadcast DEBUG leashAnim: capabilities not ready, ignored");
                return;
            }
            MiStepHost.toggleOrStartIfNeeded(HostEntry.sContext, HostEntry.sShellExecutor, HostEntry.sTaskViewFactory,
                    HostEntry.sTaskViewTransitions);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> MiStepHost.debugLeashAnim(animSlot, duration, animPkg), 1500L);
            return;
        }
        if (intent.hasExtra("mirrorTask") || intent.getBooleanExtra("mirror", false)) {
            final int task = intent.getIntExtra("mirrorTask", -1);
            final int x = intent.getIntExtra("mirrorX", 0);
            final int y = intent.getIntExtra("mirrorY", 622);
            final int w = intent.getIntExtra("mirrorW", 400);
            final int h = intent.getIntExtra("mirrorH", 912);
            final int hold = intent.getIntExtra("mirrorHoldMs", 6000);
            final boolean hideSource = intent.getBooleanExtra("mirrorHideSource", false);
            final int hideModeExtra = intent.getIntExtra("mirrorHideMode", -1);
            final int hideMode = hideModeExtra >= 0 ? hideModeExtra : (hideSource ? 1 : 0);
            HostEntry.log("broadcast DEBUG mirror task=" + task + " rect=" + x + "," + y + "," + w + "x" + h
                    + " hold=" + hold + " hideMode=" + hideMode);
            if (HostEntry.sTaskViewFactory == null || HostEntry.sShellExecutor == null || HostEntry.sTaskViewTransitions == null) {
                HostEntry.log("broadcast DEBUG mirror: capabilities not ready, ignored");
                return;
            }
            MiStepHost.toggleOrStartIfNeeded(HostEntry.sContext, HostEntry.sShellExecutor, HostEntry.sTaskViewFactory,
                    HostEntry.sTaskViewTransitions);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> MiStepHost.debugMirror(task, x, y, w, h, hold, hideMode), 1500L);
            return;
        }
        HostEntry.log("broadcast DEBUG slot=" + slot + " pkg=" + pkg + " swap=" + swap);
        if (slot < 0) {
            return;
        }
        if (HostEntry.sTaskViewFactory == null || HostEntry.sShellExecutor == null || HostEntry.sTaskViewTransitions == null) {
            HostEntry.log("broadcast DEBUG: capabilities not ready, ignored");
            return;
        }
        MiStepHost.toggleOrStartIfNeeded(HostEntry.sContext, HostEntry.sShellExecutor, HostEntry.sTaskViewFactory, HostEntry.sTaskViewTransitions);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (swap) {
                    MiStepHost.debugSwap(slot);
                } else if (pkg != null) {
                    MiStepHost.debugLaunch(slot, pkg);
                }
            }
        }, 1500L);
    }

    void createTaskView() throws Exception {
        Consumer<Object> onCreated = new Consumer<Object>() {
            @Override
            public void accept(Object view) {
                HostEntry.log("TaskView created -> " + view);
                HostEntry.sTaskView = view;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        attachAndLaunch();
                    }
                });
            }
        };
        XposedHelpers.callMethod(HostEntry.sTaskViewFactory, "create", HostEntry.sContext, HostEntry.sShellExecutor, onCreated);
        HostEntry.log("factory.create invoked");
    }

    void attachAndLaunch() {
        try {
            if (HostEntry.sContext == null || HostEntry.sTaskView == null) {
                HostEntry.log("attachAndLaunch: missing context/taskview");
                return;
            }
            final View taskView = (View) HostEntry.sTaskView;

            // 监听 TaskView 生命周期：这些回调决定后续「格子绑定/交换」的状态机
            Object listener = makeListener(HostEntry.sShellExecutor, "com.android.wm.shell.taskview.TaskView$Listener");
            if (listener != null) {
                XposedHelpers.callMethod(taskView, "setListener", HostEntry.sShellExecutor, listener);
                HostEntry.log("setListener OK");
            }

            LinearLayout root = new LinearLayout(HostEntry.sContext);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xE6000000);
            root.setPadding(16, 16, 16, 16);

            TextView label = new TextView(HostEntry.sContext);
            label.setText("Mi Step宿主 v0.3 — 真实应用承载测试");
            label.setTextColor(Color.WHITE);
            label.setTextSize(15f);
            root.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            if (taskView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) taskView.getParent()).removeView(taskView);
            }
            root.addView(taskView, new LinearLayout.LayoutParams(HostEntry.BOX_W, HostEntry.BOX_H));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = HostEntry.BOX_X;
            lp.y = HostEntry.BOX_Y;
            lp.setTitle("MiStepHostBox");

            WindowManager wm = (WindowManager) HostEntry.sContext.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(root, lp);
            HostEntry.sWindowRoot = root;
            HostEntry.log("window with TaskView added: " + root + " box=" + HostEntry.BOX_W + "x" + HostEntry.BOX_H + "@" + HostEntry.BOX_X + "," + HostEntry.BOX_Y);

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    launchTargetApp(taskView);
                }
            }, HostEntry.AFTER_ADD_MS);
        } catch (Throwable t) {
            HostEntry.log("attachAndLaunch FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    java.io.File latchFile() {
        return new java.io.File(HostEntry.sContext.getFilesDir(), "mistep-launched");
    }

    void armLatch() {
        try {
            java.io.File f = latchFile();
            if (!f.exists() && f.createNewFile()) {
                HostEntry.log("latch armed at " + f);
            }
        } catch (Throwable t) {
            HostEntry.log("could not arm latch: " + t);
        }
    }

    void launchTargetApp(View taskView) {
        try {
            Intent launch = HostEntry.sContext.getPackageManager().getLaunchIntentForPackage(HostEntry.TARGET_PKG);
            if (launch == null) {
                HostEntry.log("no launch intent for " + HostEntry.TARGET_PKG);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Rect bounds = new Rect(0, 0, HostEntry.BOX_W, HostEntry.BOX_H);
            ActivityOptions options = ActivityOptions.makeBasic();
            callHidden(options, "setLaunchBounds", bounds);
            callHidden(options, "setLaunchCookie", new Binder());
            callHidden(options, "setLaunchWindowingMode", 6);          // WINDOWING_MODE_MULTI_WINDOW
            callHidden(options, "setRemoveWithTaskOrganizer", Boolean.TRUE);

            // pendingIntent 必须非空：TaskViewTransitions.startActivity 第一行就
            // pendingIntent.getIntent()，且真正的启动走
            // WindowContainerTransaction.sendPendingIntent(pendingIntent, intent, options)。
            // 传 null 会让 SystemUI 在 wmshell.main 上 NPE 崩溃（已实测）。
            PendingIntent pi = PendingIntent.getActivity(HostEntry.sContext, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE);

            // 同理不能用 XposedHelpers.callMethod：它靠实参类型匹配，遇到 null 实参会抛
            // "Attempt to get length of null array"。改用显式 Method.invoke。
            Method start = null;
            for (Class<?> c = taskView.getClass(); c != null && start == null; c = c.getSuperclass()) {
                try {
                    start = c.getDeclaredMethod("startActivity",
                            PendingIntent.class, Intent.class, ActivityOptions.class, Rect.class);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
            if (start == null) {
                HostEntry.log("startActivity(PendingIntent, Intent, ActivityOptions, Rect) not found");
                return;
            }
            // 先上锁再调用：万一这次调用崩掉 SystemUI，重启后的新实例会因锁存在而不再重试
            armLatch();
            start.setAccessible(true);
            start.invoke(taskView, pi, launch, options, bounds);
            HostEntry.log("startActivity invoked for " + HostEntry.TARGET_PKG + " pi=" + pi + " bounds=" + bounds);
        } catch (Throwable t) {
            HostEntry.log("launchTargetApp FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    static void callHidden(Object target, String name, Object... args) {
        try {
            Object r = XposedHelpers.callMethod(target, name, args);
            HostEntry.log("   hidden OK " + name + " -> " + r);
        } catch (Throwable t) {
            HostEntry.log("   hidden FAILED " + name + ": " + t);
        }
    }

    Object makeListener(Executor executor, String ifaceName) {
        try {
            Class<?> iface = XposedHelpers.findClass(ifaceName, HostEntry.sContext.getClassLoader());
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            StringBuilder sb = new StringBuilder("listener#").append(method.getName()).append('(');
                            if (args != null) {
                                for (int i = 0; i < args.length; i++) {
                                    sb.append(i > 0 ? ", " : "").append(args[i]);
                                }
                            }
                            HostEntry.log(sb.append(')').toString());
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return Boolean.FALSE;
                            }
                            if (rt == int.class) {
                                return 0;
                            }
                            if (rt == long.class) {
                                return 0L;
                            }
                            return null;
                        }
                    });
        } catch (Throwable t) {
            HostEntry.log("makeListener failed: " + t);
            return null;
        }
    }

    static Object readField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return "unreadable: " + t;
        }
    }

    static String sig(Method m) {
        return Modifier.toString(m.getModifiers()) + ' ' + m.getReturnType().getSimpleName() + ' ' + m.getName();
    }

}
