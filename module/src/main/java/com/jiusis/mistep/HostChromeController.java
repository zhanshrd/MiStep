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

/** 提示条 / 空格十字提示 / 顶栏状态 / 壁纸底图 / 快捷应用刷新 */
final class HostChromeController {
    private final MiStepHost h;

    HostChromeController(MiStepHost host) { h = host; }

    TextView makeEmptyHint() {
        TextView tv = new TextView(h.ctx) {
            private final android.graphics.Paint plus = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                if (length() != 0) return;
                drawEmptyCellPlus(canvas, getWidth(), getHeight(), getCurrentTextColor(), plus);
            }
        };
        // SurfaceView clears a hole through the host. Paint above that hole while empty.
        tv.setBackground(HyperOsStyle.emptyCell(h.ctx));
        tv.setText("");
        tv.setTextColor(HyperOsStyle.textSecondary(h.ctx));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 38f);
        tv.setTypeface(android.graphics.Typeface.create(
                "sans-serif-light", android.graphics.Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setContentDescription("空白窗口，点按添加应用");
        return tv;
    }

    void drawEmptyCellPlus(android.graphics.Canvas canvas, int width, int height,
            int color, android.graphics.Paint plus) {
        plus.setColor(color);
        plus.setStrokeWidth(HyperOsStyle.dp(h.ctx, 1.25f));
        plus.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        float x = width * .5f, y = height * .5f;
        float radius = HyperOsStyle.dp(h.ctx, 10f);
        canvas.drawLine(x - radius, y, x + radius, y, plus);
        canvas.drawLine(x, y - radius, x, y + radius, plus);
    }

    FrameLayout.LayoutParams hintParams(Slot slot) {
        return new FrameLayout.LayoutParams(
                slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
    }

    void updateHint(Slot slot) {
        if(slot.index==MiStepHost.SLOT_TOP) {
            h.ensureCellBackground(slot); h.updateCellSurfaceVisibility(slot);
            if(h.topCtrl.topTaskPane!=null)h.topCtrl.topTaskPane.occupied(slot.occupied && !slot.parked);
            return;
        }
        h.ensureCellBackground(slot);
        h.updateCellSurfaceVisibility(slot);
        if (slot.hint == null) {
            return;
        }
        if (slot.hint instanceof TextView) {
            TextView label = (TextView) slot.hint;
            label.setText(slot.parked ? "恢复" : "");
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    slot.parked ? 15f : 38f);
            label.setTextColor(slot.parked
                    ? HyperOsStyle.accent(h.ctx) : HyperOsStyle.textSecondary(h.ctx));
            label.setContentDescription(slot.parked
                    ? "已保留的窗口，点按恢复" : "空白窗口，点按添加应用");
        }
        slot.hint.setVisibility(slot.occupied && !slot.parked ? View.GONE : View.VISIBLE);
    }

    void refreshQuickApps() {
        if (h.topBar == null || h.root == null) return;
        final MiStepTopBar readyBar = h.topBar;
        final FrameLayout readyRoot = h.root;
        final long readyEpoch = h.sceneEpoch;
        MiStepWarmup.whenQuickAppsReady(h.ctx, apps -> {
            if (MiStepHost.sInstance == h && h.topBar == readyBar && h.root == readyRoot
                && h.sceneEpoch == readyEpoch && h.screenOn && !h.keyguardLocked)
                readyBar.setQuickApps(apps);
        });
    }

    void updateTopBarState() {
        if (h.workspaceAnimator != null || h.workspaceAlpha < 1f) return;
        boolean available = h.scene == MiStepHost.ACTIVE && h.screenOn && !h.keyguardLocked
                && h.promotion == null && !h.transferBusy;
        if (h.topBar != null) h.topBar.setAlpha(available ? 1f : 0.92f);
    }

    void loadWallpaperBackdrop() {
        if (h.wallpaperBackdropRequested) return;
        if (h.wallpaperBackdrop != null && !h.wallpaperBackdrop.isRecycled()) {
            applyWallpaperBackdrops();
            return;
        }
        if (h.screenW <= 0 || h.screenH <= 0) return;
        h.wallpaperBackdropRequested = true;
        Thread loader = new Thread(() -> {
            Bitmap ready = null;
            try {
                ready = MiStepWarmup.wallpaper(h.ctx, h.screenW, h.screenH);
            } catch (Throwable error) {
                MiStepHost.log("wallpaper blur failed: " + error);
            }
            final Bitmap bitmap = ready;
            h.main.post(() -> {
                h.wallpaperBackdropRequested = false;
                if (bitmap == null || bitmap.isRecycled()) {
                    MiStepHost.log("wallpaper blur unavailable");
                    return;
                }
                if (h.wallpaperBackdrop != null) {
                    MiStepClipboard.get(h.ctx).close();
                    h.wallpaperBackdrop.recycle();
                }
                h.wallpaperBackdrop = bitmap;
                MiStepHost.log("wallpaper gaussian backdrop ready " + bitmap.getWidth() + "x" + bitmap.getHeight());
                applyWallpaperBackdrops();
            });
        }, "MiStepWallpaperBlur");
        loader.start();
    }

    void applyWallpaperBackdrops() {
        if (h.wallpaperBackdrop == null || h.wallpaperBackdrop.isRecycled() || h.layout == null) return;
        if (h.topBar != null) h.topBar.setWallpaperBackdrop(h.wallpaperBackdrop, h.screenW, h.screenH);
        if (h.topStripBacking != null) {
            h.topStripBacking.setBackground(new WallpaperCropDrawable(h.wallpaperBackdrop, h.screenW, h.screenH,
                    0, 0, HyperOsStyle.topWallpaperTint(h.ctx)));
        }
        Slot upper=h.slots[MiStepHost.SLOT_TOP];
        if(upper!=null && upper.hint!=null && !upper.occupied)
            upper.hint.setBackground(new WallpaperCropDrawable(h.wallpaperBackdrop,h.screenW,h.screenH,
                    0,0,HyperOsStyle.topWallpaperTint(h.ctx)));
        int x = h.layout.mainOnRight ? 0 : h.layout.mainWidth;
        for (int i = 1; i < MiStepHost.CELL_SLOT_COUNT; i++) {
            View backing = h.sideBackingViews[i];
            if (backing == null) continue;
            backing.setBackground(new WallpaperCropDrawable(h.wallpaperBackdrop, h.screenW, h.screenH,
                    x, h.sideSegmentTop(i), HyperOsStyle.topWallpaperTint(h.ctx)));
            Slot slot = h.slots[i];
            if (slot == null || slot.hint == null || slot.occupied || slot.rect == null) continue;
            slot.hint.setBackground(new WallpaperCropDrawable(h.wallpaperBackdrop, h.screenW, h.screenH,
                    slot.rect[0], slot.rect[1], HyperOsStyle.topWallpaperTint(h.ctx)));
        }
    }

    void collapseStatusBarPanels() {
        try {
            Object sbm = h.ctx.getSystemService(Context.STATUS_BAR_SERVICE);
            if (sbm == null) return;
            java.lang.reflect.Method method = sbm.getClass().getMethod("collapsePanels");
            method.invoke(sbm);
            MiStepHost.log("status bar panels collapsed");
        } catch (Throwable error) {
            MiStepHost.log("collapse status bar panels failed: " + error);
        }
    }

    void registerWallpaperReceiver() {
        if (h.wallpaperReceiver != null) return;
        try {
            h.wallpaperReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    MiStepHost.log("wallpaper changed; reloading blur backdrop");
                    MiStepClipboard.get(h.ctx).close();
                    if (h.wallpaperBackdrop != null) {
                        h.wallpaperBackdrop.recycle();
                        h.wallpaperBackdrop = null;
                    }
                    h.wallpaperBackdropRequested = false;
                    loadWallpaperBackdrop();
                }
            };
            h.ctx.registerReceiver(h.wallpaperReceiver, new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
            MiStepHost.log("wallpaper change receiver registered");
        } catch (Throwable error) {
            MiStepHost.log("wallpaper receiver failed: " + error);
        }
    }

    void drawLiveEmptyHints(android.graphics.Canvas canvas, MiStepLayout original,
            MiStepLayout target, int revealEmptySlots) {
        h.swapController.drawLiveEmptyHints(canvas, original, target, revealEmptySlots);
    }

}
