package com.jiusis.mistep;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * mirror 直录层 + fullscreen-only 应用承载（carrier）子系统。
 *  - mirror 层：透明 overlay 窗口，SurfaceControl.mirrorSurface 实时镜像主区任务（调试 POC）。
 *  - direct carrier：把原任务 leash 直接变换进副格（免 mirror 形态，任务保持满屏前台）。
 *  - carrier backdrop：承载期间主区显示 Home 快照占位 + 点击层，点按把任务移回主区。
 * 自 MiStepHost 原样搬出；宿主内保留同名委托 shim。
 * 状态字段 carrierBackdrop / carrierSlotIndex 留在宿主（布局刷新等路径也在用），经 h.* 读写。
 */
final class HostMirrorController {
    private final MiStepHost h;

    HostMirrorController(MiStepHost host) {
        h = host;
    }

    private Object mirrorPocSurface;
    private Object mirrorPocLeash;
    private int mirrorPocHideMode;

    /** 镜像专用透明 overlay 窗口：位于宿主之上、不接收触摸，mirror 作为它的子层。 */
    private FrameLayout mirrorLayerRoot;
    private WindowManager mirrorLayerWm;
    private WindowManager.LayoutParams mirrorLayerParams;

    void createMirrorLayer() {
        if (mirrorLayerRoot != null) return;
        try {
            mirrorLayerRoot = new FrameLayout(h.ctx);
            mirrorLayerRoot.setBackgroundColor(0x00000000);
            mirrorLayerWm = (WindowManager) h.ctx.getSystemService(Context.WINDOW_SERVICE);
            mirrorLayerParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            mirrorLayerParams.gravity = Gravity.TOP | Gravity.START;
            mirrorLayerParams.setTitle("MiStepMirrorLayer");
            HostWindowPolicy.configure(mirrorLayerParams);
            try { mirrorLayerParams.setFitInsetsTypes(0); } catch (Throwable ignored) {}
            try {
                mirrorLayerParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } catch (Throwable ignored) {}
            mirrorLayerWm.addView(mirrorLayerRoot, mirrorLayerParams);
            MiStepHost.log("mirror layer window added");
        } catch (Throwable error) {
            MiStepHost.log("mirror layer window failed: " + error);
            mirrorLayerRoot = null;
        }
    }

