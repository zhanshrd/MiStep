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

/** 放大/显示缩放簇：WMS magnification 桥、live zoom、缩放动画、设置监听 */
final class HostEntryZoom {
    static final HostEntryZoom INSTANCE = new HostEntryZoom();

    private HostEntryZoom() {
    }

    static void setHostZoomed(boolean zoomed) {
        HostEntry.sHostZoomed = zoomed;
    }

    static synchronized void startZoomSettingsWatcher() {
        if (HostEntry.sZoomSettingsWatcherArmed) return;
        try {
            Thread thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        android.content.Context context = HostEntry.sWmsContext;
                        if (context != null) {
                            String value = android.provider.Settings.Global.getString(
                                    context.getContentResolver(), "mistep_zoom");
                            synchronized (HostEntry.sZoomChannels) {
                                long now = System.currentTimeMillis();
                                boolean accepted = HostEntry.sZoomChannels.offerEncoded(value, now);
                                if (accepted && !java.util.Objects.equals(value, HostEntry.sLastSettingsZoom)) {
                                    HostEntry.log("zoom settings received: " + value);
                                }
                                HostEntry.sLastSettingsZoom = value;
                                dispatchCurrentZoom();
                            }
                        }
                        Thread.sleep(200L);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable ignored) {
                        try { Thread.sleep(1000L); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }, "mistep-zoom-settings");
            thread.setDaemon(true);
            thread.start();
            HostEntry.sZoomSettingsWatcherArmed = true;
            HostEntry.log("zoom settings watcher armed");
        } catch (Throwable error) {
            HostEntry.log("zoom settings watcher failed: " + error);
        }
    }

    static synchronized void armZoomBridge(Object service) {
        try {
            final Context context = (Context) XposedHelpers.getObjectField(service, "mContext");
            HostEntry.sWmsContext = context;
            startZoomSettingsWatcher();
            if (HostEntry.sZoomBridgeArmed) return;
            android.os.HandlerThread thread = new android.os.HandlerThread("mistep-zoom-bridge");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            HostEntry.sZoomBridgeHandler = handler;
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    if (MiStepCodes.ACTION.equals(intent.getAction())) {
                        setResultCode(MiStepCodes.receive(context, service.getClass().getClassLoader(), intent));
                        return;
                    }
                    if (LiveSwapZoomProtocol.APPLY.equals(intent.getAction())) {
                        receiveLiveZoom(context, intent);
                        return;
                    }
                    if (MiStepInputTrace.ACTION.equals(intent.getAction())) {
                        if (intent.getBooleanExtra("stopInputTrace", false)) {
                            MiStepInputTrace.stop();
                            return;
                        }
                        HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                        if (target != null) target.handler.post(() -> {
                            if (target != HostEntry.sZoomTarget) return;
                            MiStepInputTrace.arm(context, true, target.display.getClass().getClassLoader());
                            synchronized (target.globalLock) {
                                MiStepInputTrace.captureDisplay(target.display);
                            }
                        });
                        return;
                    }
                    if (MiStepDesktopLaunchBridge.ACTION.equals(intent.getAction())) {
                        MiStepDesktopLaunchBridge.update(intent);
                        MiStepDesktopLaunchBridge.refreshTopInput(service);
                        return;
                    }
                    if ("com.jiusis.mistep.TRANSFER_TOUCH".equals(intent.getAction())) {
                        HostEntry.transferSideTouch(intent);
                        return;
                    }
                    try {
                        synchronized (HostEntry.sZoomChannels) {
                            long now = System.currentTimeMillis();
                            String encoded = intent.getStringExtra("request");
                            if (encoded != null) {
                                HostEntry.sZoomChannels.offerEncoded(encoded, now);
                            } else {
                                // Preserve explicit adb diagnostics and older host senders.
                                float scale = intent.getFloatExtra("scale", 1f);
                                float y = intent.getFloatExtra("offsetY", 0f);
                                float x = intent.getFloatExtra("offsetX", 0f);
                                HostEntry.sZoomChannels.offer(scale + " " + y + " " + x, now, now);
                            }
                            dispatchCurrentZoom();
                        }
                    } catch (Throwable error) {
                        HostEntry.log("zoom broadcast submit failed: " + error);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.jiusis.mistep.ZOOM");
            filter.addAction(LiveSwapZoomProtocol.APPLY);
            filter.addAction("com.jiusis.mistep.TRANSFER_TOUCH");
            filter.addAction(MiStepInputTrace.ACTION);
            filter.addAction(MiStepDesktopLaunchBridge.ACTION);
            filter.addAction(MiStepCodes.ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                // SystemUI / shell can send; ordinary apps cannot change display magnification.
                context.registerReceiver(receiver, filter, android.Manifest.permission.WRITE_SECURE_SETTINGS,
                        handler, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, android.Manifest.permission.WRITE_SECURE_SETTINGS, handler);
            }
            HostEntry.sZoomBridgeArmed = true;
            HostEntry.log("zoom broadcast bridge armed");
        } catch (Throwable error) {
            HostEntry.log("zoom broadcast bridge failed: " + error);
        }
    }

    static Object createMagnificationFinishReplay(Object display) throws Exception {
        Object replay = Class.forName("android.view.SurfaceControl$Transaction")
                .getDeclaredConstructor().newInstance();
        try {
            reapplyFinalMagnification(display, replay);
            return replay;
        } catch (Throwable failure) {
            LiveSwapZoom.closeReplay(replay);
            throw new IllegalStateException("current magnification snapshot failed", failure);
        }
    }

    static void reapplyFinalMagnification(Object display, Object transaction) {
        reapplyFinalMagnification(display, transaction, null);
    }

    static void reapplyFinalMagnification(Object display, Object transaction, Object info) {
        Boolean previous = HostEntry.sFinalMagnificationSnapshot.get();
        Object previousInfo = HostEntry.sMagnificationFinishInfo.get();
        HostEntry.sFinalMagnificationSnapshot.set(Boolean.TRUE);
        if (info == null) HostEntry.sMagnificationFinishInfo.remove();
        else HostEntry.sMagnificationFinishInfo.set(info);
        try { XposedHelpers.callMethod(display, "reapplyMagnificationSpec", transaction); }
        finally {
            if (previous == null) HostEntry.sFinalMagnificationSnapshot.remove();
            else HostEntry.sFinalMagnificationSnapshot.set(previous);
            if (previousInfo == null) HostEntry.sMagnificationFinishInfo.remove();
            else HostEntry.sMagnificationFinishInfo.set(previousInfo);
        }
    }

    static void receiveLiveZoom(Context context, Intent intent) {
        long generation = intent.getLongExtra(LiveSwapZoomProtocol.GENERATION, 0L);
        long issued = intent.getLongExtra(LiveSwapZoomProtocol.ISSUED, 0L);
        float scale = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE, 0));
        float x = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X, 0));
        float y = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y, 0));
        HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
        if (target == null || !LiveSwapZoomProtocol.valid(generation, scale, x, y,
                issued, System.currentTimeMillis())) return;
        LiveSwapZoom.Zoom zoom = new LiveSwapZoom.Zoom(generation, scale, x, y);
        target.handler.post(() -> {
            HostEntry.ZoomCommit commit = new HostEntry.ZoomCommit(context, target, zoom);
            try {
                synchronized (target.globalLock) {
                    if (target != HostEntry.sZoomTarget || issued < HostEntry.sLiveZoomIssued) {
                        commit.finish(false, "stale zoom target or lease"); return;
                    }
                    synchronized (HostEntry.sZoomChannels) {
                        long now = System.currentTimeMillis();
                        if (!LiveSwapZoomProtocol.valid(generation, scale, x, y, issued, now)) {
                            commit.finish(false, "expired zoom lease"); return;
                        }
                        String command = scale + " " + y + " " + x;
                        HostEntry.sZoomChannels.offer(command, issued, now);
                        ZoomChannelState.Request current = HostEntry.sZoomChannels.current(now);
                        if (current.modified > issued && !command.equals(current.command)) {
                            commit.finish(false, "superseded zoom lease"); return;
                        }
                    }
                    if (HostEntry.sLiveZoomCommit != null) HostEntry.sLiveZoomCommit.finish(false, "superseded generation");
                    HostEntry.sLiveZoomIssued = issued;
                    HostEntry.sLiveZoomCommit = commit;
                    target.dispatch.takeOver(target, scale, y, x);
                    HostEntry.cancelZoomAnimation(target);
                    // Logical WM parents already have their final roles, while the real
                    // leashes still animate under Host. Applying this traversal's clear
                    // operations now exposes an identity/fullscreen task for one frame.
                    // Commit the WM spec/input state; the exact visual traversal is already
                    // regenerated by ZoomCommit.finish and merged with Shell's reparent.
                    Object staged = Class.forName("android.view.SurfaceControl$Transaction")
                            .getDeclaredConstructor().newInstance();
                    if (!HostEntry.sLiveSwapZoomSurfaces.begin(target.display, staged)) {
                        LiveSwapZoom.closeReplay(staged);
                        throw new IllegalStateException("live zoom surface scope already active");
                    }
                    try { HostEntry.applyDisplayZoomLocked(target, scale, y, x, commit); }
                    finally {
                        HostEntry.sLiveSwapZoomSurfaces.finish(target.display, staged);
                        LiveSwapZoom.closeReplay(staged);
                    }
                }
                target.handler.postDelayed(() -> commit.finish(false, "WMS transaction commit timeout"),
                        LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS - 100L);
            } catch (Throwable failure) {
                target.dispatch.invalidateLastAttempt();
                commit.finish(false, "WMS zoom apply failed: " + failure.getClass().getSimpleName());
            }
        });
    }

    static void readAndDispatchZoom(java.io.File f) {
        String cur = "";
        long modified = 0L;
        Throwable readError = null;
        try {
            if (f != null) {
                modified = f.lastModified();
                if (f.exists()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.FileReader(f));
                    cur = br.readLine();
                    br.close();
                }
            }
        } catch (Throwable error) {
            readError = error;
            if (HostEntry.sZoomReadError == null || !HostEntry.sZoomReadError.equals(String.valueOf(error))) {
                HostEntry.sZoomReadError = String.valueOf(error);
                HostEntry.log("zoom read error: " + error);
            }
            cur = "";
        }
        if (cur == null) cur = "";
        if (!cur.equals(HostEntry.sZoomLastSeen)) {
            HostEntry.sZoomLastSeen = cur;
            HostEntry.log("zoom cmd seen: '" + cur + "' modified=" + modified
                    + " target=" + (HostEntry.sZoomTarget != null) + " readError=" + readError
                    + " canRead=" + (f != null && f.canRead())
                    + " perms=" + (f != null && f.exists() ? Long.toOctalString(f.length()) : "-"));
        }
        long now = System.currentTimeMillis();
        synchronized (HostEntry.sZoomChannels) {
            HostEntry.sZoomChannels.offer(cur, modified, now);
            dispatchCurrentZoom();
        }
    }

    static void dispatchCurrentZoom() {
        synchronized (HostEntry.sZoomChannels) {
            long now = System.currentTimeMillis();
            ZoomChannelState.Request selected = HostEntry.sZoomChannels.current(now);
            HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
            if (target == null) {
                if (!selected.command.equals(HostEntry.sZoomLastTargetNull)) {
                    HostEntry.sZoomLastTargetNull = selected.command;
                    HostEntry.log("zoom request retained until display ready: " + selected.command);
                }
                return;
            }
            ZoomAnimCommand.Plan anim = ZoomAnimCommand.parse(selected.command,
                    selected.modified, HostEntry.sZoomWatcherStarted, now);
            if (anim != null) {
                target.submitAnim(anim, selected.animationKey);
            } else {
                float[] request = ZoomCommand.read(selected.command,
                        selected.modified, HostEntry.sZoomWatcherStarted, now);
                if (!selected.command.equals(HostEntry.sZoomLastDispatched)) {
                    HostEntry.sZoomLastDispatched = selected.command;
                    HostEntry.log("zoom dispatch " + java.util.Arrays.toString(request)
                            + " from leased request '" + selected.command + "'");
                }
                scheduleZoomDivergenceCheck(target, request);
                target.dispatch.submit(target, request[0], request[1], request[2]);
            }
        }
    }

    static void scheduleZoomDivergenceCheck(HostEntry.ZoomTarget target, float[] desired) {
        if ((HostEntry.sZoomDivergenceTick++ % 3) != 0) return;
        final float scale = desired[0], offsetY = desired[1], offsetX = desired[2];
        target.handler.post(() -> {
            synchronized (target.globalLock) {
                if (target != HostEntry.sZoomTarget || target.display != HostEntry.sDisplayContent) return;
                try {
                    Object actual = XposedHelpers.getObjectField(target.display, "mMagnificationSpec");
                    float aScale = actual == null ? 1f : XposedHelpers.getFloatField(actual, "scale");
                    float aY = actual == null ? 0f : XposedHelpers.getFloatField(actual, "offsetY");
                    float aX = actual == null ? 0f : XposedHelpers.getFloatField(actual, "offsetX");
                    if (Float.compare(aScale, scale) != 0 || Float.compare(aY, offsetY) != 0
                            || Float.compare(aX, offsetX) != 0) {
                        target.dispatch.invalidateLastAttempt();
                        HostEntry.log("zoom spec divergence: actual " + aScale + "/" + aY + "/" + aX
                                + " desired " + scale + "/" + offsetY + "/" + offsetX + "; reassert armed");
                    }
                } catch (Throwable ignored) {
                    // 只读探测，尽力而为
                }
            }
        });
    }

    void hookMagnificationReplayAfterTransition(ClassLoader cl) {
        try {
            Class<?> transition = XposedHelpers.findClass(
                    "com.android.server.wm.Transition", cl);
            XposedBridge.hookAllMethods(transition, "calculateTransitionInfo", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                    if (p.hasThrowable() || !HostEntry.sZoomed || target == null
                            || target.display != HostEntry.sDisplayContent || !Thread.holdsLock(target.globalLock)
                            || p.args.length != 6 || !(p.args[2] instanceof java.util.List<?>)) return;
                    try {
                        if (NativeFreeformReturn.change(p.getResult()) != null) {
                            Object nativeTask = null;
                            for (Object change : (java.util.List<?>) p.args[2]) {
                                Object container = XposedHelpers.getObjectField(change, "mContainer");
                                Object task = XposedHelpers.callMethod(container, "asTask");
                                if (task != null && XposedHelpers.getIntField(change, "mWindowingMode") == 5
                                        && Integer.valueOf(1).equals(XposedHelpers.callMethod(task, "getWindowingMode"))
                                        && XposedHelpers.callMethod(task, "getDisplayContent") == target.display)
                                    nativeTask = task;
                            }
                            Object start = p.args[3];
                            if (nativeTask == null || !HostEntry.sNativeFreeformStart.begin(target.display, start)) return;
                            try {
                                ((android.view.SurfaceControl.Transaction) start).addTransactionCommittedListener(
                                        command -> target.handler.post(command), () -> HostEntry.finishNativeFreeformStart(target, start));
                                if (!NativeFreeformHandoff.markNativePrepared(nativeTask))
                                    throw new IllegalStateException("native return source identity changed");
                                reapplyFinalMagnification(target.display, start);
                                if (!HomeTransitionMagnification.prepare(p.getResult(), (java.util.List<?>) p.args[2],
                                        target.display, start, HostEntry.sZoomScale, HostEntry.sZoomOffsetX, HostEntry.sZoomOffset))
                                    throw new IllegalStateException("native return Root scene changed");
                                HostEntry.log("native return Root/start magnification transferred atomically");
                            } catch (Throwable error) {
                                HostEntry.sNativeFreeformStart.finish(target.display, start); throw error;
                            }
                            return;
                        }
                        for (Object change : (java.util.List<?>) p.args[2]) {
                            if (XposedHelpers.getIntField(change, "mWindowingMode") != 1
                                    || !Boolean.TRUE.equals(XposedHelpers.getObjectField(change, "mVisible"))) continue;
                            Object container = XposedHelpers.getObjectField(change, "mContainer");
                            Object task = XposedHelpers.callMethod(container, "asTask");
                            if (task == null || XposedHelpers.callMethod(task, "getDisplayContent") != target.display
                                    || ((Number) XposedHelpers.callMethod(task, "getWindowingMode")).intValue() != 5)
                                continue;
                            Object start = p.args[3];
                            if (!HostEntry.sNativeFreeformStart.begin(target.display, start)) return;
                            try {
                                android.view.SurfaceControl.Transaction tx =
                                        (android.view.SurfaceControl.Transaction) start;
                                tx.addTransactionCommittedListener(command -> target.handler.post(command),
                                        () -> HostEntry.finishNativeFreeformStart(target, start));
                                if (!NativeFreeformHandoff.markPrepared(task))
                                    throw new IllegalStateException("native fullscreen source identity changed");
                                reapplyFinalMagnification(target.display, start);
                                HostEntry.log("native freeform main transform transferred in start transaction");
                            } catch (Throwable error) {
                                HostEntry.sNativeFreeformStart.finish(target.display, start);
                                throw error;
                            }
                            return;
                        }
                        for (Object change : (java.util.List<?>) p.args[2]) {
                            Object container = XposedHelpers.getObjectField(change, "mContainer");
                            Object task = XposedHelpers.callMethod(container, "asTask");
                            if (task == null) continue;
                            int transitMode = (Integer) XposedHelpers.callMethod(
                                    change, "getTransitMode", container);
                            if (!MainLaunchMagnification.shouldApply(HostEntry.sZoomed, target.display,
                                    XposedHelpers.callMethod(task, "getDisplayContent"),
                                    XposedHelpers.callMethod(task, "getRootTask") == task,
                                    Boolean.TRUE.equals(XposedHelpers.callMethod(
                                            task, "isVisibleRequested")),
                                    (Integer) XposedHelpers.callMethod(task, "getWindowingMode"),
                                    transitMode)) continue;
                            reapplyFinalMagnification(target.display, p.args[3]);
                            if (HomeTransitionMagnification.prepare(p.getResult(),
                                    (java.util.List<?>) p.args[2], target.display, p.args[3],
                                    HostEntry.sZoomScale, HostEntry.sZoomOffsetX, HostEntry.sZoomOffset))
                                HostEntry.log("Home transition root retains workspace transform");
                            HostEntry.log("main launch magnification included in transition start task="
                                    + XposedHelpers.getIntField(task, "mTaskId")
                                    + " transit=" + transitMode);
                            return;
                        }
                    } catch (Throwable error) { HostEntry.log("transition start magnification skipped: " + error); }
                }
            });
            XposedBridge.hookAllMethods(transition, "buildFinishTransaction", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args == null || p.args.length != 3
                            || !(p.args[2] instanceof Object[])) return;
                    HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                        if (target == null) return;
                    try {
                        if (HomeTransitionMagnification.hasInfo(p.args[1]))
                            HostEntry.sHomeTransitionInfos.put(p.thisObject, p.args[1]);
                        boolean replayed = TransitionMagnificationReplay.reapplyIfActive(
                                HostEntry.sZoomed, target.display, Thread.holdsLock(target.globalLock),
                                (Object[]) p.args[2], p.args[0],
                                (display, transaction) -> reapplyFinalMagnification(
                                        display, transaction, p.args[1]));
                        if (replayed) {
                            // A closing native task can stop normal child-layer assignment.
                            // Detach our windows before its leash becomes hidden, and include
                            // the same restoration in the transition's finish transaction.
                            HostEntry.placeHostBelowNativeFreeforms(target.display, p.args[0]);
                            HostEntry.sMagnificationFinishReplays.put(p.thisObject, target.display);
                            HostEntry.log("display magnification reasserted in transition finish");
                        }
                    } catch (Throwable error) {
                        HostEntry.log("transition magnification replay unavailable: " + error);
                    }
                }
            });
            XposedBridge.hookAllMethods(transition, "finishTransition", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                    if (target == null || !Thread.holdsLock(target.globalLock)) return;
                    HomeTransitionMagnification.finish(HostEntry.sHomeTransitionInfos.remove(p.thisObject));
                    try {
                        Object start = XposedHelpers.getObjectField(p.thisObject, "mStartTransaction");
                        HostEntry.sNativeFreeformStart.finish(target.display, start);
                    } catch (Throwable ignored) { }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
                    if (target == null || !Thread.holdsLock(target.globalLock)) return;
                    Object display = HostEntry.sMagnificationFinishReplays.remove(p.thisObject);
                    if (p.hasThrowable() || display == null || target == null
                            || display != target.display) return;
                    target.handler.post(() -> {
                        synchronized (target.globalLock) {
                            if (target != HostEntry.sZoomTarget || target.display != HostEntry.sDisplayContent) return;
                            try {
                                if (HostEntry.sZoomed) {
                                    // A new session may have started since this finish was built.
                                    Object transaction = XposedHelpers.callMethod(display,
                                            "getPendingTransaction");
                                    XposedHelpers.callMethod(display, "reapplyMagnificationSpec",
                                            transaction);
                                    HostEntry.placeHostBelowNativeFreeforms(display, transaction);
                                    XposedHelpers.callMethod(transaction, "apply");
                                } else {
                                    restoreExitedMagnificationLocked(target);
                                }
                            } catch (Throwable error) {
                                HostEntry.log("transition zoom reconciliation skipped: " + error);
                            }
                        }
                    });
                }
            });
            HostEntry.log("Transition#buildFinishTransaction magnification replay hooked");
        } catch (Throwable error) {
            HostEntry.log("transition magnification replay hook unavailable: " + error);
        }
    }

    static void rememberMagnifiedSurface(Object container, Object spec) {
        HostEntry.ZoomTarget target = HostEntry.sZoomTarget;
        if (!HostEntry.sZoomed || target == null || spec == null
                || target.display != HostEntry.sDisplayContent || !Thread.holdsLock(target.globalLock)) return;
        try {
            if (XposedHelpers.callMethod(container, "getDisplayContent") != target.display
                    || XposedHelpers.getObjectField(target.display, "mMagnificationSpec") != spec
                    || XposedHelpers.getObjectField(container, "mLastMagnificationSpec") != spec) return;
            Object surface = XposedHelpers.getObjectField(container, "mSurfaceControl");
            if (surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
                HostEntry.sMagnifiedSurfaces.put(container, surface);
        } catch (Throwable error) {
            HostEntry.log("magnified surface identity skipped: " + error);
        }
    }

    static int restoreExitedMagnificationLocked(HostEntry.ZoomTarget target) {
        if (target == null || target != HostEntry.sZoomTarget || target.display != HostEntry.sDisplayContent
                || HostEntry.sZoomed || !Thread.holdsLock(target.globalLock)) return 0;
        int restored = 0;
        try {
            if (XposedHelpers.getObjectField(target.display, "mMagnificationSpec") != null) return 0;
            Object transaction = XposedHelpers.callMethod(target.display, "getPendingTransaction");
            java.util.Iterator<java.util.Map.Entry<Object, Object>> entries =
                    HostEntry.sMagnifiedSurfaces.entrySet().iterator();
            while (entries.hasNext()) {
                java.util.Map.Entry<Object, Object> entry = entries.next();
                Object container = entry.getKey(), surface = entry.getValue();
                try {
                    if (XposedHelpers.callMethod(container, "getDisplayContent") != target.display
                            || XposedHelpers.getObjectField(container, "mSurfaceControl") != surface
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) {
                        entries.remove();
                        continue;
                    }
                    if (XposedHelpers.getObjectField(container, "mLastMagnificationSpec") != null)
                        continue;
                    if (Integer.valueOf(5).equals(XposedHelpers.callMethod(container,
                                    "getWindowingMode"))
                            && XposedHelpers.callMethod(container, "asTask") != null) {
                        // WM has already released this canonical task leash. An OEM
                        // mini/freeform animation now owns its scale and position;
                        // an old main-region record must not reset it on entry/exit.
                        entries.remove();
                        continue;
                    }
                    Object position = XposedHelpers.getObjectField(container, "mLastSurfacePosition");
                    XposedHelpers.callMethod(transaction, "setMatrix", surface, 1f, 0f, 0f, 1f);
                    XposedHelpers.callMethod(transaction, "setPosition", surface,
                            (float) XposedHelpers.getIntField(position, "x"),
                            (float) XposedHelpers.getIntField(position, "y"));
                    restored++;
                } catch (Throwable error) {
                    entries.remove();
                    HostEntry.log("exit surface restoration skipped: " + error);
                }
            }
            if (restored > 0) {
                XposedHelpers.callMethod(transaction, "apply");
                HostEntry.log("exit magnification surfaces restored=" + restored);
            }
        } catch (Throwable error) {
            HostEntry.log("exit magnification reconciliation unavailable: " + error);
        }
        return restored;
    }

    static void installZoomFileObserver() {
        try {
            final java.io.File dir =
                    new java.io.File("/data/user_de/0/com.android.systemui/files");
            if (!dir.isDirectory()) {
                HostEntry.log("zoom FileObserver skipped: no SystemUI files dir");
                return;
            }
            android.os.FileObserver observer = new android.os.FileObserver(dir,
                    android.os.FileObserver.CLOSE_WRITE
                            | android.os.FileObserver.MOVED_TO
                            | android.os.FileObserver.CREATE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null || !path.endsWith("mistep-zoom")) return;
                    readAndDispatchZoom(new java.io.File(dir, "mistep-zoom"));
                }
            };
            observer.startWatching();
            HostEntry.sZoomFileObserver = observer;
            HostEntry.log("zoom FileObserver armed on SystemUI files dir");
        } catch (Throwable t) {
            HostEntry.log("zoom FileObserver failed: " + t);
        }
    }

    void applyDisplayZoomLocked(HostEntry.ZoomTarget target, float scale,
            float offsetY, float offsetX) throws Throwable {
        applyDisplayZoomLocked(target, scale, offsetY, offsetX, null);
    }

    void applyDisplayZoomLocked(HostEntry.ZoomTarget target, float scale,
            float offsetY, float offsetX, HostEntry.ZoomCommit commit) throws Throwable {
        if (Looper.myLooper() != target.handler.getLooper() || !Thread.holdsLock(target.globalLock)) {
            throw new IllegalStateException("zoom requires the WMS handler and its global lock");
        }
        if (HostEntry.sZoomTarget != target || HostEntry.sDisplayContent != target.display) return;
        Object dc = target.display;
        Class<?> specCls = XposedHelpers.findClass("android.view.MagnificationSpec",
                dc.getClass().getClassLoader());
        // The target's no-arg constructor is the already verified path; obtain() is absent.
        java.lang.reflect.Constructor<?> ctor = specCls.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object spec = ctor.newInstance();
        XposedHelpers.setFloatField(spec, "scale", scale);
        XposedHelpers.setFloatField(spec, "offsetX", offsetX);
        XposedHelpers.setFloatField(spec, "offsetY", offsetY);
        boolean previous = HostEntry.sZoomed;
        boolean rotationWasActive = HostEntry.sFullscreenRotationActive;
        int previousLandscapeState = HostEntry.sNativeLandscapeState;
        HostEntry.sNativeLandscapeState = NativeLandscapePolicy.onZoom(
                HostEntry.sNativeLandscapeState, scale);
        // Rotation refresh can synchronously call back into activity orientation.
        // Publish the incoming workspace state before it runs, so entering from a
        // fullscreen landscape app cannot request native-fullscreen suspension.
        HostEntry.sZoomed = scale != 1f;
        if(HostEntry.sZoomed && !previous)HostEntry.topWorkspaceGeneration++;
        try {
            if (HostEntry.sZoomed) HostEntry.beginFixedMainRotationLocked(dc);
            else HostEntry.endFixedMainRotationLocked(dc);
            if (commit != null) {
                Object pending = XposedHelpers.callMethod(dc, "getPendingTransaction");
                commit.transactionId = ((Number) XposedHelpers.callMethod(pending, "getId")).longValue();
                if (commit.transactionId == 0L) throw new IllegalStateException("missing WMS transaction id");
                Class<?> callbackType = XposedHelpers.findClass(
                        "android.view.SurfaceControl$TransactionCommittedListener", dc.getClass().getClassLoader());
                Object callback = java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(),
                        new Class<?>[]{callbackType}, (proxy, method, args) -> {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                            if ("toString".equals(method.getName())) return "MiStepZoomCommit";
                            if ("onTransactionCommitted".equals(method.getName())) {
                                boolean exact;
                                try {
                                    synchronized (target.globalLock) {
                                        Object actual = XposedHelpers.getObjectField(dc, "mMagnificationSpec");
                                        commit.actualScale = actual == null ? 1f : XposedHelpers.getFloatField(actual, "scale");
                                        commit.actualX = actual == null ? 0f : XposedHelpers.getFloatField(actual, "offsetX");
                                        commit.actualY = actual == null ? 0f : XposedHelpers.getFloatField(actual, "offsetY");
                                        exact = commit.applied && target == HostEntry.sZoomTarget
                                                && HostEntry.sLiveZoomCommit == commit
                                                && commit.zoom.sameTuple(commit.actualScale, commit.actualX, commit.actualY)
                                                && commit.zoom.sameTuple(HostEntry.sZoomScale, HostEntry.sZoomOffsetX, HostEntry.sZoomOffset);
                                    }
                                    commit.finish(exact, exact ? null : "zoom changed before commit acknowledgement");
                                } catch (Throwable failure) {
                                    commit.finish(false, "WMS applied-spec read failed");
                                }
                            }
                            return null;
                        });
                java.util.concurrent.Executor executor = runnable -> target.handler.post(runnable);
                XposedHelpers.callMethod(pending, "addTransactionCommittedListener", executor, callback);
            }
            XposedHelpers.callMethod(dc, "applyMagnificationSpec", spec);
        } catch (Throwable error) {
            HostEntry.sZoomed = previous;
            HostEntry.sNativeLandscapeState = previousLandscapeState;
            if (!rotationWasActive) HostEntry.endFixedMainRotationLocked(dc);
            else if (!HostEntry.sFullscreenRotationActive) HostEntry.beginFixedMainRotationLocked(dc); // exit threw: re-arm the hold to match sZoomed=true
            throw error;
        }
        HostEntry.sZoomScale = scale;
        HostEntry.sZoomOffset = offsetY;
        HostEntry.sZoomOffsetX = offsetX;
        if (previous != HostEntry.sZoomed) MiStepWallpaperScale.refresh(dc);
        if (commit != null) commit.applied = true;
        // An already visible OEM freeform need not trigger assignChildLayers
        // when only magnification changes. Reconcile at the zoom boundary too,
        // in the same WM transaction, so host input/chrome cannot cover it.
        if (previous != HostEntry.sZoomed || scale == 1f) {
            HostEntry.placeHostBelowNativeFreeforms(dc,
                    XposedHelpers.callMethod(dc, "getPendingTransaction"));
        }
        if (scale == 1f) {
            restoreExitedMagnificationLocked(target);
        }
        MiStepRecentsInput.geometryChanged(XposedHelpers.callMethod(dc, "getPendingTransaction"),
                scale, offsetY, offsetX);
        Rect rawDisplayBounds = (Rect) XposedHelpers.callMethod(dc, "getBounds");
        int rawDisplayRotation = (Integer) XposedHelpers.callMethod(dc, "getRotation");
        NativeRawInputBridge.update(scale, offsetY, offsetX,
                rawDisplayBounds.width(), rawDisplayBounds.height(), rawDisplayRotation);
        HostEntry.applyGestureSurfacesLocked(target, scale, offsetY, offsetX);

        // 清掉历史版本遗留的固定 1080x2460 显示裁剪（R2 时代的调试代码）：在 17PM 上会把
        // 窗口层裁成 1080x2460，右侧 120px、底部 148px 变成黑边。每次应用 spec 都显式清空，
        // 能自愈旧版本残留；不再主动设置任何 crop。
        Object crop = null;
        try {
            Object layer = XposedHelpers.callMethod(dc, "getWindowingLayer");
            if (layer != null && Boolean.TRUE.equals(XposedHelpers.callMethod(layer, "isValid"))) {
                crop = Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(crop, "setWindowCrop", layer, (Object) null);
                XposedHelpers.callMethod(crop, "apply");
            }
        } catch (Throwable failure) {
            HostEntry.log("display crop clear unavailable: " + failure);
        } finally {
            if (crop != null) {
                try { XposedHelpers.callMethod(crop, "close"); } catch (Throwable ignored) {}
            }
        }
        // supplies the same matrix and inverse input mapping as the main app windows.
        if (!HostEntry.sZoomFrameApply) {
            HostEntry.log("WM zoom applied scale=" + scale + " offsetY=" + offsetY
                    + " offsetX=" + offsetX
                    + " thread=" + Thread.currentThread().getName() + " globalLock=true");
        }
    }

    void startZoomAnimation(HostEntry.ZoomTarget target, ZoomAnimCommand.Plan plan, String key) {
        if (target == null || plan == null || key == null) return;
        if (HostEntry.sZoomTarget != target) return;
        if (Looper.myLooper() != target.handler.getLooper()) {
            HostEntry.log("zoom animation rejected off the WM handler");
            return;
        }
        if (key.equals(HostEntry.sZoomAnimKey)) return; // 同一命令内容只播放一次
        cancelZoomAnimation(target);
        HostEntry.sZoomAnimKey = key;
        final float fromScale = HostEntry.sZoomScale;
        final float fromY = HostEntry.sZoomOffset;
        final float fromX = HostEntry.sZoomOffsetX;
        if (Math.abs(fromScale - plan.scale) < 0.0005f
                && Math.abs(fromY - plan.offsetY) < 0.5f
                && Math.abs(fromX - plan.offsetX) < 0.5f) {
            HostEntry.log("zoom animation skipped; already at target " + plan.describe());
            return;
        }
        // 动画会改变真实 state；让调度器丢弃旧的 lastAttempt，避免之后“恢复原值”被当成重复请求吞掉。
        target.dispatch.invalidateLastAttempt();
        if (HostEntry.zoomAnimInterpolator == null) {
            HostEntry.zoomAnimInterpolator = new PathInterpolator(0.34f, 0.69f, 0.1f, 1.0f);
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(plan.durationMs);
        animator.setInterpolator(HostEntry.zoomAnimInterpolator);
        animator.addUpdateListener(animation -> {
            if (HostEntry.sZoomAnimator != animator || HostEntry.sZoomTarget != target) return;
            float fraction = (Float) animation.getAnimatedValue();
            float[] state = ZoomAnimCommand.stateAt(fromScale, fromY, fromX, plan, fraction);
            HostEntry.sZoomFrameApply = true;
            try {
                synchronized (target.globalLock) {
                    if (HostEntry.sZoomTarget != target) return;
                    applyDisplayZoomLocked(target, state[0], state[1], state[2]);
                }
            } catch (Throwable error) {
                HostEntry.log("zoom animation frame stopped: " + error);
                cancelZoomAnimation(target);
            } finally {
                HostEntry.sZoomFrameApply = false;
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (HostEntry.sZoomAnimator != animator) return; // 已被更新的普通请求取消
                HostEntry.sZoomAnimator = null;
                HostEntry.sZoomFrameApply = false;
                try {
                    synchronized (target.globalLock) {
                        if (HostEntry.sZoomTarget == target) {
                            applyDisplayZoomLocked(target, plan.scale, plan.offsetY, plan.offsetX);
                        }
                    }
                } catch (Throwable error) {
                    HostEntry.log("zoom animation final apply failed: " + error);
                }
                HostEntry.log("zoom animation finished " + plan.describe());
            }
        });
        HostEntry.sZoomAnimator = animator;
        animator.start();
        HostEntry.log("zoom animation started from scale=" + fromScale + " y=" + fromY + " x=" + fromX
                + " -> " + plan.describe());
    }

    void cancelZoomAnimation(HostEntry.ZoomTarget target) {
        ValueAnimator animator = HostEntry.sZoomAnimator;
        if (animator == null) return;
        if (target != null && Looper.myLooper() != target.handler.getLooper()) {
            target.handler.post(() -> cancelZoomAnimation(target));
            return;
        }
        HostEntry.sZoomAnimator = null;
        HostEntry.sZoomFrameApply = false;
        try { animator.cancel(); } catch (Throwable ignored) {}
        HostEntry.log("zoom animation cancelled");
    }

}
