package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** TaskView 创建/装载/启动链与启动闩锁 */
final class HostEntryLaunch {
    static final HostEntryLaunch INSTANCE = new HostEntryLaunch();

    private HostEntryLaunch() {
    }

    void createTaskView() throws Exception {
        Consumer<Object> onCreated = new Consumer<Object>() {
            @Override
            public void accept(Object view) {
                HostEntry.log("TaskView created -> " + view);
                HostEntry.sTaskView = view;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        attachAndLaunch();
                    }
                });
            }
        };
        XposedHelpers.callMethod(HostEntry.sTaskViewFactory, "create", HostEntry.sContext, HostEntry.sShellExecutor, onCreated);
        HostEntry.log("factory.create invoked");
    }

    void attachAndLaunch() {
        try {
            if (HostEntry.sContext == null || HostEntry.sTaskView == null) {
                HostEntry.log("attachAndLaunch: missing context/taskview");
                return;
            }
            final View taskView = (View) HostEntry.sTaskView;

            // 监听 TaskView 生命周期：这些回调决定后续「格子绑定/交换」的状态机
            Object listener = makeListener(HostEntry.sShellExecutor, "com.android.wm.shell.taskview.TaskView$Listener");
            if (listener != null) {
                XposedHelpers.callMethod(taskView, "setListener", HostEntry.sShellExecutor, listener);
                HostEntry.log("setListener OK");
            }

            LinearLayout root = new LinearLayout(HostEntry.sContext);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xE6000000);
            root.setPadding(16, 16, 16, 16);

            TextView label = new TextView(HostEntry.sContext);
            label.setText("Mi Step宿主 v0.3 — 真实应用承载测试");
            label.setTextColor(Color.WHITE);
            label.setTextSize(15f);
            root.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            if (taskView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) taskView.getParent()).removeView(taskView);
            }
            root.addView(taskView, new LinearLayout.LayoutParams(HostEntry.BOX_W, HostEntry.BOX_H));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = HostEntry.BOX_X;
            lp.y = HostEntry.BOX_Y;
            lp.setTitle("MiStepHostBox");

            WindowManager wm = (WindowManager) HostEntry.sContext.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(root, lp);
            HostEntry.sWindowRoot = root;
            HostEntry.log("window with TaskView added: " + root + " box=" + HostEntry.BOX_W + "x" + HostEntry.BOX_H + "@" + HostEntry.BOX_X + "," + HostEntry.BOX_Y);

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    launchTargetApp(taskView);
                }
            }, HostEntry.AFTER_ADD_MS);
        } catch (Throwable t) {
            HostEntry.log("attachAndLaunch FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    java.io.File latchFile() {
        return new java.io.File(HostEntry.sContext.getFilesDir(), "mistep-launched");
    }

    void armLatch() {
        try {
            java.io.File f = latchFile();
            if (!f.exists() && f.createNewFile()) {
                HostEntry.log("latch armed at " + f);
            }
        } catch (Throwable t) {
            HostEntry.log("could not arm latch: " + t);
        }
    }

    void launchTargetApp(View taskView) {
        try {
            Intent launch = HostEntry.sContext.getPackageManager().getLaunchIntentForPackage(HostEntry.TARGET_PKG);
            if (launch == null) {
                HostEntry.log("no launch intent for " + HostEntry.TARGET_PKG);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Rect bounds = new Rect(0, 0, HostEntry.BOX_W, HostEntry.BOX_H);
            ActivityOptions options = ActivityOptions.makeBasic();
            HostEntrySupport.callHidden(options, "setLaunchBounds", bounds);
            HostEntrySupport.callHidden(options, "setLaunchCookie", new Binder());
            HostEntrySupport.callHidden(options, "setLaunchWindowingMode", 6);          // WINDOWING_MODE_MULTI_WINDOW
            HostEntrySupport.callHidden(options, "setRemoveWithTaskOrganizer", Boolean.TRUE);

            // pendingIntent 必须非空：TaskViewTransitions.startActivity 第一行就
            // pendingIntent.getIntent()，且真正的启动走
            // WindowContainerTransaction.sendPendingIntent(pendingIntent, intent, options)。
            // 传 null 会让 SystemUI 在 wmshell.main 上 NPE 崩溃（已实测）。
            PendingIntent pi = PendingIntent.getActivity(HostEntry.sContext, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE);

            // 同理不能用 XposedHelpers.callMethod：它靠实参类型匹配，遇到 null 实参会抛
            // "Attempt to get length of null array"。改用显式 Method.invoke。
            Method start = null;
            for (Class<?> c = taskView.getClass(); c != null && start == null; c = c.getSuperclass()) {
                try {
                    start = c.getDeclaredMethod("startActivity",
                            PendingIntent.class, Intent.class, ActivityOptions.class, Rect.class);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
            if (start == null) {
                HostEntry.log("startActivity(PendingIntent, Intent, ActivityOptions, Rect) not found");
                return;
            }
            // 先上锁再调用：万一这次调用崩掉 SystemUI，重启后的新实例会因锁存在而不再重试
            armLatch();
            start.setAccessible(true);
            start.invoke(taskView, pi, launch, options, bounds);
            HostEntry.log("startActivity invoked for " + HostEntry.TARGET_PKG + " pi=" + pi + " bounds=" + bounds);
        } catch (Throwable t) {
            HostEntry.log("launchTargetApp FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    Object makeListener(Executor executor, String ifaceName) {
        try {
            Class<?> iface = XposedHelpers.findClass(ifaceName, HostEntry.sContext.getClassLoader());
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            StringBuilder sb = new StringBuilder("listener#").append(method.getName()).append('(');
                            if (args != null) {
                                for (int i = 0; i < args.length; i++) {
                                    sb.append(i > 0 ? ", " : "").append(args[i]);
                                }
                            }
                            HostEntry.log(sb.append(')').toString());
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return Boolean.FALSE;
                            }
                            if (rt == int.class) {
                                return 0;
                            }
                            if (rt == long.class) {
                                return 0L;
                            }
                            return null;
                        }
                    });
        } catch (Throwable t) {
            HostEntry.log("makeListener failed: " + t);
            return null;
        }
    }

}
