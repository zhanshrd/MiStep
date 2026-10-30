package com.jiusis.mistep;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * MiStepHost 的纯静态小工具集合（无状态，只依赖入参）。
 * 自 MiStepHost 原样搬出；宿主内保留同名委托 shim，调用点零改动。
 */
final class HostStaticUtil {
    private static final String TAG = "MiStepProbe";
    private static final int SLOT_COUNT = 1 + MiStepLayout.CELL_COUNT;

    private HostStaticUtil() {
    }

    static void log(String msg) {
        android.util.Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            // 至少保留 logcat
        }
    }

    static void callHidden(Object target, String name, Object... args) {
        try {
            XposedHelpers.callMethod(target, name, args);
        } catch (Throwable t) {
            log("hidden " + name + " failed: " + t);
        }
    }

    static Method findStartActivity(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod("startActivity",
                        PendingIntent.class, Intent.class, ActivityOptions.class, Rect.class);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    static int windowModeOf(Object info) {
        try {
            Object config = XposedHelpers.getObjectField(info, "configuration");
            Object winConfig = XposedHelpers.getObjectField(config, "windowConfiguration");
            return (Integer) XposedHelpers.callMethod(winConfig, "getWindowingMode");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int configurationOrientationOf(Object info) {
        try {
            Object config = XposedHelpers.getObjectField(info, "configuration");
            return XposedHelpers.getIntField(config, "orientation");
        } catch (Throwable ignored) {
            return TaskWindowState.UNKNOWN_ORIENTATION;
        }
    }

    static int requestedOrientationOf(Object info) {
        try {
            return XposedHelpers.getIntField(info, "requestedOrientation");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 诊断用：从 RunningTaskInfo 里取 appCompatTaskInfo.topActivityAppBounds（px）。 */
    static String appBoundsOf(Object info) {
        try {
            Object compat = XposedHelpers.getObjectField(info, "appCompatTaskInfo");
            if (compat == null) {
                return "null";
            }
            Object bounds = XposedHelpers.getObjectField(compat, "topActivityAppBounds");
            return String.valueOf(bounds);
        } catch (Throwable t) {
            return "unreadable(" + t + ")";
        }
    }

    static int compactionHintMask(int initialEmptySlots, int occupiedSlots) {
        return (initialEmptySlots | ~occupiedSlots) & ((1 << SLOT_COUNT) - 2);
    }

    /** 按字段名取 SurfaceControl（先本类后父类；取不到返回 null）。 */
    static Object getSurfaceField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (!"android.view.SurfaceControl".equals(f.getType().getName())) {
                    continue;
                }
                f.setAccessible(true);
                Object surface = f.get(target);
                return surface != null && Boolean.TRUE.equals(
                        XposedHelpers.callMethod(surface, "isValid")) ? surface : null;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    static int uidOf(Context ctx, String pkg) {
        if (pkg == null) {
            return 0;
        }
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            log("uidOf " + pkg + " failed: " + t);
            return 0;
        }
    }
}
