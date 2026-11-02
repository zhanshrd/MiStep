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

/** 任务事件回调 / slot 创建与清除 / 侧格可见性修复 */
final class HostTaskEventController {
    private final MiStepHost h;

    HostTaskEventController(MiStepHost host) { h = host; }

    void createSubViews() {
        createNext(1);
    }

    void createNext(final int index) {
        if (index >= MiStepHost.SLOT_COUNT) {
            MiStepHost.log("three side and one upper TaskView ready; system main region has no TaskView");
            return;
        }
        Consumer<Object> onCreated = new Consumer<Object>() {
            @Override
            public void accept(Object taskViewObj) {
                MiStepHost.log("TaskView created slot=" + index + " -> " + taskViewObj);
                h.main.post(() -> onTaskViewCreated(index, taskViewObj));
            }
        };
        XposedHelpers.callMethod(h.factory, "create", h.ctx, h.executor, onCreated);
    }

    void onTaskViewCreated(int index, Object taskViewObj) {
        try {
            MiStepHost.Slot slot = h.slots[index];
            View view = (View) taskViewObj;
            slot.view = view;
            slot.controller = XposedHelpers.callMethod(view, "getController");
            slot.rect = h.slotBounds(slot);

            Object listener = makeListener(index);
            XposedHelpers.callMethod(view, "setListener", h.executor, listener);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
            if (!MiStepHost.boundsHooksReady) throw new IllegalStateException("logical bounds hooks unavailable");
            h.root.addView(view, lp);
            h.applyRect(slot);
            h.ensureCellBackground(slot);

            if(index==MiStepHost.SLOT_TOP) {
                h.createTopTaskControls();
                h.updateHint(slot); h.updateTouchRegion(slot);
                MiStepHost.log("upper landscape TaskView ready");
                createNext(index+1); return;
            }
            slot.hint = h.makeEmptyHint();
            slot.hint.setOnClickListener(v -> h.onSlotTapped(slot.index));
            h.root.addView(slot.hint, h.hintParams(slot));
            // 修：hint 在 applyRect 之后才创建，之前一直停在 (0,0)（屏幕上左上角那个多余的加号）。
            h.applyRect(slot);
            h.updateHint(slot);

            if (h.workspaceAlpha < 1f) h.setWorkspaceAlpha(h.workspaceAlpha);

            view.setOnClickListener(v -> h.onSlotTapped(h.slotIndexOfView(v)));
            view.setOnTouchListener((v, e) -> canRender(slot) && h.scene == MiStepHost.ACTIVE && h.onSlotTouch(slot.index, e));
            h.updateTouchRegion(slot);
            MiStepHost.log("slot " + index + " attached rect=" + java.util.Arrays.toString(slot.rect));
        } catch (Throwable t) {
            MiStepHost.log("onTaskViewCreated slot=" + index + " FAILED: " + t);
            XposedBridge.log(t);
        }
        createNext(index + 1);
    }

