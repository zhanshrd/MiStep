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

    final class Promotion {
        final Slot slot;
        final int taskId;
        final Object token;
        final java.util.List<Object> otherSideTokens = new java.util.ArrayList<>();
        volatile boolean cancelled;
        volatile boolean handoffAttempted;
        volatile boolean handoffComplete;
        boolean background;
        boolean externalLaunch;
        boolean nativeSwap;
        boolean nativeFlip;
        boolean nativeHome;
        boolean nativeCollect;
        Runnable notificationLaunch;
        long liveZoomGuardUntil;
        MiStepLayout previousLayout;
        MiStepLayout targetLayout;
        boolean candidateRecovery;
        boolean liveMainReveal;
        Object liveRevealSurface;
        volatile boolean nativeSubmitted;
        volatile boolean cancelRequested;
        volatile boolean nativeCompleting;
        boolean liveMainFocusPrepared;
        int liveMainUserId = -1;
        PromotionFocusTransfer.Result liveMainFocusResult;
        LiveSwapVisualLease liveVisual;
        MiStepLiveBackdrop liveBackdrop;
        final java.util.List<LiveSwapVisualLease> peerVisuals = new java.util.ArrayList<>();
        final java.util.List<LivePeerBinding> livePeers = new java.util.ArrayList<>();
        OwnedTask previousMainCandidate;
        LiveTask previousMain;
        LiveTask homeBeforeCover;
        LiveTask collectionHome;
        boolean previousMainOnRight;
        boolean targetMainOnRight;
        LiveLiftPreview liveLift;
        /** 交换时主任务（previousMain）的真实 leash：桥上给它挖洞做 view 级缩小动画。 */
        Object mainLeash;
        Bitmap compactSnapshot;
        Rect compactSnapshotRect;
        long sceneEpoch;
        long bindingEpoch;

        Promotion(Slot slot, Slot[] slots) {
            this.slot = slot;
            this.taskId = slot.taskId;
            this.token = slot.taskToken;
            for (Slot other : slots) {
                // A parked/frozen or already hidden cell is not a live peer that can
                // block promotion. Requiring it visible caused spurious rollbacks
                // after the user returned from Home with a stale side binding.
                if (other != slot && other.occupied && !other.parked && !other.frozen
                        && other.taskVisible && other.taskToken != null) {
                    otherSideTokens.add(other.taskToken);
                }
            }
        }
    }