    void setMirrorLayerVisible(boolean visible) {
        if (mirrorLayerRoot == null) return;
        try {
            if (mirrorLayerRoot.getVisibility() != (visible ? View.VISIBLE : View.GONE)) {
                mirrorLayerRoot.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /** mirror 层窗口的 SurfaceControl；失败时返回 null。 */
    private Object mirrorLayerSurface() {
        try {
            if (mirrorLayerRoot == null) return null;
            Object viewRoot = XposedHelpers.callMethod(mirrorLayerRoot, "getViewRootImpl");
            return viewRoot == null ? null
                    : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
        } catch (Throwable error) {
            MiStepHost.log("mirror layer surface unavailable: " + error);
            return null;
        }
    }

    /**
     * POC：从 ShellTaskOrganizer.getTaskLeash(taskId) 取真实任务 leash，
     * SurfaceControl.mirrorSurface 生成实时镜像，父挂到宿主窗口层并按目标矩形缩放。
     * hideMode 1/2/3/4 分别试验 alpha/移出屏幕/空 crop/hide 隐藏原任务，
     * 目标是镜像仍然实时、而主区不再显示原任务；结束时恢复。
     */
    void startMirrorPoc(int requestedTask, Rect dest, int holdMs, int hideMode) {
        try {
            stopMirrorPoc();
            if (requestedTask < 0) {
                MiStepHost.LiveTask main = h.findMainTask();
                if (main == null) {
                    MiStepHost.log("mirror POC: no main task");
                    android.widget.Toast.makeText(h.ctx, "mirror: 主区无应用",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                requestedTask = main.id;
            }
            Object organizer = null;
            for (MiStepHost.Slot slot : h.slots) {
                if (slot.controller != null) {
                    organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                    break;
                }
            }
            if (organizer == null) {
                MiStepHost.log("mirror POC: no ShellTaskOrganizer on controllers");
                return;
            }
            Object leash = XposedHelpers.callMethod(organizer, "getTaskLeash",
                    new Class<?>[]{int.class}, requestedTask);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                MiStepHost.log("mirror POC: leash unavailable task=" + requestedTask + " leash=" + leash);
                return;
            }
            if (hideMode == 5) {
                // 免 mirror 承载：把原任务 leash 直接变换进格子（原任务窗口仍是满屏，
                // 只是显示变换成格子大小/位置），验证画面与触摸能否同时成立。
                if (!applyDirectCarrier(leash, dest, true)) return;
                mirrorPocLeash = leash;
                mirrorPocHideMode = 5;
                MiStepHost.log("direct carrier shown task=" + requestedTask + " dest=" + dest);
                h.main.postDelayed(this::stopMirrorPoc, holdMs);
                return;
            }
            Object parent = mirrorLayerSurface();
            if (parent == null) {
                Object viewRoot = XposedHelpers.callMethod(h.root, "getViewRootImpl");
                parent = viewRoot == null ? null
                        : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
            }
            if (parent == null) {
                MiStepHost.log("mirror POC: host window SurfaceControl unavailable");
                return;
            }
            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            Object mirror = XposedHelpers.callStaticMethod(scClass, "mirrorSurface", leash);
            float sx = dest.width() / (float) h.screenW;
            float sy = dest.height() / (float) h.screenH;
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                hideMirrorSource(tx, leash, hideMode);
                XposedHelpers.callMethod(tx, "reparent", mirror, parent);
                XposedHelpers.callMethod(tx, "setLayer", mirror, 1000000);
                XposedHelpers.callMethod(tx, "setPosition", mirror,
                        (float) dest.left, (float) dest.top);
                XposedHelpers.callMethod(tx, "setMatrix", mirror, sx, 0f, 0f, sy);
                XposedHelpers.callMethod(tx, "setAlpha", mirror, 1f);
                XposedHelpers.callMethod(tx, "show", mirror);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            mirrorPocSurface = mirror;
            mirrorPocLeash = leash;
            mirrorPocHideMode = hideMode;
            MiStepHost.log("mirror POC shown task=" + requestedTask + " dest=" + dest
                    + " scale=" + sx + "x" + sy + " hideMode=" + hideMode
                    + " leash=" + leash + " parent=" + parent);
            h.main.postDelayed(this::stopMirrorPoc, holdMs);
        } catch (Throwable error) {
            MiStepHost.log("mirror POC failed: " + error);
            XposedBridge.log(error);
            stopMirrorPoc();
        }
    }

    /**
     * 试不同的“原任务隐藏”方式，目标是镜像仍然实时、而主区不再显示原任务：
     * 0=不隐藏；1=alpha 0（已证会让镜像一起变空）；2=移出屏幕；3=空 crop；4=hide。
     */
    private void hideMirrorSource(Object tx, Object leash, int mode) {
        try {
            switch (mode) {
                case 1:
                    XposedHelpers.callMethod(tx, "setAlpha", leash, 0f);
                    break;
                case 2:
                    XposedHelpers.callMethod(tx, "setPosition", leash, -4000f, -4000f);
                    break;
                case 3:
                    XposedHelpers.callMethod(tx, "setCrop", leash, new Rect(0, 0, 0, 0));
                    break;
                case 4:
                    XposedHelpers.callMethod(tx, "hide", leash);
                    break;
                default:
                    break;
            }
        } catch (Throwable error) {
            MiStepHost.log("mirror hide mode " + mode + " failed: " + error);
        }
    }

    private void restoreMirrorSource(Object tx, Object leash, int mode) {
        try {
            switch (mode) {
                case 1:
                    XposedHelpers.callMethod(tx, "setAlpha", leash, 1f);
                    break;
                case 2:
                    XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
                    break;
                case 3:
                    XposedHelpers.callMethod(tx, "setCrop",
                            new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                    android.graphics.Rect.class}, leash, null);
                    break;
                case 4:
                    XposedHelpers.callMethod(tx, "show", leash);
                    break;
                default:
                    break;
            }
        } catch (Throwable error) {
            MiStepHost.log("mirror restore mode " + mode + " failed: " + error);
        }
    }

    /**
     * 免 mirror 承载：屏幕坐标 = 放大倍率 × 显示内容坐标 + 主区偏移，因此把格子的
     * 屏幕矩形反算成任务 leash 的位置/缩放。原任务窗口仍占满屏、输入通道不变。
     */
    private boolean applyDirectCarrier(Object leash, Rect dest, boolean logSuccess) {
        try {
            final float ratio = h.layout.mainScale;
            final float sx = dest.width() / (ratio * h.screenW);
            final float sy = dest.height() / (ratio * h.screenH);
            final float px = (dest.left - h.layout.mainOffsetX) / ratio;
            final float py = (dest.top - h.layout.mainOffsetY) / ratio;
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition", leash, px, py);
                XposedHelpers.callMethod(tx, "setMatrix", leash, sx, 0f, 0f, sy);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            if (logSuccess) {
                MiStepHost.log("direct carrier transform ratio=" + ratio + " scale=" + sx + "x" + sy
                        + " pos=" + px + "," + py);
            }
            return true;
        } catch (Throwable error) {
            MiStepHost.log("direct carrier failed: " + error);
            return false;
        }
    }

    private void restoreDirectCarrier(Object leash) {
        try {
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            MiStepHost.log("direct carrier restored task to main");
        } catch (Throwable error) {
            MiStepHost.log("direct carrier restore failed: " + error);
        }
    }

    private void stopMirrorPoc() {
        Object mirror = mirrorPocSurface;
        Object leash = mirrorPocLeash;
        int hideMode = mirrorPocHideMode;
        mirrorPocSurface = null;
        mirrorPocLeash = null;
        mirrorPocHideMode = 0;
        if (hideMode == 5) {
            if (leash != null && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                restoreDirectCarrier(leash);
            }
            return;
        }
        if (mirror == null) return;
        try {
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                if (hideMode != 0 && leash != null
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    restoreMirrorSource(tx, leash, hideMode);
                }
                XposedHelpers.callMethod(tx, "remove", mirror);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            XposedHelpers.callMethod(mirror, "release");
            MiStepHost.log("mirror POC removed; restoredHideMode=" + hideMode);
        } catch (Throwable error) {
            MiStepHost.log("mirror POC cleanup failed: " + error);
        }
    }

    Object shellOrganizer() {
        for (MiStepHost.Slot slot : h.slots) {
            if (slot.controller != null) {
                return XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- mirror 承载（fullscreen-only 应用进副格）

    /**
     * 把 fullscreen-only 应用（相机等）放进副格：
     * 任务保持满屏前台（预览继续刷新），直接把任务 leash 变换成格子的大小/位置；
     * 主区显示桌面快照。此形态真机验证宿主仍能收到触摸（mirror 形态会被 CLONE 输入窗口挡住）。
     */
    private boolean attachMirrorCarrier(MiStepHost.Slot slot, MiStepHost.LiveTask task) {
        if (slot == null || task == null || h.scene != MiStepHost.ACTIVE || h.carrierSlotIndex >= 0) return false;
        if (slot.rect == null) return false;
        try {
            Object organizer = shellOrganizer();
            Object leash = organizer == null ? null
                    : XposedHelpers.callMethod(organizer, "getTaskLeash",
                            new Class<?>[]{int.class}, task.id);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                MiStepHost.log("carrier: no task leash for task=" + task.id);
                return false;
            }
            final Rect dest = new Rect(slot.rect[0], slot.rect[1], slot.rect[2], slot.rect[3]);
            // 不用 mirror：直接把原任务 leash 变换进格子。真机验证这种形态下宿主仍能收到
            // 触摸（mirror 形态会被 MIUI 的 CLONE 输入窗口挡住），且任务仍是前台、预览继续。
            if (!applyDirectCarrier(leash, dest, true)) {
                MiStepHost.log("carrier: direct transform failed task=" + task.id);
                return false;
            }
            slot.carrier = true;
            slot.carrierTaskId = task.id;
            slot.carrierLeash = leash;
            slot.carrierMirror = null;
            slot.taskId = task.id;
            slot.taskToken = null;
            slot.pkg = task.pkg;
            slot.occupied = true;
            slot.parked = false;
            slot.frozen = false;
            slot.taskVisible = true;
            slot.launching = false;
            // 底板/hint 是不透明的宿主 View，会盖住下面的真实任务画面：承载格必须让位。
            View backing = h.sideBackingViews[slot.index];
            if (backing != null) backing.setVisibility(View.GONE);
            if (slot.hint != null) slot.hint.setVisibility(View.GONE);
            // 格子的 SF 纯色底也是宿主层的子 surface，同样会挡住真实任务画面。
            h.removeCellBackground(slot);
            h.carrierSlotIndex = slot.index;
            showCarrierBackdrop(task.pkg, slot.index);
            // 承载格没有 TaskView 可点：在格子上放一个透明点击层，点它把任务放回主区。
            try {
                final View tap = new View(h.ctx);
                tap.setBackgroundColor(0x01000000);
                tap.setOnClickListener(v -> {
                    MiStepHost.log("carrier tap layer clicked slot=" + slot.index);
                    restoreCarrierToMain();
                });
                h.root.setOnTouchListener((v, event) -> {
                    if (h.carrierSlotIndex >= 0 && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        MiStepHost.log("carrier root touch down " + event.getRawX() + "," + event.getRawY());
                    }
                    return false;
                });
                FrameLayout.LayoutParams tapParams =
                        new FrameLayout.LayoutParams(dest.width(), dest.height());
                tapParams.leftMargin = dest.left;
                tapParams.topMargin = dest.top;
                h.root.addView(tap, tapParams);
                slot.carrierTapView = tap;
            } catch (Throwable error) {
                MiStepHost.log("carrier tap layer failed: " + error);
            }
            h.root.requestLayout();
            h.root.invalidate();
            MiStepHost.log("carrier attached (direct) task=" + task.id + " slot=" + slot.index
                    + " dest=" + dest + " leash=" + leash);
            android.widget.Toast.makeText(h.ctx, "已放入副格",
                    android.widget.Toast.LENGTH_SHORT).show();
            return true;
        } catch (Throwable error) {
            MiStepHost.log("carrier attach failed: " + error);
            XposedBridge.log(error);
            restoreCarrierToMain();
            return false;
        }
    }

    /** 主区在承载期间没有真实任务可看，用 Home 快照占位并提示返回方式。 */
    private void showCarrierBackdrop(String pkg, int slotIndex) {
        if (h.carrierBackdrop != null || h.root == null || h.layout == null) return;
        try {
            FrameLayout box = new FrameLayout(h.ctx);
            if (h.mainBackdrop != null && !h.mainBackdrop.isRecycled()) {
                android.widget.ImageView image = new android.widget.ImageView(h.ctx);
                image.setImageBitmap(h.mainBackdrop);
                image.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                box.addView(image, new FrameLayout.LayoutParams(-1, -1));
                View shade = new View(h.ctx);
                shade.setBackgroundColor(HyperOsStyle.dark(h.ctx) ? 0x33000000 : 0x22000000);
                box.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            } else {
                box.setBackgroundColor(HyperOsStyle.page(h.ctx));
            }
            TextView hint = new TextView(h.ctx);
            hint.setText(pkg + " 在 " + slotIndex + " 号窗口\n点按返回主区");
            hint.setTextColor(HyperOsStyle.textPrimary(h.ctx));
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            hint.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            hint.setGravity(Gravity.CENTER);
            hint.setBackground(HyperOsStyle.glassSheet(h.ctx, 22f));
            hint.setPadding(h.dp(18), h.dp(12), h.dp(18), h.dp(12));
            FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1, h.dp(82));
            hintParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            hintParams.leftMargin = h.dp(16);
            hintParams.rightMargin = h.dp(16);
            hintParams.bottomMargin = h.dp(16);
            box.addView(hint, hintParams);
            box.setOnClickListener(v -> restoreCarrierToMain());
            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(h.layout.mainDispWidth, h.layout.mainDispHeight);
            params.leftMargin = h.layout.mainOffsetX;
            params.topMargin = h.layout.mainOffsetY;
            h.root.addView(box, params);
            h.carrierBackdrop = box;
            MiStepHost.log("carrier backdrop added for " + pkg + " slot=" + slotIndex);
        } catch (Throwable error) {
            MiStepHost.log("carrier backdrop failed: " + error);
        }
    }

    private void removeCarrierBackdrop() {
        View box = h.carrierBackdrop;
        h.carrierBackdrop = null;
        if (box != null && h.root != null) {
            try { h.root.removeView(box); } catch (Throwable ignored) {}
            h.root.requestLayout();
            h.root.invalidate();
        }
    }

    /** 心跳/系统重排后重新声明“原任务 leash 在格子里的位置/缩放”。 */
    void reassertCarrier() {
        for (MiStepHost.Slot slot : h.slots) {
            if (!slot.carrier) continue;
            Object leash = slot.carrierLeash;
            if (leash == null || slot.rect == null) continue;
            try {
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    MiStepHost.log("carrier leash invalid; restoring task to main");
                    restoreCarrierToMain();
                    return;
                }
                Rect dest = new Rect(slot.rect[0], slot.rect[1], slot.rect[2], slot.rect[3]);
                applyDirectCarrier(leash, dest, false);
            } catch (Throwable error) {
                MiStepHost.log("carrier reassert failed: " + error);
            }
        }
    }

    /** 把承载中的任务移回主区（点击格子/主区、退出一步、系统返回都走这里）。 */
    void restoreCarrierToMain() {
        boolean restored = false;
        for (MiStepHost.Slot slot : h.slots) {
            if (!slot.carrier) continue;
            Object leash = slot.carrierLeash;
            try {
                if (leash != null
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    restoreDirectCarrier(leash);
                }
            } catch (Throwable error) {
                MiStepHost.log("carrier restore failed: " + error);
            }
            slot.carrier = false;
            slot.carrierTaskId = -1;
            slot.carrierLeash = null;
            slot.carrierMirror = null;
            View tap = slot.carrierTapView;
            slot.carrierTapView = null;
            if (tap != null && h.root != null) {
                try { h.root.removeView(tap); } catch (Throwable ignored) {}
            }
            slot.occupied = false;
            slot.taskId = -1;
            slot.taskToken = null;
            slot.pkg = null;
            slot.taskVisible = false;
            View backing = h.sideBackingViews[slot.index];
            if (backing != null) backing.setVisibility(View.VISIBLE);
            if (slot.hint != null) slot.hint.setVisibility(View.VISIBLE);
            MiStepHost.log("carrier restored slot=" + slot.index + " task to main");
            restored = true;
        }
        h.carrierSlotIndex = -1;
        removeCarrierBackdrop();
        if (h.root != null) {
            h.root.setOnTouchListener(null);
        }
        if (restored && h.root != null) {
            h.root.requestLayout();
            h.root.invalidate();
        }
    }

    /**
     * 清掉主任务 leash 上可能残留的过渡裁切。旧版 clampOpeningTransition 把动画期的
     * crop 写进了 finish 事务，任务回到常驻后仍被裁到主区矩形（顶栏启动的应用只显示
     * 一部分）；这里在进入一步、快捷启动后、主任务变化时主动清一次。
     */
    private void clearMainTaskLeashCrop(int taskId) {
        if (taskId < 0) return;
        try {
            Object organizer = shellOrganizer();
            if (organizer == null) return;
            Object leash = XposedHelpers.callMethod(organizer, "getTaskLeash",
                    new Class<?>[]{int.class}, taskId);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                return;
            }
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setCrop",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                android.graphics.Rect.class}, leash, null);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            MiStepHost.log("main task leash crop cleared task=" + taskId);
        } catch (Throwable error) {
            MiStepHost.log("clear main task crop failed task=" + taskId + ": " + error);
        }
    }

    /** 延时清一次主任务 leash 裁切（进入一步/恢复/快捷启动后调用）。 */
    void scheduleMainCropClear(long delayMs) {
        h.main.postDelayed(() -> {
            if (h.root == null || h.scene == MiStepHost.SUSPENDED) return;
            if (!h.executeOnShell(() -> {
                try {
                    MiStepHost.LiveTask mainTask = h.findMainTask();
                    if (mainTask != null) clearMainTaskLeashCrop(mainTask.id);
                } catch (Throwable error) {
                    MiStepHost.log("main task crop clear failed: " + error);
                }
            })) {
                MiStepHost.log("main task crop clear skipped: no shell queue");
            }
        }, delayMs);
    }
}
