package com.jiusis.mistep;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.HashSet;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Original APK resources for MiStep's independent native icons only. */
final class MiStepNativeIconContext extends ContextWrapper {
    private static final WeakHashMap<Context, WeakReference<MiStepNativeIconContext>> cache =
            new WeakHashMap<>();
    private static final HashSet<String> reportedPackages = new HashSet<>();
    private final Resources resources;
    private final Resources.Theme theme;
    private final Configuration configuration;

    static synchronized Context wrap(Context base) {
        if (base instanceof MiStepNativeIconContext) return base;
        WeakReference<MiStepNativeIconContext> ref = cache.get(base);
        MiStepNativeIconContext existing = ref == null ? null : ref.get();
        if (existing != null && existing.configuration.equals(base.getResources().getConfiguration()))
            return existing;
        try {
            MiStepNativeIconContext context = new MiStepNativeIconContext(base);
            cache.put(base, new WeakReference<>(context));
            return context;
        } catch (Throwable error) {
            // Unsupported resource construction keeps the native view functional.
            // Never replace it with an independently drawn glyph.
            MiStepOs4Controls.failed("native icon resources", error);
            return base;
        }
    }

    private MiStepNativeIconContext(Context base) {
        super(base);
        ApplicationInfo app = base.getApplicationInfo();
        AssetManager assets = (AssetManager) XposedHelpers.newInstance(AssetManager.class);
        boolean ready = false;
        try {
            addApk(assets, app.sourceDir);
            if (app.splitSourceDirs != null)
                for (String split : app.splitSourceDirs) addApk(assets, split);
            configuration = new Configuration(base.getResources().getConfiguration());
            DisplayMetrics metrics = new DisplayMetrics();
            metrics.setTo(base.getResources().getDisplayMetrics());
            // HyperOS also installs MiuiResourcesImpl inside a plain Resources.
            // Bind our private Resources to the platform implementation explicitly.
            // Nothing in the shared SystemUI/plugin resource objects is changed.
            resources = new Resources(assets, metrics, configuration);
            Class<?> implType = XposedHelpers.findClass("android.content.res.ResourcesImpl", null);
            Class<?> adjustmentsType = XposedHelpers.findClass("android.view.DisplayAdjustments", null);
            Object adjustments = XposedHelpers.newInstance(adjustmentsType);
            Object impl = XposedHelpers.newInstance(implType, assets, metrics, configuration, adjustments);
            XposedHelpers.callMethod(resources, "setImpl", impl);
            if (XposedHelpers.getObjectField(resources, "mResourcesImpl").getClass() != implType)
                throw new IllegalStateException("Original resource implementation was replaced");
            theme = resources.newTheme();
            if (app.theme != 0) theme.applyStyle(app.theme, true);
            if (reportedPackages.add(base.getPackageName()))
                XposedBridge.log("[Mi Step] native icons " + base.getPackageName()
                        + " impl=" + impl.getClass().getName());
            ready = true;
        } finally {
            if (!ready) assets.close();
        }
    }

    private static void addApk(AssetManager assets, String path) {
        if (path == null || path.isEmpty()
                || ((Number) XposedHelpers.callMethod(assets, "addAssetPath", path)).intValue() == 0)
            throw new IllegalStateException("Missing original icon APK resources");
    }

    static Drawable drawable(Context base, String... names) {
        Context context = wrap(base);
        Resources resources = context.getResources();
        Resources.Theme iconTheme = resources.newTheme();
        iconTheme.setTo(context.getTheme());
        // Resolve slider vector fills locally; applying this style to the tile
        // context would also overwrite its native enabled/disabled backgrounds.
        if ("miui.systemui.plugin".equals(base.getPackageName())) {
            int style = resources.getIdentifier("ControlCenter", "style", base.getPackageName());
            if (style != 0) iconTheme.applyStyle(style, true);
        }
        for (String name : names) {
            int id = resources.getIdentifier(name, "drawable", base.getPackageName());
            if (id != 0) {
                Drawable drawable = resources.getDrawable(id, iconTheme);
                if (drawable != null) return drawable.mutate();
            }
        }
        return null;
    }

    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return resources.getAssets(); }
    @Override public Resources.Theme getTheme() { return theme; }
    @Override public void setTheme(int resId) { theme.applyStyle(resId, true); }
}
