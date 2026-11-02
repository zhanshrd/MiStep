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

/** 拖拽 / 格缩放 / WCT 事务 / 触摸区域 / 窗口状态与 IME */
final class HostWindowController {
    private final MiStepHost h;

    HostWindowController(MiStepHost host) { h = host; }

TopTaskGeometry topGeometry() {
        return new TopTaskGeometry(h.screenW,h.screenH,h.layout.topStrip,DeviceProfile.statusBarHeightPx(h.ctx),
                h.ctx.getResources().getDisplayMetrics().density);
    }

    void applyTopConfiguration(Object wct, Object token, Slot slot) {
        if (slot == null || slot.index != MiStepHost.SLOT_TOP) return;
        TopTaskGeometry geometry = topGeometry();
        XposedHelpers.callMethod(wct, "setAppBounds", token, h.logicalBounds(slot));
        // Inherit native density so retained app Views never mix cached px at two DPIs.
        XposedHelpers.callMethod(wct, "setDensityDpi", token, 0);
        int widthDp = Math.max(1, Math.round(geometry.logicalWidth * 160f / geometry.densityDpi));
        int heightDp = Math.max(1, Math.round(geometry.logicalHeight * 160f / geometry.densityDpi));
        XposedHelpers.callMethod(wct, "setScreenSizeDp", token, widthDp, heightDp);
        XposedHelpers.callMethod(wct, "setSmallestScreenWidthDp", token, Math.min(widthDp, heightDp));
    }

    int[] slotBounds(Slot slot) {
        return slot.index==MiStepHost.SLOT_TOP ? topGeometry().bounds() : h.layout.cellBounds(slot.index-1);
    }

    boolean supportsTaskUser(int userId) {
        java.util.Set<Integer> ids = h.supportedTaskUsers();
        return TaskUserPolicy.allows(userId, currentUserId(), ids);
    }

    int currentUserId() {
        try {
            return (Integer) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityManager"), "getCurrentUser");
        } catch (Throwable ignored) {
            return (Integer) XposedHelpers.callStaticMethod(UserHandle.class, "getUserId",
                    android.os.Process.myUid());
        }
    }

    void beginWindowDrag(MotionEvent event) {
        h.dragMoved = false;
        h.dragStartRawX = event.getRawX();
        h.dragStartRawY = event.getRawY();
        h.dragTarget = null;
    }

    void updateWindowDrag(MotionEvent event) {
        float dx = event.getRawX() - h.dragStartRawX;
        float dy = event.getRawY() - h.dragStartRawY;
        if (!h.dragMoved && dx * dx + dy * dy < h.dp(10) * h.dp(10)) return;
        h.dragMoved = true;
        if (h.dragGhost == null) createDragGhost();
        if (h.dragGhost != null) {
            h.dragGhost.setX(event.getRawX() - h.dragGhost.getWidth() * 0.5f);
            h.dragGhost.setY(event.getRawY() - h.dragGhost.getHeight() * 0.5f);
        }
        Slot target = slotAtPoint(event.getRawX(), event.getRawY());
        if (target != h.dragTarget) {
            setDropHighlight(h.dragTarget, false);
            h.dragTarget = target;
            setDropHighlight(h.dragTarget, true);
        }
    }

