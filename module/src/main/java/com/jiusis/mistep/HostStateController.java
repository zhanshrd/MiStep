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

/** 任务用户缓存 / 根枚举 / 原生观察 / 缩放状态 / 副区挂起状态 / 接收器注册 / 提升看门狗 / 收养前检 */
final class HostStateController {
    private final MiStepHost h;

    HostStateController(MiStepHost host) { h = host; }

    MiStepHost.NativeObservation observeNativeCommit(Object token, Runnable check) {
        MiStepHost.NativeObservation observation = h.new NativeObservation(token, check);
        h.pendingNativeObservation = observation;
        h.main.postDelayed(observation, 350L); // Fallback if the target omits the transition/commit callback.
        return observation;
    }

    void cancelNativeObservation(MiStepHost.NativeObservation observation) {
        if (observation == null) return;
        observation.consumed.set(true);
        h.main.removeCallbacks(observation);
        if (h.pendingNativeObservation == observation) h.pendingNativeObservation = null;
    }

    public static void setPendingMainSide(boolean mainOnRight) {
        MiStepHost host = MiStepHost.sInstance;
        // 过渡期间（RESUMING/SUSPENDING）的滑动不得改写下一次打开的方向：
        // consumePendingMainSide 已在恢复会话时消费，晚到的写入会污染下次打开的朝向。
        // ACTIVE 时热区手势本就在 HotZoneGesture 源头被丢弃，此守卫为防御性冗余；
        // 关闭工作区走顶栏 ✕ / TOGGLE 广播 / Home、Recents / 息屏，没有“再滑一次关闭”。
        if (host != null && host.scene != MiStepHost.SUSPENDED) return;
        MiStepHost.pendingMainSide = mainOnRight ? 1 : 0;
    }

    void consumePendingMainSide() {
        int pending = MiStepHost.pendingMainSide;
        if (pending < 0) return;
        MiStepHost.pendingMainSide = -1;
        boolean right = pending == 1;
        if (right != h.mainOnRight) {
            MiStepHost.log("pending main side applied: " + (right ? "right" : "left"));
            setMainSide(right);
        }
    }

    java.util.Set<Integer> supportedTaskUsers() {
        long now = SystemClock.uptimeMillis();
        java.util.Set<Integer> cached = h.taskUserIds;
        if (cached != null && now - h.taskUserIdsReadAt < 5000L) return cached;
        synchronized (h.taskUsersLock) {
            cached = h.taskUserIds;
            now = SystemClock.uptimeMillis();
            if (cached != null && now - h.taskUserIdsReadAt < 5000L) return cached;
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            int currentUser = h.currentUserId();
            ids.add(currentUser);
            try {
                UserManager users = h.ctx.getSystemService(UserManager.class);
                if (users != null) {
                    java.util.List<UserHandle> profiles = users.getUserProfiles();
                    if (profiles != null) {
                        for (UserHandle profile : profiles) {
                            if (profile != null) {
                                ids.add((Integer) XposedHelpers.callMethod(profile, "getIdentifier"));
                            }
                        }
                    }
                }
                h.taskUserIdsReadError = null;
            } catch (Throwable error) {
                String message = String.valueOf(error);
                if (!message.equals(h.taskUserIdsReadError)) {
                    MiStepHost.log("associated task profiles unavailable; owner only: " + message);
                    h.taskUserIdsReadError = message;
                }
            }
            h.taskUserIds = java.util.Collections.unmodifiableSet(ids);
            h.taskUserIdsReadAt = now;
            MiStepHost.log("task profile scope current=" + currentUser + " associated=" + ids);
            return h.taskUserIds;
        }
    }

    boolean ownsViewOrController(Object object) {
        if (object == null) return false;
        for (Slot slot : h.slots)
            if (slot.index > 0 && !slot.parked && (slot.view == object || slot.controller == object)) return true;
        return false;
    }

