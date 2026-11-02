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

    final class Slot implements SlotPositions.Entry {
        int index;
        View view;
        View hint;
        Object controller;
        int taskId = -1;
        Object taskToken;
        int[] rect;
        int windowMode = -1;
        TaskWindowState windowState;
        boolean occupied;
        boolean taskVisible;
        boolean reassertPending;
        long modeGuardUntil;
        int modeRepairAttempts;
        boolean modeAdmissionPending;
        final VisibilityRepair visibilityRepair = new VisibilityRepair();
        // v0.9 缩放渲染：任务按满屏排版，靠矩阵缩到格子里
        Object scaleSurface;
        Object cellBackground;
        Object cellBackgroundParent;
        int cellBackgroundWidth;
        int cellBackgroundHeight;
        boolean scaleOnLeash;
        int scaleTaskId = Integer.MIN_VALUE;
        float scaleApplied = -1f;
        int scaleAttempts;
        int taskUid;
        String pkg;
        // A tap swaps; an outward swipe releases this exact task to the background.
        MotionEvent bufferedDown;
        long touchTaskEpoch, touchSceneEpoch;
        boolean injecting;
        volatile boolean promoting;
        boolean launching;
        int retiredTaskId = -1;
        long taskEpoch;
        int removalCheckTask = -1;
        volatile boolean parked;
        volatile boolean frozen;
        volatile boolean closing;
        volatile boolean nativeReleasePending;
        // fullscreen-only 应用的 mirror 承载：任务仍是满屏前台，画面被移出屏幕，
        // 副本镜像显示在格子里；点击格子把原任务移回主区。
        int carrierTaskId = -1;
        Object carrierLeash;
        Object carrierMirror;
        View carrierTapView;
        boolean carrier;
        PendingSidePromotion pendingSidePromotion;

        Slot(int index) {
            this.index = index;
        }

        public int position() { return index; }
        public void position(int value) { index = value; }
    }