    void finishWindowDrag(MotionEvent event) {
        boolean cancelled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
        boolean moved = h.dragMoved;
        Slot target = slotAtPoint(event.getRawX(), event.getRawY());
        setDropHighlight(h.dragTarget, false);
        if (h.dragGhost != null) {
            h.root.removeView(h.dragGhost);
            h.dragGhost = null;
        }
        h.dragTarget = null;
        if (!moved || cancelled) return;
        if (target == null) {
            MiStepHost.log("window drag ended outside side cells");
            return;
        }
        if (target.occupied) {
            MiStepHost.log("window drag target occupied slot=" + target.index);
            android.widget.Toast.makeText(h.ctx, "请拖到空白副槽",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        MiStepHost.log("window drag collect main into slot=" + target.index);
        h.captureMainIntoEmptySlot(target);
    }

    void createDragGhost() {
        TextView ghost = new TextView(h.ctx);
        ghost.setText("当前应用");
        ghost.setTextColor(HyperOsStyle.textPrimary(h.ctx));
        ghost.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        ghost.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        ghost.setGravity(Gravity.CENTER);
        ghost.setAlpha(0.92f);
        ghost.setElevation(1600f);
        ghost.setBackground(HyperOsStyle.glassPill(h.ctx, 18f, true));
        int width = h.dp(120), height = h.dp(56);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = Math.round(h.dragStartRawX - width * 0.5f);
        params.topMargin = Math.round(h.dragStartRawY - height * 0.5f);
        h.root.addView(ghost, params);
        h.dragGhost = ghost;
        h.root.requestLayout();
    }

    void setDropHighlight(Slot slot, boolean active) {
        if (slot == null || slot.hint == null) return;
        float scale = active ? 1.06f : 1f;
        slot.hint.setScaleX(scale);
        slot.hint.setScaleY(scale);
        LiveSwapVisualLease.writeAlpha(slot.hint, 1f);
    }

    int sideSegmentTop(int slotIndex) {
        if (slotIndex <= 1 || h.layout == null) return h.layout == null ? 0 : h.layout.topStrip;
        int[] previous = h.layout.cellBounds(slotIndex - 2);
        return previous[3];
    }

    int sideSegmentHeight(int slotIndex) {
        if (h.layout == null) return 1;
        int top = sideSegmentTop(slotIndex);
        int bottom = slotIndex >= MiStepHost.CELL_SLOT_COUNT - 1 ? h.screenH : h.layout.cellBounds(slotIndex - 1)[3];
        return Math.max(1, bottom - top);
    }

    Slot slotAtPoint(float x, float y) {
        if (h.layout == null || y < h.layout.topStrip) return null;
        for (Slot slot : h.slots) {
            if (slot.index <= MiStepHost.SLOT_MAIN || slot.index >= MiStepHost.CELL_SLOT_COUNT || slot.rect == null) continue;
            if (x >= slot.rect[0] && x < slot.rect[2]
                    && y >= slot.rect[1] && y < slot.rect[3]) return slot;
        }
        return null;
    }

    int slotIndexOfView(View view) {
        for (Slot s : h.slots) {
            if (s.view == view) {
                return s.index;
            }
        }
        return -1;
    }

    void installHostTouchRegion() {
        Class<?> listenerClass = XposedHelpers.findClass(
                "android.view.ViewTreeObserver$OnComputeInternalInsetsListener", h.ctx.getClassLoader());
        Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return "MiStepHostTouchRegion";
                    }
                    if (!"onComputeInternalInsets".equals(method.getName()) || h.root == null) {
                        return null;
                    }
                    Object info = args[0];
                    XposedHelpers.callMethod(info, "setTouchableInsets", 3); // TOUCHABLE_INSETS_REGION
                    Region touchable = (Region) XposedHelpers.getObjectField(info, "touchableRegion");
                    touchable.setEmpty();
                    if (h.swapPreview != null || h.cellMovePreview != null) {
                        touchable.set(0, 0, h.screenW, h.screenH);
                    } else if (h.liveSwapInputBlocked) {
                        touchable.set(0, 0, h.screenW, h.screenH);
                    } else {
                        touchable.set(0, 0, h.screenW, h.layout.topStrip);
                        touchable.op(new Rect(h.layout.cellX, h.layout.topStrip,
                                        h.layout.cellX + h.layout.cellWidth, h.screenH),
                                Region.Op.UNION);
                        Slot upper=h.slots[MiStepHost.SLOT_TOP];
                        if(h.topCtrl.topFace && h.topCtrl.topTaskPane!=null && !h.topCtrl.topTaskPane.choosing()
                                && h.topCtrl.topRoll!=null && !h.topCtrl.topRoll.rolling() && upper.occupied && !upper.parked
                                && !upper.promoting && upper.rect!=null)
                            touchable.op(new Rect(upper.rect[0],upper.rect[1],upper.rect[2],upper.rect[3]),Region.Op.DIFFERENCE);
                    }
                    return null;
                });
        XposedHelpers.callMethod(h.root.getViewTreeObserver(),
                "addOnComputeInternalInsetsListener", listener);
        MiStepHost.log("host input: top/side only; system main passes through");
    }

    void updateTouchRegion(Slot slot) {
        try {
            if (slot.view == null) {
                return;
            }
            Method m = slot.view.getClass().getMethod("setObscuredTouchRegion", Region.class);
            // Only side cells exist. Their own scaled contents still use targeted injection.
            Object arg = h.liveSwapInputBlocked ? new Region(0, 0, h.screenW, h.screenH) : slot.index==MiStepHost.SLOT_TOP ? null : slot.rect == null ? null
                    : new Region(slot.rect[0], slot.rect[1], slot.rect[2], slot.rect[3]);
            m.invoke(slot.view, arg);
        } catch (Throwable t) {
            MiStepHost.log("updateTouchRegion slot=" + slot.index + " failed: " + t);
        }
    }

    int uidOf(String pkg) { return HostStaticUtil.uidOf(h.ctx, pkg); }

    static int windowModeOf(Object info) { return HostStaticUtil.windowModeOf(info); }

    static int configurationOrientationOf(Object info) { return HostStaticUtil.configurationOrientationOf(info); }

    static int requestedOrientationOf(Object info) { return HostStaticUtil.requestedOrientationOf(info); }

    void updateTaskWindowState(int slotIndex, Object info, int taskId, boolean active) {
        if (info == null || taskId < 0) return;
        try {
            int mode = windowModeOf(info);
            int configurationOrientation = configurationOrientationOf(info);
            int requestedOrientation = requestedOrientationOf(info);
            TaskWindowState state;
            synchronized (h.taskWindowStatesLock) {
                state = h.taskWindowStates.get(taskId);
                if (state == null) {
                    state = new TaskWindowState(taskId);
                    h.taskWindowStates.put(taskId, state);
                }
            }
            state.update(slotIndex, configurationOrientation, requestedOrientation,
                    TaskWindowState.UNKNOWN_ROTATION, mode, mode == 1, active);
            if (slotIndex > MiStepHost.SLOT_MAIN && slotIndex < MiStepHost.SLOT_COUNT) {
                h.slots[slotIndex].windowState = state;
            }
        } catch (Throwable error) {
            MiStepHost.log("task window state update failed task=" + taskId + ": " + error);
        }
    }

    void syncSlotWindowState(Slot slot) {
        if (slot != null && slot.windowState != null) {
            slot.windowState.slotIndex = slot.index;
        }
    }

    void hideIme() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "statusbar");
            Class<?> stub = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
            Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Method m = null;
            for (Class<?> c = svc.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("hideCurrentInputMethodForBubbles", int.class);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
            if (m == null) {
                MiStepHost.log("hideIme: method not found");
                return;
            }
            m.setAccessible(true);
            m.invoke(svc, 0);
            MiStepHost.log("hideIme invoked");
        } catch (Throwable t) {
            MiStepHost.log("hideIme failed: " + t);
        }
    }

    String describeTasks() {
        StringBuilder sb = new StringBuilder();
        for (Slot s : h.slots) {
            sb.append(" [").append(s.index).append(':')
                    .append(s.taskId).append(s.occupied ? "" : "(empty)")
                    .append(s.taskVisible ? "/vis" : "/hid");
            if (s.windowState != null) {
                sb.append("/o").append(s.windowState.configurationOrientation)
                        .append("/r").append(s.windowState.resolvedRotation)
                        .append("/m").append(s.windowState.windowingMode)
                        .append(s.windowState.fullscreen ? "/full" : "/windowed");
            }
            sb.append(']');
        }
        return sb.toString();
    }

    boolean executeOnShell(Runnable task) {
        try {
            Object shell = XposedHelpers.getObjectField(h.transitions, "mShellExecutor");
            if (shell instanceof Executor) {
                ((Executor) shell).execute(task);
                return true;
            }
        } catch (Throwable t) {
            MiStepHost.log("Shell executor failed: " + t);
        }
        return false;
    }

    float mainScale() {
        return h.layout.mainScale;
    }

    float cellScale() {
        return h.layout.cellScale(0);
    }

    float scaleFor(Slot slot) {
        return slot.index == MiStepHost.SLOT_TOP ? topGeometry().scale : h.layout.cellScale(slot.index - 1);
    }

    void applySlotScale(Slot slot) {
        if (h.holdsLiveFlipZoom() || !h.canRender(slot) || slot.promoting || !slot.occupied || slot.view == null
                || !h.mayWriteSideSurface(slot)) {
            return;
        }
        float k = scaleFor(slot);
        Object liveLeash = MiStepHost.getSurfaceField(slot.controller, "mTaskLeash");
        if (!h.controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        if (liveLeash != null && slot.scaleSurface == liveLeash
                && slot.scaleTaskId == slot.taskId && Math.abs(slot.scaleApplied - k) < 0.0005f) {
            return;
        }
        // 顺序很重要（真机实测）：先读 leash 做缩放，再改整屏边界 —— 反过来会让
        // TaskViewTransitions 把 mTaskLeash 清空重建，900ms 内都读不到。
        if (liveLeash == null) {
            if (slot.scaleAttempts >= 8) return;
            slot.scaleAttempts++;
            MiStepHost.log("applySlotScale slot=" + slot.index + " leash not ready, retry #" + slot.scaleAttempts);
            h.main.postDelayed(() -> applySlotScale(slot), 200L);
            return;
        }
        slot.scaleAttempts = 0;
        Object sc = liveLeash;
        slot.scaleOnLeash = true;
        if (applyScaleTransform(slot, sc)) {
            slot.scaleSurface = sc;
            slot.scaleTaskId = slot.taskId;
            slot.scaleApplied = k;
            OwnedTask accepted = h.ownedTasks.get(slot.taskToken);
            if (accepted != null) accepted.surface = sc;
            MiStepHost.log("applySlotScale slot=" + slot.index + " task=" + slot.taskId
                    + " k=" + k + " onLeash=" + slot.scaleOnLeash + " surface=" + sc);
        }
        // TaskView 会把任务边界同步成视图大小（真机实测 DeepSeek=[0,622][807,2460]、设置=550
        // 宽右对齐）→ 改回整屏 1080×2460，应用才按"整屏手机"排版。放在缩放之后执行。
        // The opening/bounds guards already enforce logical full-screen sizing.
        // 转场/可见性变化可能覆盖矩阵：在几个延迟点重发。
        h.main.postDelayed(() -> reapplyScale(slot), 300L);
        h.main.postDelayed(() -> reapplyScale(slot), 1200L);
    }

    void reapplyScale(Slot slot) {
        if (h.holdsLiveFlipZoom() || !h.canRender(slot) || slot.promoting || !slot.occupied || !h.mayWriteSideSurface(slot)) {
            return;
        }
        if (!h.controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        Object current = MiStepHost.getSurfaceField(slot.controller, "mTaskLeash");
        if (current == null || current != slot.scaleSurface) {
            applySlotScale(slot);
            return;
        }
        if (!applyScaleTransform(slot, slot.scaleSurface)) {
            // 表面可能已失效（任务重启/改边界后转场重建）：清缓存 + 放开去重门槛，
            // 下次 TaskInfo 变化时重新解析并整套重来。
            slot.scaleSurface = null;
            slot.scaleTaskId = Integer.MIN_VALUE;
            slot.scaleApplied = -1f;
        }
    }

    void applyFullscreenBounds(Slot slot) {
        if (h.holdsLiveFlipZoom() || !h.canRender(slot) || slot.promoting || slot.taskToken == null
                || !h.mayWriteSideSurface(slot)) {
            return;
        }
        if (!h.controllerOwns(slot, slot.taskId, slot.taskToken)
                || MiStepHost.getSurfaceField(slot.controller, "mTaskLeash") == null) return;
        try {
            Object wct = Class.forName("android.window.WindowContainerTransaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(wct, "setForceTranslucent", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setBounds", slot.taskToken,
                    h.logicalBounds(slot));
            applyTopConfiguration(wct, slot.taskToken, slot);
            boolean ok = applyWct("fullscreenBounds(slot=" + slot.index + ")", wct);
            MiStepHost.log("fullscreenBounds slot=" + slot.index + " task=" + slot.taskId + " ok=" + ok);
        } catch (Throwable t) {
            MiStepHost.log("fullscreenBounds slot=" + slot.index + " failed: " + t);
        }
    }

    boolean applyScaleTransform(Slot slot, Object sc) {
        if (h.holdsLiveFlipZoom() || !h.canRender(slot) || slot.promoting || !h.mayWriteSideSurface(slot)) return false;
        float k = scaleFor(slot);
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            if (slot.scaleOnLeash) {
                XposedHelpers.callMethod(tx, "setPosition", sc, 0f, 0f);
                // v1.1.3：视口也要对齐 —— 只缩不放位置会"比例对但只显示一部分"（真机反馈）。
                try {
                    XposedHelpers.callMethod(tx, "setWindowCrop", sc,
                            h.logicalBounds(slot));
                } catch (Throwable ignored) {
                    // 裁剪失败也继续，位置+矩阵仍是主体
                }
            }
            XposedHelpers.callMethod(tx, "setMatrix", sc, k, 0f, 0f, k);
            XposedHelpers.callMethod(tx, "apply");
            return true;
        } catch (Throwable t) {
            MiStepHost.log("applyScaleTransform slot=" + slot.index + " failed: " + t);
            return false;
        } finally {
            h.closeSurfaceObject(tx, "close");
        }
    }

    boolean applyWct(String what, Object wct) {
        Throwable first = null;
        // 路线 A：框架公开类 WindowOrganizer（SystemUI 里可直接 new，真机已验证）。
        try {
            Class<?> c = Class.forName("android.window.WindowOrganizer");
            Object organizer = c.getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(organizer, "applyTransaction", wct);
            MiStepHost.log("wct(" + what + ") via WindowOrganizer");
            return true;
        } catch (Throwable t) {
            first = t;
        }
        // 路线 B：从已捕获的 Shell 对象（transitions / controller）里找能 applyTransaction 的字段。
        try {
            Object applier = findWctApplier(wct);
            if (applier != null) {
                MiStepHost.log("wct(" + what + ") via " + applier.getClass().getName());
                return true;
            }
        } catch (Throwable t) {
            MiStepHost.log("wct fallback failed: " + t);
        }
        MiStepHost.log("wct(" + what + ") FAILED: " + first);
        return false;
    }

    Object findWctApplier(Object wct) {
        Class<?> wctCls;
        try {
            wctCls = Class.forName("android.window.WindowContainerTransaction");
        } catch (Throwable t) {
            return null;
        }
        Object[] roots = {h.transitions, h.slots[0].controller, h.slots[1].controller,
                h.slots[2].controller, h.slots[3].controller, h.slots[MiStepHost.SLOT_TOP].controller};
        for (Object r : roots) {
            if (r == null) {
                continue;
            }
            for (Class<?> c = r.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(r);
                        if (v == null || v == wct) {
                            continue;
                        }
                        Method m;
                        try {
                            m = v.getClass().getMethod("applyTransaction", wctCls);
                        } catch (NoSuchMethodException e) {
                            continue;
                        }
                        m.invoke(v, wct);
                        return v;
                    } catch (Throwable ignored) {
                        // 该字段不可读或不可用，继续找
                    }
                }
            }
        }
        return null;
    }

}
