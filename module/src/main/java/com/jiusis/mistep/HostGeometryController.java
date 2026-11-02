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

/** 显示几何 / 逻辑边界钩子 / 主区变换簇（旧批次 4 重试，掩码方案） */
final class HostGeometryController {
    private final MiStepHost h;

    HostGeometryController(MiStepHost host) { h = host; }

    static Rect activeMainBounds() {
        MiStepHost hb = MiStepHost.sInstance;
        if (hb == null || hb.scene != MiStepHost.ACTIVE || !hb.screenOn || hb.layout == null) return null;
        MiStepLayout active = hb.layout;
        return new Rect(active.mainX, active.mainY,
                active.mainX + active.mainWidth, active.mainY + active.mainHeight);
    }

    static float[] activeMainTransform() {
        MiStepHost hb = MiStepHost.sInstance;
        if (hb == null || !hostSceneActive() || hb.layout == null
                || hb.promotion != null || hb.transferBusy || hb.keyguardLocked) return null;
        MiStepLayout current = hb.layout;
        return new float[]{current.mainScale, current.mainOffsetY, current.mainOffsetX};
    }

    static Rect nativeFreeformAnimationStart(Object change) {
        MiStepHost hb = MiStepHost.sInstance;
        float[] transform = activeMainTransform();
        if (hb == null || transform == null || change == null) return null;
        try {
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null || MiStepHost.windowModeOf(task) != 5
                    || XposedHelpers.getIntField(task, "displayId") != 0
                    || hb.isSideToken(XposedHelpers.getObjectField(task, "token"))) return null;
            int startRotation = (Integer) XposedHelpers.callMethod(change, "getStartRotation");
            int endRotation = (Integer) XposedHelpers.callMethod(change, "getEndRotation");
            if (startRotation < 0 || startRotation != endRotation) return null;
            return NativeFreeformAnimation.startBounds(
                    (Rect) XposedHelpers.callMethod(change, "getStartAbsBounds"),
                    hb.layout.width, hb.layout.height, transform);
        } catch (Throwable error) { return null; }
    }

    static float[] activeStatusBarTransform() {
        MiStepHost hb = MiStepHost.sInstance;
        if (hb == null || !hb.zoomed || !hb.screenOn || hb.keyguardLocked || hb.layout == null
                || hb.root == null || hb.root.getVisibility() != View.VISIBLE) return null;
        MiStepLayout current = hb.layout;
        return new float[]{current.mainScale, current.mainOffsetY, current.mainOffsetX};
    }

    static float[] activeIslandMonitorTransform() {
        MiStepHost hb = MiStepHost.sInstance;
        if (hb == null || SystemClock.uptimeMillis() < hb.zoomAnimUntil) return null;
        return activeMainTransform();
    }

    static boolean hostSceneActive() {
        MiStepHost hb = MiStepHost.sInstance;
        return hb != null && hb.zoomed && hb.scene == MiStepHost.ACTIVE && hb.screenOn
                && hb.root != null && hb.root.getVisibility() == View.VISIBLE;
    }

    static boolean shouldClampAnimationBounds(Object info, Object change) {
        MiStepHost hb = MiStepHost.sInstance;
        if (hb == null || info == null || change == null) return false;
        try {
            int transitionType = (Integer) XposedHelpers.callMethod(info, "getType");
            int changeMode = (Integer) XposedHelpers.callMethod(change, "getMode");
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null) return false;
            Object token = XposedHelpers.getObjectField(task, "token");
            boolean sideTask = token == null || hb.isSideToken(token) || MiStepHost.windowModeOf(task) == 6;
            int startRotation = -1;
            int endRotation = -1;
            try {
                startRotation = (Integer) XposedHelpers.callMethod(change, "getStartRotation");
                endRotation = (Integer) XposedHelpers.callMethod(change, "getEndRotation");
            } catch (Throwable ignored) { }
            boolean rotating = startRotation >= 0 && endRotation >= 0
                    && startRotation != endRotation;
            return TransitionAnimationPolicy.shouldClampOpenBounds(
                    hostSceneActive(), hb.promotion != null || hb.transferBusy,
                    transitionType, changeMode, sideTask, MiStepHost.windowModeOf(task), rotating);
        } catch (Throwable error) {
            return false;
        }
    }

    static void installLogicalBoundsHooks(ClassLoader cl) {
        if (MiStepHost.boundsHooksReady) return;
        java.util.List<XC_MethodHook.Unhook> installed = new java.util.ArrayList<>();
        try {
            Class<?> view = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskView", cl);
            Class<?> trans = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskViewTransitions", cl);
            Class<?> control = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskViewTaskController", cl);
            installed.addAll(XposedBridge.hookAllMethods(trans, "startAnimation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    MiStepHost hb = MiStepHost.sInstance;
                    if (p.args.length != 6 || hb == null || hb.transitions != p.thisObject) return;
                    java.util.List<MiStepSideClose> closes = hb.closingTasks(p.args[1]);
                    if (closes.isEmpty()) return;
                    p.setObjectExtra("mistep.closes", closes);
                    MiStepHost.nativeCloses.set(closes);
                    MiStepHost.closeFinishTransaction.set(p.args[4]);
                    Object original = p.args[5];
                    Class<?> callback = Class.forName("com.android.wm.shell.transition.Transitions$TransitionFinishCallback", false, cl);
                    java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
                    p.args[5] = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{callback}, (proxy, method, args) -> {
                        if (!"onTransitionFinished".equals(method.getName())) return method.invoke(original, args);
                        if (!finished.compareAndSet(false, true)) return null;
                        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(closes.size());
                        Runnable finish = () -> {
                            if (remaining.decrementAndGet() == 0)
                                XposedHelpers.callMethod(original, "onTransitionFinished", args[0]);
                        };
                        for (MiStepSideClose close : closes) close.animate(finish);
                        return null;
                    });
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    @SuppressWarnings("unchecked") java.util.List<MiStepSideClose> closes =
                            (java.util.List<MiStepSideClose>) p.getObjectExtra("mistep.closes");
                    if (closes == null) return;
                    MiStepHost.nativeCloses.remove();
                    MiStepHost.closeFinishTransaction.remove();
                    if (p.hasThrowable() || !Boolean.TRUE.equals(p.getResult()))
                        for (MiStepSideClose close : closes) close.abandon();
                }
            }));
            installed.addAll(XposedBridge.hookAllMethods(control, "prepareCloseAnimation", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    java.util.List<MiStepSideClose> closes = MiStepHost.nativeCloses.get();
                    if (closes == null || p.hasThrowable() || p.args.length != 2) return;
                    for (MiStepSideClose close : closes)
                        if (close.controller == p.thisObject && close.surface == p.args[1]) {
                            try { close.prepare(p.args[0], MiStepHost.closeFinishTransaction.get()); }
                            catch (Throwable error) { MiStepHost.log("side close prepare: " + error); }
                        }
                }
            }));
            installed.add(XposedHelpers.findAndHookMethod(view, "onComputeInternalInsets",
                    XposedHelpers.findClass("android.view.ViewTreeObserver$InternalInsetsInfo", cl),
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost hb = MiStepHost.sInstance;
                    // TaskView normally punches its app hole out of the Host. Mi Step
                    // owns the entire side strip for tap-to-promote. A moved/retired
                    // TaskView must not subtract another cell after our Host listener.
                    if (hb != null && hb.ownsViewOrController(p.thisObject)) p.setResult(null);
                }
            }));
            installed.add(XposedHelpers.findAndHookMethod(view, "getCurrentBoundsOnScreen", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost hb = MiStepHost.sInstance;
                    if (hb != null && hb.ownsViewOrController(p.thisObject)) p.setResult(hb.logicalBounds(hb.slotForObject(p.thisObject)));
                }
            }));
            XC_MethodHook rectGuard = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost hb = MiStepHost.sInstance;
                    if (hb != null && hb.transitions == p.thisObject && hb.ownsViewOrController(p.args[0]))
                        p.args[1] = hb.logicalBounds(hb.slotForObject(p.args[0]));
                }
            };
            installed.add(XposedHelpers.findAndHookMethod(trans, "setTaskBounds", control, Rect.class, rectGuard));
            installed.add(XposedHelpers.findAndHookMethod(trans, "lambda$setTaskBounds$6", control, Rect.class, rectGuard));
            java.util.Set<XC_MethodHook.Unhook> external = XposedBridge.hookAllMethods(trans,
                    "isValidTaskView", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost hb = MiStepHost.sInstance;
                    if (hb != null && hb.transitions == p.thisObject && p.args.length == 2
                            && p.args[1] == null && hb.leaveExternalChangeToNative(p.args[0]))
                        p.setResult(false);
                }
            });
            if (external.isEmpty()) throw new IllegalStateException("no external task change guard");
            installed.addAll(external);
            java.util.Set<XC_MethodHook.Unhook> unfold = XposedBridge.hookAllMethods(trans, "updateBoundsForUnfold", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost hb = MiStepHost.sInstance;
                    if (hb == null || hb.transitions != p.thisObject || p.args.length < 2
                            || !(p.args[0] instanceof Rect)) return;
                    for (Object arg : p.args) {
                        if (arg == null || !arg.getClass().getName().contains("RunningTaskInfo")) continue;
                        Object controller = XposedHelpers.callMethod(p.thisObject, "findTaskView", arg);
                        if (hb.ownsViewOrController(controller)) {
                            for (Slot slot : hb.slots) {
                                if (slot.controller == controller && !slot.parked
                                        && hb.controllerOwns(slot, XposedHelpers.getIntField(arg, "taskId"),
                                        XposedHelpers.getObjectField(arg, "token"))) p.args[0] = hb.logicalBounds(slot);
                            }
                        }
                    }
                }
            });
            if (unfold.isEmpty()) throw new IllegalStateException("no unfold bounds guard");
            installed.addAll(unfold);
            java.util.Set<XC_MethodHook.Unhook> opening = XposedBridge.hookAllMethods(trans, "prepareOpenAnimation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost hb = MiStepHost.sInstance;
                    if (hb != null && hb.transitions == p.thisObject && p.args.length == 7
                            && hb.ownsViewOrController(p.args[0])) {
                        Object info = p.args[4];
                        Object token = XposedHelpers.getObjectField(info, "token");
                        Object wct = p.args[6];
                        XposedHelpers.callMethod(wct, "setForceTranslucent", token, true);
                        XposedHelpers.callMethod(wct, "setAlwaysOnTop", token, true);
                        XposedHelpers.callMethod(wct, "setFocusable", token, true);
                        Slot slot = hb.slotForObject(p.args[0]);
                        XposedHelpers.callMethod(wct, "setBounds", token, hb.logicalBounds(slot));
                        hb.applyTopConfiguration(wct, token, slot);
                    }
                }
            });
            if (opening.isEmpty()) throw new IllegalStateException("no native opening guard");
            installed.addAll(opening);
            MiStepHost.boundsHooksReady = true;
            MiStepHost.log("logical bounds guards installed: getter/queue/consumer/unfold");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : installed) hook.unhook();
            MiStepHost.log("logical bounds guards unavailable; no task admission: " + error);
        }
    }

    Rect logicalBounds() { return new Rect(0, 0, h.screenW, h.screenH); }

    Rect logicalBounds(Slot slot) {
        if(slot==null || slot.index!=MiStepHost.SLOT_TOP)return logicalBounds();
        TopTaskGeometry upper=h.topGeometry();
        return new Rect(0,0,upper.logicalWidth,upper.logicalHeight);
    }

    Slot slotForObject(Object object) {
        for(Slot slot:h.slots) if(slot.view==object || slot.controller==object)return slot;
        return null;
    }

    void readDisplayGeometry() {
        android.util.DisplayMetrics metrics = h.ctx.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        try {
            if (h.displayManager == null) {
                h.displayManager = (DisplayManager) h.ctx.getSystemService(Context.DISPLAY_SERVICE);
            }
            Display display = h.displayManager == null
                    ? null : h.displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                Point realSize = new Point();
                display.getRealSize(realSize);
                if (realSize.x > 0 && realSize.y > 0) {
                    width = realSize.x;
                    height = realSize.y;
                }
                h.displayRotation = display.getRotation();
            }
        } catch (Throwable error) {
            MiStepHost.log("display geometry read fell back to resources: " + error);
        }
        if (width > 0 && height > 0) {
            // Mi Step always has one portrait workspace. A task's fixed rotation or
            // a pending display transition must not rebuild it as a landscape scene.
            h.screenW = FullscreenRotationPolicy.workspaceWidth(width, height);
            h.screenH = FullscreenRotationPolicy.workspaceHeight(width, height);
        }
    }

    void scheduleDisplayGeometryRefresh() {
        if (h.geometryRefreshQueued) return;
        h.geometryRefreshQueued = true;
        h.main.postDelayed(() -> {
            h.geometryRefreshQueued = false;
            refreshDisplayGeometry();
        }, 120L);
    }

    void refreshDisplayGeometry() {
        if (h.root == null || h.displayManager == null) return;
        if (h.cellMotion != null || h.promotion != null && h.promotion.nativeSwap) {
            int oldWidth = h.screenW;
            int oldHeight = h.screenH;
            int oldRotation = h.displayRotation;
            readDisplayGeometry();
            int newWidth = h.screenW;
            int newHeight = h.screenH;
            int newRotation = h.displayRotation;
            // Display notifications also report non-geometric changes. Keep the
            // in-flight coordinate space intact until its finish or rollback.
            h.screenW = oldWidth;
            h.screenH = oldHeight;
            h.displayRotation = oldRotation;
            if (oldWidth == newWidth && oldHeight == newHeight && oldRotation == newRotation) {
                if (h.cellMotion != null) MiStepHost.log("unchanged display geometry retains real cell motion");
                return;
            }
            if (h.cellMotion != null) {
                h.cellMotion.finish();
                scheduleDisplayGeometryRefresh();
                return;
            }
            h.geometryRefreshAfterLive = true;
            h.failPromotion(h.promotion, "display geometry changed during live swap "
                    + oldWidth + "x" + oldHeight + " r" + oldRotation
                    + " -> " + newWidth + "x" + newHeight + " r" + newRotation);
            return;
        }
        if (h.transferBusy || h.swapPreview != null || h.cellMovePreview != null) {
            if (h.geometryRefreshDeferrals++ < 8) {
                scheduleDisplayGeometryRefresh();
                return;
            }
        }
        h.geometryRefreshDeferrals = 0;
        int oldWidth = h.screenW;
        int oldHeight = h.screenH;
        int oldRotation = h.displayRotation;
        readDisplayGeometry();
        if (NativeLandscapePolicy.shouldRestoreFromDisplay(
                h.landscapeSuspended, h.scene == MiStepHost.SUSPENDED, h.displayRotation)) {
            MiStepHost.log("restore workspace after native landscape rotation=" + h.displayRotation);
            h.resumeSession();
            return;
        }
        if (h.displayRotation != FullscreenRotationPolicy.portraitDisplayRotation()) {
            if (oldRotation != h.displayRotation) {
                MiStepHost.log("workspace retains portrait geometry during display rotation "
                        + h.displayRotation + "; waiting for WM portrait configuration");
            }
            return;
        }
        if ((oldWidth == h.screenW && oldHeight == h.screenH && oldRotation == h.displayRotation)
                || h.screenW <= 0 || h.screenH <= 0) return;

        h.layout = h.makeAdaptiveLayout(h.mainOnRight);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, h.layout.topStrip);
        if (h.topStripBacking != null) h.topStripBacking.setLayoutParams(topParams);
        if (h.topBar != null) {
            h.topBar.setLayoutParams(new FrameLayout.LayoutParams(-1, h.layout.topStrip));
            h.topBar.setStatusBarInset(DeviceProfile.statusBarHeightPx(h.ctx));
        }
        for (int i = 1; i < MiStepHost.SLOT_COUNT; i++) {
            Slot slot = h.slots[i];
            slot.rect = h.slotBounds(slot);
            h.applyRect(slot);
            h.updateTouchRegion(slot);
            h.updateHint(slot);
            View backing = h.sideBackingViews[i];
            if (backing != null) {
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        h.layout.sideWidth, h.sideSegmentHeight(i));
                params.leftMargin = h.layout.mainOnRight ? 0 : h.layout.mainWidth;
                params.topMargin = h.sideSegmentTop(i);
                backing.setLayoutParams(params);
            }
            if (slot.carrierTapView != null) {
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
                params.leftMargin = slot.rect[0];
                params.topMargin = slot.rect[1];
                slot.carrierTapView.setLayoutParams(params);
            }
        }
        if (h.carrierBackdrop != null) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    h.layout.mainDispWidth, h.layout.mainDispHeight);
            params.leftMargin = h.layout.mainOffsetX;
            params.topMargin = h.layout.mainOffsetY;
            h.carrierBackdrop.setLayoutParams(params);
        }
        h.applyWallpaperBackdrops();
        if (h.carrierSlotIndex >= 0) h.reassertCarrier();
        if (h.wm != null && h.lp != null) {
            try { h.wm.updateViewLayout(h.root, h.lp); }
            catch (Throwable error) { MiStepHost.log("host window resize refresh failed: " + error); }
        }
        h.root.requestLayout();
        h.root.invalidate();
        boolean activeRoot = ZoomLifecyclePolicy.shouldReassertGeometry(
                h.scene == MiStepHost.ACTIVE, h.root.getVisibility() == View.VISIBLE, h.screenOn);
        if (activeRoot) h.writeZoom(true);
        else MiStepHost.log("display geometry changed while host inactive; skip zoom/task reassert");
        boolean queued = activeRoot && h.executeOnShell(() -> {
            if (h.scene != MiStepHost.ACTIVE) return;
            for (Slot slot : h.slots) {
                if (slot.index > MiStepHost.SLOT_MAIN && slot.occupied && !slot.carrier
                        && !slot.promoting && !slot.launching) h.applyFullscreenBounds(slot);
            }
        });
        h.main.postDelayed(() -> {
            if (!ZoomLifecyclePolicy.shouldReassertGeometry(h.scene == MiStepHost.ACTIVE,
                    h.root != null && h.root.getVisibility() == View.VISIBLE, h.screenOn)) return;
            for (Slot slot : h.slots) {
                if (slot.index > MiStepHost.SLOT_MAIN && slot.occupied && !slot.carrier) h.reapplyScale(slot);
            }
        }, queued ? 180L : 0L);
        MiStepHost.log("display geometry changed " + oldWidth + "x" + oldHeight + " r" + oldRotation
                + " -> " + h.screenW + "x" + h.screenH + " r" + h.displayRotation
                + " queuedTaskBounds=" + queued + " " + h.layout.describe());
    }

}
