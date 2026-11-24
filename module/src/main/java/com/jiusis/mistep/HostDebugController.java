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

/** 调试 / POC / 链路追踪工具簇（批次 E 重试，掩码方案） */
final class HostDebugController {
    private final MiStepHost h;

    HostDebugController(MiStepHost host) { h = host; }

    public static void debugLaunch(int slot, String pkg) {
        MiStepHost hb = MiStepHost.sInstance;
        if (hb == null) {
            MiStepHost.log("debugLaunch: host not started");
            return;
        }
        hb.main.post(() -> hb.launchWhenReady(slot, pkg, 0));
    }

    public static void debugSwap(int slot) {
        MiStepHost hb = MiStepHost.sInstance;
        if (hb == null) {
            MiStepHost.log("debugSwap: host not started");
            return;
        }
        hb.main.post(() -> hb.swapWithMain(slot));
    }

    public static void debugTraceNextSwap() {
        MiStepHost hb = MiStepHost.sInstance;
        if (hb == null) { MiStepHost.log("passive trace: host not started"); return; }
        hb.main.post(() -> {
            if (hb.scene != MiStepHost.ACTIVE || !hb.screenOn || hb.keyguardLocked
                    || hb.promotion != null || hb.transferBusy) return;
            hb.traceNextSwap = true;
            MiStepHost.log("passive trace armed for next one swap; 6s/384 events; no transaction mutations");
        });
    }

    void traceSwap(Promotion ticket) {
        if (!h.traceNextSwap) return;
        h.traceNextSwap = false;
        MiStepSwapTrace old = h.swapTrace;
        if (old != null) old.close();
        h.swapTrace = null;
        try {
            LiveTask previous = ticket.previousMain;
            h.swapTrace = new MiStepSwapTrace(h.main, new java.io.File(h.ctx.getFilesDir(), "mistep-swap-traces"),
                    MiStepHost::log, previous == null ? -1 : previous.id,
                    previous == null ? null : previous.token, ticket.taskId, ticket.token,
                    ticket.slot.controller, h.shellOrganizer(), h.layout.describe(),
                    closed -> { if (h.swapTrace == closed) h.swapTrace = null; });
        } catch (Throwable error) { MiStepHost.log("passive trace unavailable: " + error); }
    }

    void traceSwapEvent(String event) {
        MiStepSwapTrace trace = h.swapTrace;
        if (trace != null) trace.snapshot(event);
    }

    public static void debugMirror(int taskId, int x, int y, int w, int hb, int holdMs,
            int hideMode) {
        MiStepHost host = MiStepHost.sInstance;
        if (host == null) {
            MiStepHost.log("debugMirror: host not started");
            return;
        }
        if (w <= 0 || hb <= 0) {
            MiStepHost.log("debugMirror: bad rect " + w + "x" + hb);
            return;
        }
        host.main.post(() -> host.startMirrorPoc(taskId,
                new Rect(x, y, x + w, y + hb), Math.max(1000, holdMs), hideMode));
    }

    void startMirrorPoc(int requestedTask, Rect dest, int holdMs, int hideMode) {
        h.mirrorController.startMirrorPoc(requestedTask, dest, holdMs, hideMode);
    }

    void runLeashAnimPoc(int slotIndex, long durationMs, String pkg) {
        if (h.scene != MiStepHost.ACTIVE || h.layout == null || slotIndex <= MiStepHost.SLOT_MAIN
                || slotIndex >= MiStepHost.SLOT_COUNT || h.slots[slotIndex].rect == null) {
            MiStepHost.log("leash anim POC skipped slot=" + slotIndex + " scene=" + h.scene);
            return;
        }
        final int index = slotIndex;
        if (!h.executeOnShell(() -> {
            try {
                LiveTask task = null;
                try {
                    task = h.findMainTask();
                } catch (Throwable error) {
                    MiStepHost.log("leash anim: findMainTask failed: " + error);
                }
                if (task == null && pkg != null) {
                    try {
                        task = h.findPackageTask(pkg);
                        MiStepHost.log("leash anim: package fallback task="
                                + (task == null ? -1 : task.id) + " pkg=" + pkg);
                    } catch (Throwable error) {
                        MiStepHost.log("leash anim: findPackageTask failed: " + error);
                    }
                }
                if (task == null) {
                    MiStepHost.log("leash anim POC: no main task pkg=" + pkg);
                    return;
                }
                final LiveTask resolved = task;
                Object organizer = h.shellOrganizer();
                Object leash = organizer == null ? null
                        : ShellCompat.taskLeash(organizer, resolved.id);
                if (leash == null
                        || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    MiStepHost.log("leash anim POC: no leash task=" + resolved.id);
                    return;
                }
                h.main.post(() -> animateLeashPoc(leash, h.slots[index].rect, index, resolved.id, durationMs));
            } catch (Throwable error) {
                MiStepHost.log("leash anim POC setup failed: " + error);
            }
        })) {
            MiStepHost.log("leash anim POC skipped: no shell queue");
        }
    }

