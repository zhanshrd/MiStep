package com.jiusis.mistep;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.WindowManager;
import de.robv.android.xposed.XposedHelpers;

/** The main shade's modal outside area must not cover independent workspace tasks. */
final class MiStepMainPanelTouchRegion {
    static boolean crop(Object window, Object display, Region region,
            WindowManager.LayoutParams attrs) {
        if (window == null || display == null || region == null || attrs == null
                || attrs.type != 2040 || !"com.android.systemui".equals(attrs.packageName)
                || !"NotificationShade".contentEquals(attrs.getTitle())
                || XposedHelpers.callMethod(window, "getDisplayContent") != display
                || XposedHelpers.getObjectField(window, "mActivityRecord") != null) return false;
        // The recorded shade uses native window pixels. Leave size-compat windows alone:
        // their OEM scaling policy determines the surface-coordinate conversion.
        if (XposedHelpers.getFloatField(window, "mInvGlobalScale") != 1f) return false;
        Object frames = XposedHelpers.getObjectField(window, "mWindowFrames");
        Rect frame = (Rect) XposedHelpers.getObjectField(frames, "mFrame");
        if (frame == null || frame.isEmpty()) return false;
        // getSurfaceTouchableRegion already subtracts exclusions and translates the
        // region to this surface's origin. Preserve its holes; change only the limit.
        region.op(new Rect(0, 0, frame.width(), frame.height()), Region.Op.INTERSECT);
        return true;
    }
}
