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

    final class PendingSidePromotion {
        final Slot slot;
        final int taskId;
        final Object token;
        final long sceneEpoch;
        final long bindingEpoch;
        final long deadline;
        final String pkg;
        boolean desktopLaunch;
        int attempts;

        PendingSidePromotion(Slot slot, long sceneEpoch, boolean desktopLaunch, long deadline) {
            this.slot = slot;
            taskId = slot.taskId;
            token = slot.taskToken;
            this.sceneEpoch = sceneEpoch;
            bindingEpoch = slot.taskEpoch;
            pkg = slot.pkg;
            this.desktopLaunch = desktopLaunch;
            this.deadline = deadline;
        }
    }