    void animateLeashPoc(Object leash, int[] rect, int slotIndex, int taskId,
            long durationMs) {
        final Rect dest = new Rect(rect[0], rect[1], rect[2], rect[3]);
        final float ratio = h.layout.mainScale;
        final float endScaleX = dest.width() / (ratio * h.screenW);
        final float endScaleY = dest.height() / (ratio * h.screenH);
        final float endX = (dest.left - h.layout.mainOffsetX) / ratio;
        final float endY = (dest.top - h.layout.mainOffsetY) / ratio;
        h.leashAnimFrames = 0;
        hideSlotChromeForPoc(slotIndex, true);
        try {
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(durationMs);
            anim.setInterpolator(new PathInterpolator(0.34f, 0.69f, 0.1f, 1f));
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                applyLeashTransform(leash, endX * f, endY * f,
                        1f + (endScaleX - 1f) * f, 1f + (endScaleY - 1f) * f);
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    applyLeashTransform(leash, endX, endY, endScaleX, endScaleY);
                    MiStepHost.log("leash anim POC finished task=" + taskId + " dest=" + dest
                            + " frames=" + h.leashAnimFrames);
                    h.main.postDelayed(() -> reverseLeashPoc(leash, taskId, slotIndex), 1200L);
                }
            });
            anim.start();
            MiStepHost.log("leash anim POC start task=" + taskId + " dest=" + dest
                    + " duration=" + durationMs + " endScale=" + endScaleX + "x" + endScaleY);
        } catch (Throwable error) {
            MiStepHost.log("leash anim POC failed: " + error);
        }
    }

    void hideSlotChromeForPoc(int slotIndex, boolean hide) {
        try {
            h.leashPocSlot = hide ? slotIndex : -1;
            View backing = h.sideBackingViews[slotIndex];
            if (backing != null) {
                backing.setVisibility(hide ? View.GONE : View.VISIBLE);
            }
            Slot slot = h.slots[slotIndex];
            if (slot != null) {
                if (hide) {
                    if (slot.hint != null) slot.hint.setVisibility(View.GONE);
                    h.removeCellBackground(slot);
                } else if (slot.hint != null) {
                    slot.hint.setVisibility(View.VISIBLE);
                }
            }
            if (h.root != null) {
                h.root.requestLayout();
                h.root.invalidate();
            }
            MiStepHost.log("leash anim POC slot chrome hidden=" + hide + " slot=" + slotIndex);
        } catch (Throwable error) {
            MiStepHost.log("leash anim chrome failed: " + error);
        }
    }

    void reverseLeashPoc(Object leash, int taskId, int slotIndex) {
        // POC 只验证“主窗 live 缩小”；hold 后直接回位，避免再引入一套反向动画。
        applyLeashTransform(leash, 0f, 0f, 1f, 1f);
        hideSlotChromeForPoc(slotIndex, false);
        MiStepHost.log("leash anim POC restored task=" + taskId);
    }

    void startLiveMainShrink(Promotion ticket) {
        Object leash = ticket.mainLeash;
        if (leash == null || ticket.slot == null || ticket.slot.rect == null) return;
        int[] rect = ticket.slot.rect;
        final Rect dest = new Rect(rect[0], rect[1], rect[2], rect[3]);
        final float ratio = h.layout.mainScale;
        final float endScaleX = dest.width() / (ratio * h.screenW);
        final float endScaleY = dest.height() / (ratio * h.screenH);
        final float endX = (dest.left - h.layout.mainOffsetX) / ratio;
        final float endY = (dest.top - h.layout.mainOffsetY) / ratio;
        try {
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(SwapPreview.DURATION_MS);
            anim.setInterpolator(new PathInterpolator(0.34f, 0.69f, 0.1f, 1f));
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                applyLeashTransform(leash, endX * f, endY * f,
                        1f + (endScaleX - 1f) * f, 1f + (endScaleY - 1f) * f);
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    applyLeashTransform(leash, endX, endY, endScaleX, endScaleY);
                    MiStepHost.log("live main shrink finished task=" + ticket.taskId
                            + " dest=" + dest + " frames=" + h.leashAnimFrames);
                }
            });
            h.leashAnimFrames = 0;
            anim.start();
            MiStepHost.log("live main shrink start task=" + ticket.taskId + " dest=" + dest
                    + " duration=" + SwapPreview.DURATION_MS);
        } catch (Throwable error) {
            MiStepHost.log("live main shrink failed: " + error);
        }
    }

    void applyLeashTransform(Object leash, float x, float y, float sx, float sy) {
        try {
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition", leash, x, y);
                XposedHelpers.callMethod(tx, "setMatrix", leash, sx, 0f, 0f, sy);
                XposedHelpers.callMethod(tx, "apply");
                h.leashAnimFrames++;
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
        } catch (Throwable error) {
            MiStepHost.log("leash anim frame failed: " + error);
        }
    }

    void dumpRoots(String reason) {
        try {
            java.util.List<?> roots = h.rootInfos();
            LiveTask main = null;
            try { main = h.findMainTask(); } catch (Throwable error) {
                MiStepHost.log("roots[" + reason + "] findMainTask threw: " + error);
            }
            MiStepHost.log("roots[" + reason + "] count=" + roots.size()
                    + " main=" + (main == null ? -1 : main.id));
            for (Object info : roots) {
                int taskId = XposedHelpers.getIntField(info, "taskId");
                int userId = XposedHelpers.getIntField(info, "userId");
                int type = (Integer) XposedHelpers.callMethod(info, "getActivityType");
                boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
                boolean focused = XposedHelpers.getBooleanField(info, "isFocused");
                boolean hasParent = Boolean.TRUE.equals(
                        XposedHelpers.callMethod(info, "hasParentTask"));
                int mode = -1;
                try {
                    Object config = XposedHelpers.getObjectField(info, "configuration");
                    Object wc = XposedHelpers.getObjectField(config, "windowConfiguration");
                    mode = (Integer) XposedHelpers.callMethod(wc, "getWindowingMode");
                } catch (Throwable ignored) {}
                Object token = XposedHelpers.getObjectField(info, "token");
                boolean side = h.isSideToken(token);
                boolean ordinary = h.ordinaryRoot(info, 1);
                ComponentName base = (ComponentName) XposedHelpers.getObjectField(
                        info, "baseActivity");
                MiStepHost.log("roots[" + reason + "] task=" + taskId + " type=" + type
                        + " user=" + userId
                        + " vis=" + visible + " focused=" + focused + " mode=" + mode
                        + " parent=" + hasParent + " side=" + side
                        + " ordinary=" + ordinary
                        + " base=" + (base == null ? "?" : base.getPackageName()));
            }
        } catch (Throwable error) {
            MiStepHost.log("roots dump[" + reason + "] failed: " + error);
        }
    }

    public static void debugRoots() {
        MiStepHost host = MiStepHost.sInstance;
        if (host == null) {
            MiStepHost.log("debugRoots: host not started");
            return;
        }
        if (!host.executeOnShell(() -> host.dumpRoots("debug"))) {
            MiStepHost.log("debugRoots skipped: no shell queue");
        }
    }

    public static void debugDumpViews() {
        MiStepHost host = MiStepHost.sInstance;
        if (host == null) { MiStepHost.log("debugDumpViews: host not started"); return; }
        host.main.post(() -> host.dumpViewTree());
    }

    void dumpViewTree() {
        if (h.root == null) { MiStepHost.log("view tree: root null"); return; }
        MiStepHost.log("view tree root children=" + h.root.getChildCount());
        dumpViewRecursive(h.root, 0);
    }

    void dumpViewRecursive(View view, int depth) {
        if (view == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("| ");
        sb.append(view.getClass().getSimpleName())
                .append(" vis=").append(view.getVisibility())
                .append(" l=").append(view.getLeft()).append(" t=").append(view.getTop())
                .append(" w=").append(view.getWidth()).append(" h=").append(view.getHeight())
                .append(" a=").append(view.getAlpha());
        if (view.getBackground() != null) {
            sb.append(" bg=").append(view.getBackground().getClass().getSimpleName());
        }
        MiStepHost.log("view: " + sb);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpViewRecursive(group.getChildAt(i), depth + 1);
            }
        }
    }

}
