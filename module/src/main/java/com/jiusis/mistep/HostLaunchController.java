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

/** 侧格启动 / 桌面图标发布 / Home 任务识别 / 隐藏 API 调用 */
final class HostLaunchController {
    private final MiStepHost h;

    HostLaunchController(MiStepHost host) { h = host; }

    MiStepHost.LiveTask findMainTask() throws Exception {
        // A closing fullscreen app may remain visible while Home has already taken
        // focus. Visibility alone cannot make that departing app a swap participant.
        // If both claim focus the scene is still changing: retain both task bindings.
        MiStepHost.LiveTask candidate = null;
        MiStepHost.LiveTask focusedCandidate = null;
        boolean focusedHome = false;
        int visibleCandidates = 0;
        java.util.List<?> roots = h.rootInfos();
        for (Object info : roots) {
            if (isHomeRoot(info) && XposedHelpers.getBooleanField(info, "isVisible")
                    && XposedHelpers.getBooleanField(info, "isFocused")) focusedHome = true;
            if (!h.ordinaryRoot(info, 1)) continue;
            int userId = XposedHelpers.getIntField(info, "userId");
            boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
            boolean focused = XposedHelpers.getBooleanField(info, "isFocused");
            boolean sideTask = h.isSideToken(XposedHelpers.getObjectField(info, "token"));
            if (!TaskUserPolicy.mayBeMainTask(userId, h.currentUserId(),
                    h.supportedTaskUsers(), visible, focused, sideTask)) continue;
            if (visible) visibleCandidates++;
            MiStepHost.LiveTask task = new MiStepHost.LiveTask(info);
            if (focused) {
                if (focusedCandidate != null)
                    throw new IllegalStateException("ambiguous focused main roots");
                focusedCandidate = task;
            }
            candidate = task;
            h.updateTaskWindowState(0, info, task.id, true);
        }
        if (focusedHome && focusedCandidate != null)
            throw new IllegalStateException("Home/app focus is changing; retain task bindings");
        if (focusedHome) return null;
        if (focusedCandidate != null) return focusedCandidate;
        if (visibleCandidates > 1) throw new IllegalStateException("ambiguous visible main roots");
        return candidate; // Home（type=2）不会被当主任务。
    }

    MiStepHost.LiveTask findPackageTask(String pkg) throws Exception {
        MiStepHost.LiveTask candidate = null;
        for (Object info : h.rootInfos()) {
            if (!TaskUserPolicy.isCurrentUser(
                    XposedHelpers.getIntField(info, "userId"), h.currentUserId())) continue;
            ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            if (base == null || !pkg.equals(base.getPackageName())) continue;
            if (!h.ordinaryRoot(info, 1) || h.isSideToken(XposedHelpers.getObjectField(info, "token")))
                throw new IllegalStateException("existing package task has unsupported owner/mode");
            if (candidate != null) throw new IllegalStateException("multiple package tasks; no implicit relaunch");
            candidate = new MiStepHost.LiveTask(info);
        }
        return candidate;
    }

    MiStepHost.LiveTask existingHomeTask() throws Exception {
        MiStepHost.LiveTask home = null;
        for (Object info : h.rootInfos()) {
            if (!isHomeRoot(info)) continue;
            if (home != null) throw new IllegalStateException("ambiguous existing Home roots");
            home = new MiStepHost.LiveTask(info);
        }
        if (home == null) throw new IllegalStateException("existing Home root unavailable");
        return home;
    }

    boolean isHomeRoot(Object info) {
        return info != null && Integer.valueOf(2).equals(
                    XposedHelpers.callMethod(info, "getActivityType"))
                && XposedHelpers.getIntField(info, "displayId") == 0
                && XposedHelpers.getIntField(info, "userId") == h.currentUserId()
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"));
    }

    MiStepHost.LiveTask existingHomeForCollection(MiStepHost.LiveTask task) throws Exception {
        MiStepHost.LiveTask home = existingHomeTask();
        HomeTaskReturn saved = h.coveredHome;
        if (saved != null && saved.covers(task.id, task.token)
                && !saved.restores(home.id, home.token))
            throw new IllegalStateException("covered Home identity changed; keep app in main");
        return home;
    }

