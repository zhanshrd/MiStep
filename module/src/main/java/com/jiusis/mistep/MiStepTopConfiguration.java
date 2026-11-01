package com.jiusis.mistep;

import android.content.res.Configuration;
import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;

/** Keep each upper Activity's metrics in the viewport already selected by its Task. */
final class MiStepTopConfiguration {
    static boolean apply(Object activity, Object task, Configuration parent) {
        if (activity == null || parent == null || !MiStepSideRoleChange.isSide(task)) return false;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if (bounds == null || bounds.width() <= bounds.height()) return false;
        Object parentWindow = XposedHelpers.getObjectField(parent, "windowConfiguration");
        Rect parentBounds = (Rect) XposedHelpers.callMethod(parentWindow, "getBounds");
        Rect appBounds = (Rect) XposedHelpers.callMethod(parentWindow, "getAppBounds");
        // Only the explicit full-viewport TOP configuration opts in. Other Shell tasks do not.
        if (!bounds.equals(parentBounds) || !bounds.equals(appBounds)
                || parent.densityDpi <= 0 || parent.screenWidthDp <= 0
                || parent.screenHeightDp <= 0 || parent.smallestScreenWidthDp <= 0) return false;
        Configuration resolved = (Configuration) XposedHelpers.callMethod(
                activity, "getResolvedOverrideConfiguration");
        Object window = XposedHelpers.getObjectField(resolved, "windowConfiguration");
        XposedHelpers.callMethod(window, "setAppBounds", new Rect(bounds));
        // Display APIs used by video layouts and dialogs must not return the physical phone size.
        XposedHelpers.callMethod(window, "setMaxBounds", new Rect(bounds));
        resolved.densityDpi = parent.densityDpi;
        resolved.screenWidthDp = parent.screenWidthDp;
        resolved.screenHeightDp = parent.screenHeightDp;
        resolved.smallestScreenWidthDp = parent.smallestScreenWidthDp;
        resolved.orientation = parent.orientation;
        resolved.screenLayout = parent.screenLayout;
        return true;
    }
}
