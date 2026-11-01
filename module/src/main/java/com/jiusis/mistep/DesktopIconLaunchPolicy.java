package com.jiusis.mistep;

final class DesktopIconLaunchPolicy {
    private static final String LAUNCHER = "com.miui.home";
    private static final String ACTION_MAIN = "android.intent.action.MAIN";

    private DesktopIconLaunchPolicy() {}

    static int launchUser(String method, Class<?>[] types, Object[] args, int callerUser) {
        if ("startActivity".equals(method)) return callerUser;
        if (!("startActivityAsUser".equals(method) || "startActivityAsUserWithFeature".equals(method))
                || types == null || args == null || types.length != args.length) return -1;
        // These ATMS overloads end in userId, optionally followed by a boolean.
        for (int i = types.length - 1; i >= 0; i--) if (types[i] == int.class)
            return args[i] instanceof Integer user && user >= 0 ? user : -1;
        return -1;
    }

    static int uniqueSideSlot(String packageName, int user, String[] sidePackages,
            int[] sideUsers, boolean[] occupied) {
        if (user < 0 || sidePackages == null || sideUsers == null || occupied == null
                || sidePackages.length != sideUsers.length || sidePackages.length != occupied.length)
            return -1;
        boolean[] matchingUser = new boolean[occupied.length];
        for (int i = 0; i < matchingUser.length; i++) matchingUser[i] = occupied[i] && sideUsers[i] == user;
        return uniqueSideSlot(packageName, sidePackages, matchingUser);
    }

    static String packageForLauncherEntry(String callerPackage, String action,
            boolean launcherCategory, String componentPackage) {
        if (!LAUNCHER.equals(callerPackage) || !ACTION_MAIN.equals(action)
                || !launcherCategory || componentPackage == null || componentPackage.isEmpty()
                || LAUNCHER.equals(componentPackage)) return null;
        return componentPackage;
    }

    /** Returns the side-slot index, relative to sidePackages, only for one exact match. */
    static int uniqueSideSlot(String packageName, String[] sidePackages, boolean[] occupied) {
        if (packageName == null || sidePackages == null || occupied == null
                || sidePackages.length != occupied.length) return -1;
        int match = -1;
        for (int i = 0; i < sidePackages.length; i++) {
            if (!occupied[i] || !packageName.equals(sidePackages[i])) continue;
            if (match >= 0) return -1;
            match = i;
        }
        return match;
    }
}