    Object homeForCollection(MiStepHost.LiveTask task) throws Exception {
        return existingHomeForCollection(task).token;
    }

    void publishDesktopSideTasks() {
        final long sequence = ++h.desktopRegistrationSequence;
        final long epoch = h.sceneEpoch;
        final java.util.List<MiStepHost.Slot> owners = new java.util.ArrayList<>();
        for (MiStepHost.Slot slot : h.slots) if (slot != null && slot.index > 0 && slot.index < MiStepHost.CELL_SLOT_COUNT) owners.add(slot);
        final boolean active = h.scene == MiStepHost.ACTIVE && h.screenOn && !h.keyguardLocked && h.root != null;
        final boolean keepRecents = h.root != null && h.scene != MiStepHost.SUSPENDED;
        Runnable send = () -> {
            if (sequence != h.desktopRegistrationSequence || epoch != h.sceneEpoch) return;
            android.os.Bundle state = new android.os.Bundle();
            java.util.ArrayList<android.os.Bundle> rows = new java.util.ArrayList<>();
            java.util.ArrayList<android.os.Bundle> recents = new java.util.ArrayList<>();
            if (active || keepRecents) for (MiStepHost.Slot slot : owners) {
                try {
                    if (!h.isActive(slot) || !slot.occupied || slot.parked || slot.taskId < 0
                            || slot.taskToken == null || slot.pkg == null
                            || !h.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
                    Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                    synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                        Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                                organizer, "mTasks")).get(slot.taskId);
                        Object info = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                        if (info == null || !slot.taskToken.equals(XposedHelpers.getObjectField(info, "token"))) continue;
                        android.os.Bundle row = new android.os.Bundle();
                        row.putBinder("task", (android.os.IBinder) XposedHelpers.callMethod(slot.taskToken, "asBinder"));
                        row.putInt("taskId", slot.taskId);
                        row.putInt("userId", XposedHelpers.getIntField(info, "userId"));
                        row.putInt("displayId", XposedHelpers.getIntField(info, "displayId"));
                        row.putString("package", slot.pkg);
                        row.putLong("scene", epoch);
                        row.putLong("binding", slot.taskEpoch);
                        if (keepRecents && h.scene != MiStepHost.SUSPENDED && MiStepHost.windowModeOf(info) == 6) recents.add(row);
                        if (active && h.scene == MiStepHost.ACTIVE && h.screenOn && !h.keyguardLocked
                                && !slot.frozen && !slot.promoting && !slot.launching
                                && h.mayWriteSideSurface(slot)) rows.add(row);
                    }
                } catch (Throwable unavailable) { MiStepHost.log("desktop binding omitted: " + unavailable); }
            }
            if (sequence != h.desktopRegistrationSequence || epoch != h.sceneEpoch) return;
            state.putBinder("owner", h.desktopSceneOwner);
            MiStepHost.Slot upper = h.slots[MiStepHost.SLOT_TOP];
            TopTaskState.Key upperOwner = h.topCtrl.topTaskState.owner();
            if (active && h.scene == MiStepHost.ACTIVE && h.screenOn && !h.keyguardLocked && h.topCtrl.topFace
                    && upperOwner != null && upperOwner.taskId == upper.taskId
                    && upperOwner.token.equals(upper.taskToken)
                    && h.isActive(upper) && upper.occupied && !upper.parked
                    && !upper.frozen && !upper.promoting && !upper.launching
                    && upper.taskToken != null && h.controllerOwns(upper, upper.taskId, upper.taskToken)) {
                TopTaskGeometry geometry = h.topGeometry();
                android.os.Bundle topInput = new android.os.Bundle();
                topInput.putBinder("task", (android.os.IBinder) XposedHelpers.callMethod(upper.taskToken, "asBinder"));
                topInput.putInt("taskId", upper.taskId);
                topInput.putInt("userId", upperOwner.userId);
                topInput.putInt("displayId", 0);
                topInput.putInt("width", geometry.logicalWidth);
                topInput.putInt("height", geometry.logicalHeight);
                topInput.putFloat("scale", geometry.scale);
                state.putBundle("topInput", topInput);
            }
            state.putLong("sequence", sequence);
            state.putParcelableArrayList("rows", rows);
            state.putParcelableArrayList("recents", recents);
            h.ctx.sendBroadcast(new Intent(MiStepDesktopLaunchBridge.ACTION).setPackage("android")
                    .putExtra("state", state), android.Manifest.permission.WRITE_SECURE_SETTINGS);
        };
        if (active || keepRecents) h.executeOnShell(send); else send.run();
    }

    void promoteSideTaskForDesktopLaunch(String pkg, int launchUser) {
        if (pkg == null || h.scene != MiStepHost.ACTIVE) return;
        if (!h.supportsTaskUser(launchUser)) return;
        String[] packages = new String[MiStepHost.SLOT_COUNT - 1];
        int[] users = new int[MiStepHost.SLOT_COUNT - 1];
        boolean[] occupied = new boolean[MiStepHost.SLOT_COUNT - 1];
        for (int i = 1; i < MiStepHost.SLOT_COUNT; i++) {
            packages[i - 1] = h.slots[i].pkg;
            occupied[i - 1] = h.sideTaskMatchesUser(h.slots[i], launchUser);
            users[i - 1] = launchUser;
        }
        int sideIndex = DesktopIconLaunchPolicy.uniqueSideSlot(pkg, launchUser, packages, users, occupied);
        if (sideIndex < 0) {
            MiStepHost.log("desktop launch has no unique side task package=" + pkg);
            return;
        }
        MiStepHost.Slot match = h.slots[sideIndex + 1];
        if(match.index==MiStepHost.SLOT_TOP) {h.topCtrl.releaseTopTask(true,true,null);return;}
        MiStepHost.log("desktop icon selected side task package=" + pkg + " slot=" + match.index
                + " task=" + match.taskId + "; promote existing task");
        h.queueSidePromotion(match, true);
    }

    void scheduleSideSurfaceRestore(MiStepHost.Slot slot, int taskId, Object token,
            long bindingEpoch, long epoch) {
        for (long delay : new long[]{90L, 320L, 800L}) {
            h.main.postDelayed(() -> {
                if (h.scene != MiStepHost.ACTIVE || h.sceneEpoch != epoch || !h.isActive(slot)
                        || slot.taskId != taskId || slot.taskEpoch != bindingEpoch
                        || !java.util.Objects.equals(slot.taskToken, token)
                        || !slot.occupied || slot.promoting || slot.parked
                        || slot.windowMode != 6) return;
                h.reapplyScale(slot);
            }, delay);
        }
    }

    void launchQuick(QuickApp entry) {
        if (entry == null || entry.component == null || h.scene != MiStepHost.ACTIVE
                || h.promotion != null || h.transferBusy) return;
        for (MiStepHost.Slot slot : h.slots) {
            if (slot.index > MiStepHost.SLOT_MAIN && h.sideTaskMatchesUser(slot, h.currentUserId()) && entry.component.getPackageName()
                    .equals(slot.pkg)) {
                MiStepHost.log("top shortcut promotes existing side task slot=" + slot.index);
                h.onSlotTapped(slot.index);
                return;
            }
        }
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            h.ctx.startActivity(launch);
            MiStepHost.log("top shortcut launch main " + entry.component.flattenToShortString());
            h.scheduleMainCropClear(1400L);
        } catch (Throwable error) {
            MiStepHost.log("top shortcut launch failed: " + error);
            android.widget.Toast.makeText(h.ctx, "无法打开 " + entry.label,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    void openSettingsInMain() {
        if (h.scene != MiStepHost.ACTIVE || h.promotion != null || h.transferBusy) return;
        for (MiStepHost.Slot slot : h.slots) {
            if (slot.index > MiStepHost.SLOT_MAIN && h.sideTaskMatchesUser(slot, h.currentUserId())
                    && "com.android.settings".equals(slot.pkg)) {
                h.onSlotTapped(slot.index);
                return;
            }
        }
        try {
            Intent settings = new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            h.ctx.startActivity(settings);
            MiStepHost.log("system settings launched in main");
        } catch (Throwable error) {
            MiStepHost.log("system settings launch failed: " + error);
            android.widget.Toast.makeText(h.ctx, "无法打开系统设置",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    boolean launchActivitySupportsSide(Intent launch) {
        return true;
    }

    void keepUnsupportedInMain(MiStepHost.Slot slot, String pkg, String reason) {
        if (slot != null) {
            slot.launching = false;
            h.updateHint(slot);
            h.updateTouchRegion(slot);
        }
        MiStepHost.log("side launch rejected slot=" + (slot == null ? -1 : slot.index)
                + " pkg=" + pkg + ": " + reason + "; task remains in main");
        android.widget.Toast.makeText(h.ctx, "该应用不支持副格，已在主区打开",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    void launchPackage(int slotIndex, String pkg) {
        if (slotIndex <= MiStepHost.SLOT_MAIN || slotIndex >= MiStepHost.SLOT_COUNT) {
            MiStepHost.log("launchPackage bad slot=" + slotIndex);
            return;
        }
        Intent i = h.ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null && pkg.contains("home")) {
            // 桌面（home）没有普通 LAUNCHER intent，用 CATEGORY_HOME 显式构造。
            i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setPackage(pkg);
            MiStepHost.log("launchPackage: using CATEGORY_HOME for " + pkg);
        }
        if (i == null) {
            MiStepHost.log("launchPackage: no launcher for " + pkg);
            return;
        }
        launchIntoSlot(slotIndex, i, pkg);
    }

    void launchComponent(int slotIndex, ComponentName cn) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(cn);
        launchIntoSlot(slotIndex, i, cn.flattenToShortString());
    }

    void launchIntoSlot(final int slotIndex, Intent launch, String label) {
        final MiStepHost.Slot slot = h.slots[slotIndex];
        if (!h.isActive(slot) || slot.view == null || slot.launching || slot.promoting || h.promotion != null) {
            MiStepHost.log("launchIntoSlot " + slotIndex + ": no view");
            return;
        }
        final String pkg = launch.getComponent() != null
                ? launch.getComponent().getPackageName() : launch.getPackage();
        if (pkg == null || "com.miui.home".equals(pkg)) return;
        for (MiStepHost.Slot other : h.slots) {
            if (other != slot && (other.occupied || other.launching) && pkg.equals(other.pkg)) {
                MiStepHost.log("launch rejected: package already in side slot=" + other.index);
                return;
            }
        }
        if (!MiStepHost.boundsHooksReady || h.scene != MiStepHost.ACTIVE || h.transferBusy) return;
        Runnable choose = () -> chooseExistingOrLaunch(slot, launch, label, pkg);
        if (slot.occupied) {
            h.returnToSystem(slot, false, () -> { h.clearSlot(slot); choose.run(); },
                    error -> MiStepHost.log("replacement stopped; previous live task retained: " + error));
        } else choose.run();
    }

    void chooseExistingOrLaunch(MiStepHost.Slot slot, Intent launch, String label, String pkg) {
        if (!h.isActive(slot) || h.scene != MiStepHost.ACTIVE) return;
        slot.launching = true;
        if (!h.executeOnShell(() -> {
            try {
                MiStepHost.LiveTask existing = findPackageTask(pkg);
                h.main.post(() -> {
                    if (!h.isActive(slot) || h.scene != MiStepHost.ACTIVE) { slot.launching = false; return; }
                    if (existing == null) {
                        h.startSlot(slot, launch, label);
                    } else {
                        slot.launching = false;
                        h.adoptExisting(slot, existing, h::finishPendingSuspend,
                                error -> {
                                    MiStepHost.log("existing task admission failed; no launcher fallback: " + error);
                                    h.finishPendingSuspend();
                                });
                    }
                });
            } catch (Throwable error) {
                h.main.post(() -> { slot.launching = false; MiStepHost.log("choose task stopped: " + error);
                    h.finishPendingSuspend(); });
            }
        })) { slot.launching = false; MiStepHost.log("no Shell queue for selection"); }
    }

    static void callHidden(Object target, String name, Object... args) { HostStaticUtil.callHidden(target, name, args); }

    static Method findStartActivity(Class<?> cls) { return HostStaticUtil.findStartActivity(cls); }

}