    void resetMainTaskLeash(int taskId) {
        if (taskId < 0) return;
        try {
            Object organizer = h.shellOrganizer();
            if (organizer == null) return;
            Object leash = XposedHelpers.callMethod(organizer, "getTaskLeash",
                    new Class<?>[]{int.class}, taskId);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                return;
            }
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class, float.class}, leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setMatrix",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class, float.class, float.class, float.class},
                        leash, 1f, 0f, 0f, 1f);
                XposedHelpers.callMethod(tx, "setAlpha",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class}, leash, 1f);
                XposedHelpers.callMethod(tx, "setCrop",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                android.graphics.Rect.class}, leash, null);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            MiStepHost.log("main task leash reset task=" + taskId);
        } catch (Throwable error) {
            MiStepHost.log("reset main task leash failed task=" + taskId + ": " + error);
        }
    }

    void clearSlot(MiStepHost.Slot slot) {
        if(slot.index==MiStepHost.SLOT_TOP && h.topCtrl.topTaskState.owner()!=null) {
            h.topCtrl.topTaskState.release(h.topCtrl.topTaskState.owner(),false); h.topCtrl.topOriginalSlot=null;
        }
        if(slot.index==MiStepHost.SLOT_TOP)h.topCtrl.releaseRetainedTopSnapshot();
        slot.taskEpoch++;
        slot.removalCheckTask = -1;
        slot.retiredTaskId = slot.taskId;
        slot.occupied = false;
        slot.parked = false;
        slot.frozen = false;
        slot.promoting = false;
        slot.taskVisible = false;
        slot.taskId = -1;
        slot.taskToken = null;
        slot.windowMode = -1;
        slot.nativeReleasePending = false;
        slot.pendingSidePromotion = null;
        slot.launching = false;
        slot.injecting = false;
        slot.modeGuardUntil = 0L;
        if (slot.windowState != null) {
            slot.windowState.clear();
            synchronized (h.taskWindowStatesLock) {
                h.taskWindowStates.remove(slot.windowState.taskId);
            }
            slot.windowState = null;
        }
        slot.taskUid = 0;
        slot.pkg = null;
        slot.modeAdmissionPending = false;
        slot.modeRepairAttempts = 0;
        slot.scaleSurface = null;
        slot.scaleOnLeash = false;
        slot.scaleTaskId = Integer.MIN_VALUE;
        slot.scaleApplied = -1f;
        slot.scaleAttempts = 0;
        slot.reassertPending = false;
        slot.visibilityRepair.reset();
        h.recycleBuffered(slot);
        h.updateHint(slot);
        h.updateTouchRegion(slot);
        h.publishDesktopSideTasks();
        h.requestCompaction();
    }

    boolean isActive(MiStepHost.Slot slot) {
        return h.root != null && MiStepHost.sInstance == h && slot.index > 0
                && slot.index < MiStepHost.SLOT_COUNT && h.slots[slot.index] == slot;
    }

    boolean canRender(MiStepHost.Slot slot) {
        return isActive(slot) && !slot.parked && h.screenOn
                && (h.scene == MiStepHost.ACTIVE || h.scene == MiStepHost.RESUMING)
                && h.root.getVisibility() == View.VISIBLE;
    }

    void postForSlot(MiStepHost.Slot slot, Consumer<MiStepHost.Slot> callback) {
        h.main.post(() -> { if (isActive(slot)) callback.accept(slot); });
    }

    boolean controllerOwns(MiStepHost.Slot slot, int taskId, Object token) {
        try {
            Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            if (info != null) {
                ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
                if (base != null && "com.miui.home".equals(base.getPackageName())) return false;
            }
            return info != null && current != null
                    && XposedHelpers.getIntField(info, "taskId") == taskId
                    && (token == null || token.equals(current));
        } catch (Throwable t) {
            return false;
        }
    }

    void bindTask(MiStepHost.Slot slot, int taskId, Object token) { h.promotionController.bindTask(slot, taskId, token); }

    void resetIdleTaskView(MiStepHost.Slot slot) {
        if (slot == null || slot.controller == null) return;
        try {
            MiStepIdleTaskView.reset(slot.controller, text -> MiStepHost.log(text + " slot=" + slot.index));
        } catch (Throwable error) {
            MiStepHost.log("reset idle TaskView slot=" + (slot == null ? -1 : slot.index) + " failed: " + error);
        }
    }

    void startSlot(final MiStepHost.Slot slot, Intent launch, String label) {
        if (!isActive(slot) || h.scene != MiStepHost.ACTIVE || slot.promoting) { slot.launching = false; return; }
        final int index = slot.index;
        resetIdleTaskView(slot);
        h.prepareCellSurface(slot);
        slot.modeGuardUntil = SystemClock.uptimeMillis() + 2500L;
        slot.modeRepairAttempts = 0;
        slot.modeAdmissionPending = true;
        slot.pkg = launch.getComponent() != null
                ? launch.getComponent().getPackageName() : launch.getPackage();
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // v0.9：任务一律按"整屏手机"起（1080×2460），完整 UI 不受窗口最小宽限制；
        // 显示时用矩阵缩放塞进格子（官方Mi Step的等比缩小语义）。
        Rect rect = new Rect(0, 0, h.screenW, h.screenH);
        PendingIntent pi = PendingIntent.getActivity(h.ctx, 1000 + index, launch,
                PendingIntent.FLAG_IMMUTABLE);
        ActivityOptions options = ActivityOptions.makeBasic();
        MiStepHost.callHidden(options, "setLaunchWindowingMode", 6);          // WINDOWING_MODE_MULTI_WINDOW
        MiStepHost.callHidden(options, "setRemoveWithTaskOrganizer", Boolean.FALSE);
        try {
            Method start = MiStepHost.findStartActivity(slot.view.getClass());
            if (start == null) {
                slot.launching = false;
                MiStepHost.log("startSlot " + index + ": startActivity not found");
                return;
            }
            start.setAccessible(true);
            start.invoke(slot.view, pi, launch, options, rect);
            h.launchCurrentSlot = slot;
            h.main.postDelayed(() -> {
                if (h.launchCurrentSlot == slot) {
                    h.launchCurrentSlot = null;
                    slot.launching = false;
                    MiStepHost.log("launch watchdog fired slot=" + index + " label=" + label);
                    h.finishPendingSuspend();
                }
            }, MiStepHost.LAUNCH_TIMEOUT_MS);
            MiStepHost.log("startActivity slot=" + index + " label=" + label
                    + " pkg=" + launch.getPackage() + " rect=" + rect);
        } catch (Throwable t) {
            slot.launching = false;
            MiStepHost.log("startSlot " + index + " FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    Object makeListener(final int slotIndex) {
        final MiStepHost.Slot owner = h.slots[slotIndex];
        try {
            Class<?> iface = XposedHelpers.findClass(
                    "com.android.wm.shell.taskview.TaskView$Listener", h.ctx.getClassLoader());
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                String name = method.getName();
                                if ("onTaskCreated".equals(name) && args != null && args.length >= 1) {
                                    final int taskId = (Integer) args[0];
                                    postForSlot(owner, s -> onTaskIdKnown(s.index, taskId));
                                } else if ("onTaskInfoChanged".equals(name)
                                        && args != null && args.length >= 1) {
                                    final Object info = args[0];
                                    postForSlot(owner, s -> onTaskInfo(s.index, info));
                                } else if ("onTaskVisibilityChanged".equals(name)
                                        && args != null && args.length >= 2) {
                                    final int taskId = (Integer) args[0];
                                    final boolean visible = (Boolean) args[1];
                                    postForSlot(owner, s -> onTaskVisibility(s.index, taskId, visible));
                                } else if ("onTaskRemovalStarted".equals(name)
                                        && args != null && args.length >= 1) {
                                    final int taskId = (Integer) args[0];
                                    postForSlot(owner, s -> onTaskRemovalStarted(s.index, taskId));
                                } else if ("onInitialized".equals(name)) {
                                    MiStepHost.log("listener#onInitialized slot=" + owner.index);
                                    postForSlot(owner, s -> { h.updateHint(s); h.setWorkspaceAlpha(h.workspaceAlpha); });
                                } else {
                                    MiStepHost.log("listener#" + name + " slot=" + slotIndex);
                                }
                            } catch (Throwable t) {
                                MiStepHost.log("listener dispatch failed: " + t);
                            }
                            return null;
                        }
                    });
        } catch (Throwable t) {
            MiStepHost.log("makeListener failed: " + t);
            return null;
        }
    }

    void onTaskIdKnown(int slotIndex, int taskId) {
        MiStepHost.Slot slot = h.slots[slotIndex];
        if (slot.promoting || slot.parked || slot.retiredTaskId == taskId || !controllerOwns(slot, taskId, null)) return;
        Object token = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
        if (token == null || !controllerOwns(slot, taskId, token)) return;
        bindTask(slot, taskId, token);
        slot.occupied = true;
        slot.launching = false;
        if (slot == h.launchCurrentSlot) {
            h.launchCurrentSlot = null;
        }
        MiStepHost.log("onTaskCreated slot=" + slotIndex + " taskId=" + taskId);
        h.updateHint(slot);
        h.updateTouchRegion(slot);
        h.finishPendingSuspend();
        h.main.postDelayed(h::compactCells, 120L);
        if (slotIndex > MiStepHost.SLOT_MAIN) h.scheduleSideModeResolution(slot, taskId);
    }

    void onTaskInfo(int slotIndex, Object info) {
        MiStepHost.Slot slot = h.slots[slotIndex];
        try {
            int taskId = XposedHelpers.getIntField(info, "taskId");
            if (slot.promoting || slot.parked || slot.retiredTaskId == taskId) return;
            boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
            boolean running = XposedHelpers.getBooleanField(info, "isRunning");
            Object token = XposedHelpers.getObjectField(info, "token");
            int mode = MiStepHost.windowModeOf(info);
            if(slotIndex==MiStepHost.SLOT_TOP && mode==1 && slot.occupied && taskId==slot.taskId
                    && token!=null && token.equals(slot.taskToken)) {
                h.topCtrl.releaseTopTask(true,false,null);return;
            }
            if (slotIndex > MiStepHost.SLOT_MAIN && mode == 5 && slot.occupied
                    && taskId == slot.taskId && token != null && token.equals(slot.taskToken)) {
                h.releaseNativeFreeformSide(slot, taskId, token);
                return;
            }
            if (token == null || !controllerOwns(slot, taskId, token)) return;
            boolean surfaceGeometryChanged = slot.taskId != taskId || slot.windowMode != mode;
            boolean changed = slot.taskId != taskId || slot.occupied != running
                    || slot.taskVisible != visible || slot.windowMode != mode;
            bindTask(slot, taskId, token);
            ComponentName actual = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            if (actual != null) slot.pkg = actual.getPackageName();
            slot.occupied = running;
            slot.taskVisible = visible;
            slot.windowMode = mode;
            if (!running && changed) h.requestCompaction();
            if (slotIndex > MiStepHost.SLOT_MAIN && mode == 6) {
                slot.modeAdmissionPending = false;
                slot.modeRepairAttempts = 0;
            }
            h.updateTaskWindowState(slotIndex, info, taskId, running);
            if (changed) {
                MiStepHost.log("taskInfo slot=" + slotIndex + " task=" + taskId
                        + " running=" + running + " visible=" + visible
                        + " mode=" + mode
                        + " appBounds=" + MiStepHost.appBoundsOf(info));
                h.updateHint(slot);
                h.updateTouchRegion(slot);
            }
            if (running) {
                // Wait for the native leash first. Early bounds writes can interrupt prepareOpen.
                h.reapplyScale(slot);
            }
            if (running && slotIndex > MiStepHost.SLOT_MAIN && mode == 6 && surfaceGeometryChanged) {
                h.scheduleSideSurfaceRestore(slot, taskId, token, slot.taskEpoch, h.sceneEpoch);
            }
            if (running && !visible) scheduleSideVisibilityRepair(slot);
            if (running && mode == 1 && slotIndex > MiStepHost.SLOT_MAIN
                    && !slot.promoting && !slot.launching && !slot.parked) {
                if (slot.modeAdmissionPending) {
                    if (SystemClock.uptimeMillis() >= slot.modeGuardUntil)
                        h.repairSideWindowMode(slot, taskId);
                    else h.scheduleSideModeResolution(slot, taskId);
                } else h.promoteExternalLaunch(slot);
            } else if (slot.pendingSidePromotion != null
                    && !slot.pendingSidePromotion.desktopLaunch && mode != 1) {
                slot.pendingSidePromotion = null;
            }
            if (changed) h.publishDesktopSideTasks();
        } catch (Throwable t) {
            MiStepHost.log("onTaskInfo slot=" + slotIndex + " failed: " + t);
        }
    }

    void onTaskVisibility(int slotIndex, int taskId, boolean visible) {
        MiStepHost.Slot slot = h.slots[slotIndex];
        if (slot.taskId != taskId || slot.promoting || slot.parked) return;
        slot.taskVisible = visible;
        MiStepHost.log("taskVisibility slot=" + slotIndex + " task=" + taskId + " visible=" + visible);
        if (!visible) scheduleSideVisibilityRepair(slot);
    }

    java.util.List<MiStepSideClose> closingTasks(Object info) {
        java.util.List<MiStepSideClose> result = new java.util.ArrayList<>();
        if (h.scene != MiStepHost.ACTIVE || h.promotion != null || h.transferBusy || h.cellMotion != null || h.root == null) return result;
        try {
            MiStepLiveSwapSession.ShellBackend backend = new MiStepLiveSwapSession.ShellBackend(
                    XposedHelpers.getObjectField(h.transitions, "mTransitions"), h.ctx.getClassLoader());
            backend.assertShellThread();
            for (Object change : (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges")) {
                if ((Integer) XposedHelpers.callMethod(change, "getMode") != 2) continue;
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (task == null) continue;
                int id = XposedHelpers.getIntField(task, "taskId");
                Object token = XposedHelpers.getObjectField(task, "token");
                for (MiStepHost.Slot slot : h.slots) {
                    if (slot.index == 0 || slot.taskId != id || !java.util.Objects.equals(slot.taskToken, token)
                            || !slot.occupied || slot.promoting || slot.parked || slot.closing || slot.frozen
                            || !controllerOwns(slot, id, token)) continue;
                    Object parent = MiStepHost.getSurfaceField(slot.controller, "mSurfaceControl");
                    Object leash = XposedHelpers.callMethod(change, "getLeash");
                    if (parent == null || leash == null || slot.rect == null) continue;
                    long epoch = slot.taskEpoch;
                    FrameLayout closeRoot = h.root;
                    final MiStepLayout closeGeometry = h.layout;
                    Rect strip = h.liveSideStrip(closeGeometry);
                    MiStepLiveBackdrop wallpaper = MiStepLiveBackdrop.create(h.liveHostRootSurface(), h.wallpaperBackdrop,
                            h.screenW, h.screenH, strip, strip,
                            canvas -> h.drawLiveEmptyHints(canvas, closeGeometry, closeGeometry,
                                    1 << slot.index), MiStepHost::log);
                    slot.closing = true;
                    result.add(new MiStepSideClose(backend, slot.controller, leash, parent, slot.cellBackground, h.scaleFor(slot),
                            slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1],
                            () -> isActive(slot) && h.scene == MiStepHost.ACTIVE && slot.taskId == id && slot.taskEpoch == epoch,
                            () -> { slot.closing = false; h.main.post(() -> {
                                if (!isActive(slot) || slot.taskId != id || slot.taskEpoch != epoch) {
                                    wallpaper.closeAfterFrame(closeRoot);
                                    return;
                                }
                                if (!h.executeOnShell(() -> {
                                    boolean stillOwned = controllerOwns(slot, id, token);
                                    if (!stillOwned) resetIdleTaskView(slot);
                                    h.main.post(() -> {
                                        if (isActive(slot) && slot.taskId == id && slot.taskEpoch == epoch) {
                                            if (stillOwned) onTaskRemovalStarted(slot.index, id);
                                            else { clearSlot(slot); h.compactCells(); }
                                        }
                                        wallpaper.closeAfterFrame(closeRoot);
                                    });
                                })) {
                                    onTaskRemovalStarted(slot.index, id);
                                    wallpaper.closeAfterFrame(closeRoot);
                                }
                            }); }, MiStepHost::log));
                    MiStepHost.log("real side close task=" + id + " slot=" + slot.index);
                }
            }
        } catch (Throwable error) {
            for (MiStepSideClose close : result) close.abandon();
            result.clear();
            MiStepHost.log("side close admission: " + error);
        }
        return result;
    }

    void onTaskRemovalStarted(int slotIndex, int taskId) {
        MiStepHost.Slot slot = h.slots[slotIndex];
        MiStepHost.log("onTaskRemovalStarted slot=" + slotIndex + " taskId=" + taskId
                + " current=" + slot.taskId);
        if (slot.closing || slot.nativeReleasePending) return;
        if (slot.promoting || slot.parked) {
            MiStepHost.log("early removal notification retained for promotion task=" + taskId);
            return;
        }
        if (slot.taskId != taskId || slot.removalCheckTask == taskId) return;
        slot.removalCheckTask = taskId;
        final long expectedEpoch = slot.taskEpoch;
        h.main.postDelayed(() -> {
            if (!isActive(slot) || slot.promoting || slot.taskId != taskId
                    || slot.taskEpoch != expectedEpoch) return;
            h.executeOnShell(() -> {
                boolean stillOwned = controllerOwns(slot, taskId, slot.taskToken);
                if (!stillOwned) resetIdleTaskView(slot);
                postForSlot(slot, owner -> {
                    if (owner.taskId != taskId || owner.taskEpoch != expectedEpoch || owner.promoting) return;
                    owner.removalCheckTask = -1;
                    if (stillOwned) {
                        MiStepHost.log("removal not detached; retain task=" + taskId);
                        return;
                    }
                    clearSlot(owner);
                    h.compactCells();
                });
            });
        }, 250L);
    }

    void scheduleSideVisibilityRepair(MiStepHost.Slot slot) {
        if (!canRender(slot) || h.scene != MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked
                || slot.promoting || slot.launching
                || slot.frozen || !slot.occupied || slot.taskVisible || slot.reassertPending) return;
        final long epoch = h.sceneEpoch, binding = slot.taskEpoch;
        final int taskId = slot.taskId;
        final Object token = slot.taskToken;
        slot.reassertPending = true;
        h.main.postDelayed(() -> {
            slot.reassertPending = false;
            if (!visibilityRepairCurrent(slot, epoch, binding, taskId, token)) return;
            for (MiStepHost.Slot side : h.slots) if (side.promoting || side.launching || side.frozen) return;
            h.executeOnShell(() -> {
                try {
                    if (!visibilityRepairCurrent(slot, epoch, binding, taskId, token)
                            || !controllerOwns(slot, taskId, token)) return;
                    Object foreground = null;
                    java.util.List<?> roots = h.rootInfos();
                    for (Object info : roots) {
                        if (!XposedHelpers.getBooleanField(info, "isVisible")
                                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                                || h.isSideToken(XposedHelpers.getObjectField(info, "token"))) continue;
                        int type = (Integer) XposedHelpers.callMethod(info, "getActivityType");
                        if (type != 1 && type != 2) continue;
                        if (foreground != null) return; // Ambiguous split/overlay scene: do not guess.
                        foreground = XposedHelpers.getObjectField(info, "token");
                    }
                    if (!slot.visibilityRepair.admit(epoch, foreground)) return;
                    Object wct = Class.forName("android.window.WindowContainerTransaction")
                            .getDeclaredConstructor().newInstance();
                    int count = 0;
                    for (MiStepHost.Slot side : h.slots) {
                        if (!canRender(side) || side.promoting || side.launching || side.frozen
                                || !side.occupied || !controllerOwns(side, side.taskId, side.taskToken)) continue;
                        Object core = null;
                        for (Object info : roots) if (new MiStepHost.LiveTask(side).matches(info)) { core = info; break; }
                        if (!h.ordinaryRoot(core, 6)) continue;
                        XposedHelpers.callMethod(wct, "setHidden", side.taskToken, false);
                        XposedHelpers.callMethod(wct, "setForceTranslucent", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "setAlwaysOnTop", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "setFocusable", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "reorder", side.taskToken, true);
                        count++;
                    }
                    if (count == 0 || !visibilityRepairCurrent(slot, epoch, binding, taskId, token)) return;
                    boolean applied = h.applyWct("retain-side-order", wct);
                    if (applied) {
                        for (MiStepHost.Slot side : h.slots) {
                            if (canRender(side) && side.occupied && !side.promoting && !side.frozen
                                    && controllerOwns(side, side.taskId, side.taskToken))
                                XposedHelpers.callMethod(h.transitions, "setTaskViewVisible", side.controller, true);
                        }
                    }
                    MiStepHost.log("side order repair kept live tasks=" + count + " foreground=" + foreground
                            + " applied=" + applied + "; no remove/relaunch");
                } catch (Throwable error) {
                    MiStepHost.log("side order repair stopped; retain original task: " + error);
                }
            });
        }, 160L);
    }

    void reassertSideTaskViews(String reason) {
        final long epoch = h.sceneEpoch;
        if (!h.executeOnShell(() -> {
            if (!h.screenOn || (h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING)
                    || h.root == null || epoch != h.sceneEpoch) return;
            int count = 0;
            java.util.List<MiStepHost.RenderBinding> frames = new java.util.ArrayList<>();
            for (MiStepHost.Slot side : h.slots) {
                if (side.index <= MiStepHost.SLOT_MAIN || !canRender(side) || !side.occupied
                        || (side.index==MiStepHost.SLOT_TOP && !h.topCtrl.topFace) || side.parked || side.promoting || side.launching || side.frozen
                        || side.controller == null
                        || !controllerOwns(side, side.taskId, side.taskToken)) continue;
                try {
                    XposedHelpers.callMethod(h.transitions, "setTaskViewVisible", side.controller, true);
                    Object leash = MiStepHost.getSurfaceField(side.controller, "mTaskLeash");
                    Object parent = MiStepHost.getSurfaceField(side.controller, "mSurfaceControl");
                    if (leash != null && parent != null && leash != parent)
                        frames.add(new MiStepHost.RenderBinding(side, epoch, leash, parent));
                    count++;
                } catch (Throwable error) {
                    MiStepHost.log("side reassert failed slot=" + side.index + ": " + error);
                }
            }
            if (!frames.isEmpty()) {
                Object tx = null;
                try {
                    tx = Class.forName("android.view.SurfaceControl$Transaction")
                            .getDeclaredConstructor().newInstance();
                    // setTaskViewVisible is a no-op for an already-visible task.
                    // A rotation finish can still have returned its canonical leash
                    // to the display. Restore the owned parent and geometry together.
                    h.appendRetainedSurfaces(tx, frames);
                    XposedHelpers.callMethod(tx, "apply");
                } catch (Throwable error) {
                    MiStepHost.log("side parent reassert failed reason=" + reason + ": " + error);
                } finally { h.closeSurfaceObject(tx, "close"); }
            }
            final int visible = count;
            h.main.post(() -> {
                for (MiStepHost.Slot side : h.slots) {
                    if (side.index > MiStepHost.SLOT_MAIN && side.occupied && !side.promoting && !side.parked)
                        h.reapplyScale(side);
                }
                MiStepHost.log("side TaskViews reasserted reason=" + reason + " count=" + visible);
            });
        })) MiStepHost.log("side TaskView reassert skipped reason=" + reason + ": no Shell queue");
    }

    boolean visibilityRepairCurrent(MiStepHost.Slot slot, long epoch, long binding, int id, Object token) {
        return canRender(slot) && h.scene == MiStepHost.ACTIVE && !slot.promoting && !slot.launching && !slot.frozen
                && h.sceneEpoch == epoch && slot.taskEpoch == binding && slot.taskId == id
                && token != null && token.equals(slot.taskToken);
    }

}
