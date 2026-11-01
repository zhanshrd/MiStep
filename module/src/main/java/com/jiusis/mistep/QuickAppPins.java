package com.jiusis.mistep;

import android.content.Context;
import android.provider.Settings;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** SystemUI-owned per-user preferences; never stored in a process-wide app cache. */
final class QuickAppPins {
    private static final String KEY = "mistep_quick_app_pins_v1";
    private QuickAppPins() {}

    static int user() {
        try { return (Integer) XposedHelpers.callStaticMethod(android.app.ActivityManager.class, "getCurrentUser"); }
        catch (Throwable unavailable) { return -1; }
    }

    static List<String> read(Context context, int user) {
        if (user < 0) throw new IllegalStateException("current user unavailable");
        return QuickAppOrder.decode((String) XposedHelpers.callStaticMethod(Settings.Secure.class,
                "getStringForUser", context.getContentResolver(), KEY, user));
    }

    static boolean save(Context context, int user, Iterable<String> keys) {
        if (user < 0 || user != user()) return false;
        try {
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(Settings.Secure.class,
                    "putStringForUser", context.getContentResolver(), KEY, QuickAppOrder.encode(keys), user));
        } catch (Throwable unavailable) { return false; }
    }

    static List<QuickApp> arrange(Context context, List<QuickApp> apps) {
        try { return QuickAppOrder.arrange(apps, read(context, user()), app -> app.component.flattenToString()); }
        catch (Throwable unavailable) { return apps; }
    }
}
