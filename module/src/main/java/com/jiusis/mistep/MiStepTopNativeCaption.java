package com.jiusis.mistep;

import android.view.MotionEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** A hosted upper task does not own the native caption in the magnified main area. */
final class MiStepTopNativeCaption {
    static void install(ClassLoader loader) {
        try {
            Class<?> dot=XposedHelpers.findClass(
                    "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDot",loader);
            XposedHelpers.findAndHookMethod(dot,"isTouchedInCaption",MotionEvent.class,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if(!owned(p.thisObject))return;
                    p.setResult(false);
                    if(((MotionEvent)p.args[0]).getActionMasked()==MotionEvent.ACTION_DOWN)
                        XposedBridge.log("MiStepProbe: upper native caption hit excluded");
                }
            });
            XposedHelpers.findAndHookMethod(dot,"handleCaptionClicked",new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if(!owned(p.thisObject))return;
                    p.setResult(null);
                    XposedBridge.log("MiStepProbe: upper native caption menu excluded");
                }
            });
            XposedBridge.log("MiStepProbe: upper native caption scope installed");
        } catch(Throwable error) {
            XposedBridge.log("MiStepProbe: upper native caption hooks unavailable: "+error);
        }
    }

    private static boolean owned(Object dot) {
        try {
            return MiStepHost.ownsTopNativeCaption(XposedHelpers.getObjectField(dot,"mRunningTaskInfo"));
        } catch(Throwable ignored) {return false;}
    }
}
