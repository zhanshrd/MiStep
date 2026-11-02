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

    final class LiveSwapVisualLease {
        private static final class AlphaHold {
            int count;
            float alpha;
            AlphaHold(float alpha) { this.alpha = alpha; }
        }
        private static final java.util.IdentityHashMap<View, AlphaHold> held = new java.util.IdentityHashMap<>();
        final View backing, hint;
        final float backingAlpha, hintAlpha;
        final int backingVisibility, hintVisibility;
        boolean restored;
        LiveSwapVisualLease(View backing, View hint) {
            this.backing = backing; this.hint = hint;
            backingAlpha = backing == null ? 1f : backing.getAlpha();
            hintAlpha = hint == null ? 1f : hint.getAlpha();
            backingVisibility = backing == null ? View.GONE : backing.getVisibility();
            hintVisibility = hint == null ? View.GONE : hint.getVisibility();
            acquire(backing);
            acquire(hint);
        }
        private static void acquire(View view) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold == null) { hold = new AlphaHold(view.getAlpha()); held.put(view, hold); }
            hold.count++; view.setAlpha(0f);
        }
        static void writeAlpha(View view, float alpha) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold != null) hold.alpha = alpha;
            else view.setAlpha(alpha);
        }
        private static void release(View view) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold != null && --hold.count == 0) {
                held.remove(view); view.setAlpha(hold.alpha);
            }
        }
        void restore() {
            if (restored) return;
            restored = true;
            // Nested handoffs and late fade callbacks cannot resurrect shared chrome.
            // Restore the latest requested alpha only when the last owner releases it.
            release(backing);
            release(hint);
        }
    }
