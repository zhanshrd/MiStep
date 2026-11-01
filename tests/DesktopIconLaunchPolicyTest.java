package com.jiusis.mistep;

public final class DesktopIconLaunchPolicyTest {
    public static void main(String[] args) {
        expect("com.example.app", DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.miui.home", "android.intent.action.MAIN", true, "com.example.app"));
        expect(null, DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.example.app", "android.intent.action.MAIN", true, "com.example.app"));
        expect(null, DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.miui.home", "android.intent.action.VIEW", true, "com.example.app"));
        expect(null, DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.miui.home", "android.intent.action.MAIN", false, "com.example.app"));
        expect(null, DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.miui.home", "android.intent.action.MAIN", true, "com.miui.home"));

        index(1, DesktopIconLaunchPolicy.uniqueSideSlot("com.example.app",
                new String[]{"com.other", "com.example.app", null},
                new boolean[]{true, true, false}));
        index(-1, DesktopIconLaunchPolicy.uniqueSideSlot("com.missing",
                new String[]{"com.other", "com.example.app"}, new boolean[]{true, true}));
        index(-1, DesktopIconLaunchPolicy.uniqueSideSlot("com.example.app",
                new String[]{"com.example.app", "com.example.app"}, new boolean[]{true, true}));
        index(-1, DesktopIconLaunchPolicy.uniqueSideSlot("com.example.app",
                new String[]{"com.example.app"}, new boolean[]{false}));
        index(0, DesktopIconLaunchPolicy.uniqueSideSlot("app", 0,
                new String[]{"app", "app"}, new int[]{0, 999}, new boolean[]{true, true}));
        index(1, DesktopIconLaunchPolicy.uniqueSideSlot("app", 999,
                new String[]{"app", "app"}, new int[]{0, 999}, new boolean[]{true, true}));
        index(-1, DesktopIconLaunchPolicy.uniqueSideSlot("app", 0,
                new String[]{"app"}, new int[]{999}, new boolean[]{true}));
        index(-1, DesktopIconLaunchPolicy.uniqueSideSlot("app", 999,
                new String[]{"app", "app"}, new int[]{999, 999}, new boolean[]{true, true}));
        index(999, DesktopIconLaunchPolicy.launchUser("startActivityAsUser",
                new Class<?>[]{Object.class,int.class,int.class,boolean.class},
                new Object[]{null,0,999,true}, 0));
        index(0, DesktopIconLaunchPolicy.launchUser("startActivity", null, null, 0));
        index(-1, DesktopIconLaunchPolicy.launchUser("startActivityAsUser",
                new Class<?>[]{int.class}, new Object[]{-2}, 0));
        index(-1, DesktopIconLaunchPolicy.launchUser("unknown", null, null, 0));
        System.out.println("DesktopIconLaunchPolicyTest PASS 17");
    }

    private static void expect(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("expected package=" + expected + " actual=" + actual);
    }

    private static void index(int expected, int actual) {
        if (expected != actual)
            throw new AssertionError("expected slot=" + expected + " actual=" + actual);
    }
}