    boolean leaveExternalChangeToNative(Object change) {
        if (change == null || h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked || h.transferBusy) return false;
        try {
            int mode = (Integer) XposedHelpers.callMethod(change, "getMode");
            if (mode != 6 && mode != 3) return false;
            Object info = XposedHelpers.callMethod(change, "getTaskInfo");
            if (!ordinaryRoot(info, 1)) return false;
            int taskId = XposedHelpers.getIntField(info, "taskId");
            Object token = XposedHelpers.getObjectField(info, "token");
            for (Slot slot : h.slots) {
                if (slot.index <= 0 || !h.isActive(slot) || !slot.occupied || slot.parked
                        || slot.frozen || slot.launching || slot.modeAdmissionPending
                        || slot.taskId != taskId || token == null || !token.equals(slot.taskToken)
                        || !h.controllerOwns(slot, taskId, token)) continue;
                Promotion current = h.promotion;
                if (current != null && (!current.externalLaunch || current.slot != slot
                        || current.sceneEpoch != h.sceneEpoch || current.bindingEpoch != slot.taskEpoch))
                    return false;
                Object queue = XposedHelpers.getObjectField(h.transitions, "mPending");
                if (!(queue instanceof Iterable<?>)) return false;
                for (Object pending : (Iterable<?>) queue)
                    if (XposedHelpers.getObjectField(pending, "mTaskView") == slot.controller) return false;
                Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                    Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                            organizer, "mTasks")).get(taskId);
                    Object canonical = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                    if (!ordinaryRoot(canonical, 1) || !token.equals(XposedHelpers.getObjectField(canonical, "token"))
                            || XposedHelpers.getIntField(canonical, "taskId") != taskId
                            || XposedHelpers.getIntField(canonical, "userId") != XposedHelpers.getIntField(info, "userId"))
                        return false;
                }
                MiStepHost.log("external fullscreen change kept native task=" + taskId + " slot=" + slot.index);
                return true;
            }
        } catch (Throwable unavailable) { MiStepHost.log("external native change guard unavailable: " + unavailable); }
        return false;
    }

    java.util.List<?> rootInfos() throws Exception {
        Object service = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityTaskManager"), "getService");
        return (java.util.List<?>) XposedHelpers.callMethod(service, "getAllRootTaskInfos");
    }

    Object rootInfo(LiveTask task) throws Exception {
        for (Object info : rootInfos()) if (task.matches(info)) return info;
        return null;
    }

    boolean ordinaryRoot(Object info, int mode) {
        if (info == null || XposedHelpers.getIntField(info, "displayId") != 0
                || !h.supportsTaskUser(XposedHelpers.getIntField(info, "userId"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                || !Integer.valueOf(mode).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getActivityType"))) return false;
        ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
        return base != null && !"com.miui.home".equals(base.getPackageName());
    }

    boolean mainTaskVisible(Object info) {
        return info != null && TaskUserPolicy.mayBeMainTask(
                XposedHelpers.getIntField(info, "userId"), h.currentUserId(), supportedTaskUsers(),
                XposedHelpers.getBooleanField(info, "isVisible"),
                XposedHelpers.getBooleanField(info, "isFocused"), false);
    }

    boolean isSideToken(Object token) {
        for (Slot slot : h.slots) if (!slot.parked && token != null && token.equals(slot.taskToken)) return true;
        return false;
    }

    void checkAdoption(Slot slot, LiveTask task, long epoch, int attempt,
            Runnable success, Consumer<String> failure) {
        if (!h.isActive(slot) || epoch != h.sceneEpoch) {
            h.adoptionFailed(slot, task, true, "scene changed during adoption", failure);
            return;
        }
        if (!h.executeOnShell(() -> {
            boolean ready = false;
            String detail;
            try {
                Object core = rootInfo(task);
                ready = ordinaryRoot(core, 6) && XposedHelpers.getBooleanField(core, "isVisible")
                        && h.controllerOwns(slot, task.id, task.token)
                        && MiStepHost.getSurfaceField(slot.controller, "mTaskLeash") != null;
                detail = "core/controller/leash=" + ready;
            } catch (Throwable error) { detail = error.toString(); }
            final boolean confirmed = ready;
            final String result = detail;
            h.main.post(() -> {
                if (epoch != h.sceneEpoch) {
                    h.adoptionFailed(slot, task, true, "stale adoption completion", failure);
                } else if (confirmed) {
                    h.bindTask(slot, task.id, task.token);
                    slot.pkg = task.pkg;
                    slot.occupied = true;
                    slot.taskVisible = true;
                    slot.launching = false;
                    slot.windowMode = 6; // Confirmed from Core and this controller above.
                    slot.promoting = false;
                    slot.parked = false;
                    h.transferBusy = false;
                    h.updateHint(slot);
                    h.updateTouchRegion(slot);
                    h.reapplyScale(slot);
                    h.publishDesktopSideTasks();
                    MiStepHost.log("adoption confirmed same task=" + task.id + " slot=" + slot.index);
                    if(slot.index==MiStepHost.SLOT_TOP)MiStepTopNativeCaption.refresh();
                    success.run();
                    h.main.postDelayed(() -> {
                        h.compactCellsAfterCollection();
                        // TaskView insertion can briefly hide sibling surfaces while the
                        // native transition settles. Re-show every already-owned cell
                        // without moving tasks or changing ownership.
                        h.main.postDelayed(() -> h.reassertSideTaskViews("adoption-settled"), 420L);
                    }, 120L);
                } else if (attempt < 2) {
                    h.main.postDelayed(() -> checkAdoption(slot, task, epoch, attempt + 1, success, failure), 500L);
                } else h.adoptionFailed(slot, task, true, result, failure);
            });
        })) h.adoptionFailed(slot, task, true, "no Shell queue for confirmation", failure);
    }

    void setZoomState(boolean on) {
        on = on && !h.keyguardLocked && h.screenOn;
        h.zoomed = on;
        HostEntry.setHostZoomed(on);
        MiStepStatusBarSampling.geometryChanged();
        HotZoneGesture.raiseAll(h.ctx);
    }

    String zoomOnCommand() { return h.zoomTransport.zoomOnCommand(); }

    void writeZoomAnimated(boolean on) { h.zoomTransport.writeZoomAnimated(on); }

    void writeZoom(boolean on) { h.zoomTransport.writeZoom(on); }

    void writeZoomLine(String command) { h.zoomTransport.writeZoomLine(command); }

    void writeZoomLine(String command, long issuedAt) { h.zoomTransport.writeZoomLine(command, issuedAt); }

    void startPromotionWatchdog() {
        h.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (h.root == null || MiStepHost.sInstance != h) {
                    return;
                }
                if (h.promotion == null) {
                    h.promotionWatchdogSince = 0L;
                    h.promotionWatchdogNudged = false;
                } else {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (h.promotionWatchdogSince == 0L) {
                        h.promotionWatchdogSince = now;
                    }
                    long stuckMs = now - h.promotionWatchdogSince;
                    if (stuckMs > 8000L && !h.promotionWatchdogNudged) {
                        h.promotionWatchdogNudged = true;
                        MiStepHost.log("promotion watchdog: task=" + h.promotion.taskId + " stuck "
                                + (stuckMs / 1000L) + "s, requesting live-swap cancellation");
                        h.promotionController.requestLiveSwapCancellation(h.promotion, "watchdog");
                    }
                    if (stuckMs > 20000L) {
                        MiStepHost.log("promotion watchdog: task=" + h.promotion.taskId + " stuck "
                                + (stuckMs / 1000L) + "s, forcing failPromotion");
                        h.promotionWatchdogSince = now;
                        h.promotionWatchdogNudged = false;
                        h.failPromotion(h.promotion, "promotion watchdog deadline");
                    }
                }
                h.main.postDelayed(this, 4000L);
            }
        }, 4000L);
    }

    void registerDefaultDisplayListener() {
        if (h.displayListener != null) return;
        try {
            h.displayManager = (DisplayManager) h.ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (h.displayManager == null) throw new IllegalStateException("DisplayManager unavailable");
            h.displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) h.scheduleDisplayGeometryRefresh();
                }
            };
            h.displayManager.registerDisplayListener(h.displayListener, h.main);
            MiStepHost.log("default display geometry listener registered");
        } catch (Throwable error) {
            h.displayListener = null;
            MiStepHost.log("default display geometry listener unavailable: " + error);
        }
    }

    void registerDesktopIconReceiver() {
        if (h.desktopIconReceiver != null) return;
        try {
            h.desktopIconReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent != null && intent.getBooleanExtra("before", false)) {
                        h.preannounceDesktopLaunch(intent.getBundleExtra("binding"));
                        return;
                    }
                    String pkg = intent == null ? null : intent.getStringExtra("package");
                    h.promoteSideTaskForDesktopLaunch(pkg, intent == null ? -1 : intent.getIntExtra("userId", -1));
                }
            };
            IntentFilter filter = new IntentFilter(MiStepHost.DESKTOP_ICON_LAUNCH_ACTION);
            h.ctx.registerReceiver(h.desktopIconReceiver, filter,
                    "android.permission.MANAGE_ACTIVITY_TASKS", h.main,
                    Context.RECEIVER_EXPORTED);
            MiStepHost.log("desktop icon launch receiver registered");
        } catch (Throwable error) {
            h.desktopIconReceiver = null;
            MiStepHost.log("desktop icon launch receiver failed: " + error);
        }
    }

    void setMainSide(boolean right) {
        if (h.layout != null && h.mainOnRight == right) return;
        try {
            h.applyMainLayout(h.makeAdaptiveLayout(right));
            // Reopening from the opposite corner prepares Host geometry first.
            // The entry frame gate owns its animated zoom; an early steady write
            // would shrink the real main task before the backdrop is drawable.
            if (h.root != null && h.scene == MiStepHost.ACTIVE && !h.workspaceEntering) writeZoom(true);
        } catch (Throwable error) {
            MiStepHost.log("main side switch failed: " + error);
        }
    }

}
