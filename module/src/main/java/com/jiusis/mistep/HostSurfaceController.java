package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** 保留表面重挂 / 覆盖层刷新 / 侧格表面写权限簇 */
final class HostSurfaceController {
    private final MiStepHost h;

    HostSurfaceController(MiStepHost host) { h = host; }

    void installRetainedSurfaces(ClassLoader cl) {
        if (!MiStepHost.surfaceHooksReady) {
            java.util.List<XC_MethodHook.Unhook> installed = new java.util.ArrayList<>();
            try {
                Class<?> tx = Class.forName("android.view.SurfaceControl$Transaction");
                installed.addAll(XposedBridge.hookAllMethods(tx, "merge", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        MiStepHost hb = MiStepHost.sInstance;
                        if (hb != null && !p.hasThrowable() && p.args.length == 1) {
                            hb.finishTransactions.merge(p.thisObject, p.args[0]);
                            hb.workspaceFinishes.merge(p.thisObject, p.args[0]);
                            hb.nativeObservations.merge(p.thisObject, p.args[0]);
                            hb.keyguardTransactions.merge(p.thisObject, p.args[0]);
                        }
                    }
                }));
                installed.addAll(XposedBridge.hookAllMethods(tx, "apply", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        MiStepHost hb = MiStepHost.sInstance;
                        if (hb == null) return;
                        hb.appendHiddenKeyguardSurfaces(p.thisObject, hb.keyguardTransactions.take(p.thisObject));
                        java.util.List<RenderBinding> frames = hb.finishTransactions.take(p.thisObject);
                        if (!frames.isEmpty()) hb.appendRetainedSurfaces(p.thisObject, frames);
                        hb.appendWorkspaceSurfaces(p.thisObject, hb.workspaceFinishes.take(p.thisObject));
                        java.util.List<MiStepHost.NativeObservation> checks = hb.nativeObservations.take(p.thisObject);
                        if (!checks.isEmpty()) {
                            try {
                                Executor uiExecutor = action -> hb.main.post(action);
                                // SDK 37: 监听参数类型是 SurfaceControl$TransactionCommittedListener
                                //（onTransactionCommitted()）；传 Runnable 会反射失配、提交信号全失。
                                Class<?> callbackType = XposedHelpers.findClass(
                                        "android.view.SurfaceControl$TransactionCommittedListener",
                                        p.thisObject.getClass().getClassLoader());
                                Object callback = java.lang.reflect.Proxy.newProxyInstance(
                                        callbackType.getClassLoader(), new Class<?>[]{callbackType},
                                        (proxy, method, args) -> {
                                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                                            if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                                            if ("toString".equals(method.getName())) return "MiStepNativeObservation";
                                            if ("onTransactionCommitted".equals(method.getName())) {
                                                for (MiStepHost.NativeObservation check : checks) check.run();
                                            }
                                            return null;
                                        });
                                XposedHelpers.callMethod(p.thisObject, "addTransactionCommittedListener",
                                        uiExecutor, callback);
                            } catch (Throwable unavailable) {
                                MiStepHost.log("commit signal unavailable; retain bounded observation watchdog");
                            }
                        }
                    }
                }));
                installed.addAll(XposedBridge.hookAllMethods(tx, "close", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        MiStepHost hb = MiStepHost.sInstance;
                        if (hb != null) {
                            hb.finishTransactions.forget(p.thisObject);
                            hb.workspaceFinishes.forget(p.thisObject);
                            hb.nativeObservations.forget(p.thisObject);
                            hb.keyguardTransactions.forget(p.thisObject);
                        }
                    }
                }));
                if (installed.size() < 3) throw new IllegalStateException("missing finish transaction hooks");
                MiStepKeyguardTransition.installNativeStart(cl, (info, start) -> {
                    MiStepHost hb = MiStepHost.sInstance;
                    if (hb != null && hb.retainedForLock && hb.keyguardWindowHidden)
                        hb.keyguardTransactions.mark(start, java.util.Collections.singletonList(hb.sceneEpoch));
                });
                MiStepHost.surfaceHooksReady = true;
            } catch (Throwable error) {
                for (XC_MethodHook.Unhook hook : installed) hook.unhook();
                MiStepHost.log("retained surface hooks unavailable: " + error);
                return;
            }
        }
        h.executeOnShell(() -> {
            try {
                Class<?> iface = XposedHelpers.findClass(
                        "com.android.wm.shell.transition.Transitions$TransitionObserver", cl);
                h.surfaceObserver = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                        (self, method, args) -> {
                            try {
                                String name = method.getName();
                                if ("onTransitionReady".equals(name) && args != null && args.length == 4) {
                                    if (h.retainedForLock && h.keyguardWindowHidden
                                            && (((Integer) XposedHelpers.callMethod(args[1], "getFlags")) & 0x100) != 0)
                                        h.keyguardTransactions.mark(args[3], java.util.Collections.singletonList(h.sceneEpoch));
                                    MiStepSwapTrace trace = h.swapTrace;
                                    if (trace != null) trace.transition(args[1], args[2], args[3]);
                                    trackRetainedSurfaces(args[1], args[3]);
                                    scheduleOverlayRefresh();
                                }
                                else if ("onTransitionMerged".equals(name)
                                        || "onTransitionFinished".equals(name)) h.traceSwapEvent(name);
                                else if ("hashCode".equals(name)) return System.identityHashCode(self);
                                else if ("equals".equals(name)) return self == args[0];
                                else if ("toString".equals(name)) return "MiStepRetainedSurfaceObserver";
                                // Target onTransitionFinished precedes finishT.apply: never restore here.
                            } catch (Throwable error) { MiStepHost.log("surface observer stopped: " + error); }
                            return null;
                        });
                Object shellTransitions = XposedHelpers.getObjectField(h.transitions, "mTransitions");
                XposedHelpers.callMethod(shellTransitions, "registerObserver", h.surfaceObserver);
                MiStepHost.log("retained surface observer registered; restore only in marked final apply");
            } catch (Throwable error) { MiStepHost.log("surface observer unavailable: " + error); }
        });
    }

    void trackRetainedSurfaces(Object info, Object finish) {
        MiStepHost.NativeObservation observation = h.pendingNativeObservation;
        if (observation != null && !observation.consumed.get()) {
            java.util.List<?> observedChanges = (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges");
            for (Object change : observedChanges) {
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (task != null && observation.token.equals(XposedHelpers.getObjectField(task, "token"))) {
                    h.nativeObservations.mark(finish, java.util.Collections.singletonList(observation));
                    break;
                }
            }
        }
        if (!h.screenOn || (h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING) || h.root == null) return;
        // A main activity's rotation/cleanup can reparent a previously adopted side
        // task even when that side token is absent from TransitionInfo. Resolve its
        // current canonical binding at the final apply, after native merges.
        h.workspaceFinishes.mark(finish, java.util.Collections.singletonList(h.sceneEpoch));
        boolean relevant = h.promotion != null || h.transferBusy;
        for (Slot slot : h.slots) if (slot.launching) relevant = true;
        java.util.List<?> changes = (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges");
        for (Object change : changes) {
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null) continue;
            Object token = XposedHelpers.getObjectField(task, "token");
            if (h.isSideToken(token)) relevant = true;
        }
        if (!relevant) return;
        java.util.List<RenderBinding> frames = new java.util.ArrayList<>();
        for (Slot slot : h.slots) {
            if (!h.canRender(slot) || !slot.occupied || slot.promoting || slot.launching || slot.frozen
                    || !mayWriteSideSurface(slot)
                    || !h.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            Object leash = getSurfaceField(slot.controller, "mTaskLeash");
            Object parent = getSurfaceField(slot.controller, "mSurfaceControl");
            if (leash != null && parent != null && leash != parent)
                frames.add(new RenderBinding(slot, h.sceneEpoch, leash, parent));
        }
        if (!frames.isEmpty() && !h.finishTransactions.mark(finish, frames))
            MiStepHost.log("retained finish transaction cap; do not retry or touch unrelated transactions");
    }

    void scheduleOverlayRefresh() {
        if (h.overlayRefreshScheduled) return;
        h.overlayRefreshScheduled = true;
        h.main.postDelayed(() -> {
            h.overlayRefreshScheduled = false;
            refreshOverlayAfterTransition();
        }, 60L);
        h.main.postDelayed(this::refreshOverlayAfterTransition, 380L);
    }

    void refreshOverlayAfterTransition() {
        if (h.scene != MiStepHost.ACTIVE || h.root == null || h.keyguardLocked) return;
        for (Slot slot : h.slots) if (slot.closing) {
            h.main.postDelayed(this::refreshOverlayAfterTransition, 120L);
            return;
        }
        if (h.swapPreview != null || h.cellMovePreview != null || h.promotion != null
                || h.transferBusy) {
            // Relayouting the host window during the visible bridge brings its
            // SurfaceView children forward for one composition. Retry after handoff.
            h.main.postDelayed(this::refreshOverlayAfterTransition, 120L);
            return;
        }
        try {
            h.root.setVisibility(View.VISIBLE);
            h.root.setAlpha(1f);
            h.root.requestLayout();
            h.root.invalidate();
            if (h.root.getWindowToken() != null && h.wm != null && h.lp != null)
                h.wm.updateViewLayout(h.root, h.lp);
            MiStepHost.log("overlay refreshed for transition");
        } catch (Throwable error) {
            MiStepHost.log("overlay refresh failed: " + error);
        }
    }

    void appendWorkspaceSurfaces(Object tx, java.util.List<Long> epochs) {
        if (!h.screenOn || (h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING) || h.root == null
                || !epochs.contains(h.sceneEpoch)) return;
        java.util.List<RenderBinding> frames = new java.util.ArrayList<>();
        for (Slot slot : h.slots) {
            if (slot.index <= MiStepHost.SLOT_MAIN || !h.canRender(slot) || !slot.occupied
                    || slot.parked || slot.promoting || slot.launching || slot.frozen
                    || !mayWriteSideSurface(slot)
                    || !h.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            Object leash = getSurfaceField(slot.controller, "mTaskLeash");
            Object parent = getSurfaceField(slot.controller, "mSurfaceControl");
            if (leash != null && parent != null && leash != parent)
                frames.add(new RenderBinding(slot, h.sceneEpoch, leash, parent));
        }
        appendRetainedSurfaces(tx, frames);
    }

    void appendRetainedSurfaces(Object tx, java.util.List<RenderBinding> frames) {
        java.util.Set<Slot> restored = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int count = 0;
        for (RenderBinding frame : frames) {
            Slot slot = frame.slot;
            if (restored.contains(slot) || !h.canRender(slot) || slot.promoting || slot.launching || slot.frozen
                    || !mayWriteSideSurface(slot)
                    || frame.scene != h.sceneEpoch || frame.binding != slot.taskEpoch
                    || frame.id != slot.taskId || !frame.token.equals(slot.taskToken)
                    || !h.controllerOwns(slot, frame.id, frame.token)
                    || frame.leash != getSurfaceField(slot.controller, "mTaskLeash")
                    || frame.parent != getSurfaceField(slot.controller, "mSurfaceControl")) continue;
            try {
                // Same parent/crop/position/show contract as target TaskViewTransitions.updateSurface.
                XposedHelpers.callMethod(tx, "reparent", frame.leash, frame.parent);
                XposedHelpers.callMethod(tx, "setLayer", frame.leash, 0);
                XposedHelpers.callMethod(tx, "setPosition", frame.leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setWindowCrop", frame.leash, h.logicalBounds(slot));
                float scale = h.scaleFor(slot);
                XposedHelpers.callMethod(tx, "setMatrix", frame.leash, scale, 0f, 0f, scale);
                XposedHelpers.callMethod(tx, "setAlpha", frame.leash, 1f);
                XposedHelpers.callMethod(tx, "show", frame.leash);
                restored.add(slot);
                count++;
            } catch (Throwable error) { MiStepHost.log("retained surface skipped task=" + frame.id + ": " + error); }
        }
        if (count > 0) MiStepHost.log("final transaction retained own side surfaces=" + count);
    }

    /** Native unlock may animate its wrappers, but our live tasks stay under the hidden Host. */
    void appendHiddenKeyguardSurfaces(Object tx, java.util.List<Long> epochs) {
        if (!h.retainedForLock || !h.keyguardWindowHidden || h.root == null
                || !epochs.contains(h.sceneEpoch) || (h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING)) return;
        int count = 0;
        for (Slot slot : h.slots) {
            if (slot == null || slot.index <= MiStepHost.SLOT_MAIN || !h.isActive(slot) || !slot.occupied
                    || slot.parked || slot.promoting || slot.launching || slot.frozen
                    || !mayWriteSideSurface(slot) || !h.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            Object leash = getSurfaceField(slot.controller, "mTaskLeash");
            Object parent = getSurfaceField(slot.controller, "mSurfaceControl");
            if (leash == null || parent == null || leash == parent) continue;
            try {
                XposedHelpers.callMethod(tx, "reparent", leash, parent);
                XposedHelpers.callMethod(tx, "setLayer", leash, 0);
                XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setWindowCrop", leash, h.logicalBounds(slot));
                float scale = h.scaleFor(slot);
                XposedHelpers.callMethod(tx, "setMatrix", leash, scale, 0f, 0f, scale);
                XposedHelpers.callMethod(tx, "setAlpha", leash, 1f);
                XposedHelpers.callMethod(tx, "show", leash);
                count++;
            } catch (Throwable error) { MiStepHost.log("hidden retained parent skipped task=" + slot.taskId + ": " + error); }
        }
        if (count > 0) MiStepHost.log("native keyguard transaction retained hidden parents=" + count);
    }

    boolean mayWriteSideSurface(Slot slot) {
        if(slot!=null && slot.index==MiStepHost.SLOT_TOP && !h.topCtrl.topFace
                && (h.topCtrl.topRoll==null || !h.topCtrl.topRoll.rolling()))return false;
        if (slot == null || slot.windowMode != 6 || slot.controller == null) return false;
        PendingSidePromotion pending = slot.pendingSidePromotion;
        if (pending != null && pending.desktopLaunch) return false;
        try {
            // Controller.onTaskInfoChanged forwards info without replacing its
            // mTaskInfo on this ROM. Read the organizer's current canonical info.
            Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
            Object lock = XposedHelpers.getObjectField(organizer, "mLock");
            synchronized (lock) {
                Object tasks = XposedHelpers.getObjectField(organizer, "mTasks");
                Object appeared = XposedHelpers.callMethod(tasks, "get", slot.taskId);
                Object info = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                return info != null && XposedHelpers.getIntField(info, "taskId") == slot.taskId
                        && slot.taskToken != null && slot.taskToken.equals(
                                XposedHelpers.getObjectField(info, "token"))
                        && Integer.valueOf(6).equals(XposedHelpers.callMethod(info, "getWindowingMode"));
            }
        } catch (Throwable unavailable) { return false; }
    }

    static Object getSurfaceField(Object target, String name) { return HostStaticUtil.getSurfaceField(target, name); }

}
