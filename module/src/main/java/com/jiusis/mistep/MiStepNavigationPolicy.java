package com.jiusis.mistep;

final class MiStepNavigationPolicy {
    private MiStepNavigationPolicy() {}

    static boolean shouldSuspend(String reason) {
        // Recents is also the native freeform entry. Keep the existing workspace
        // while choosing a floating task, as for gesture navigation.
        return "homekey".equals(reason);
    }
}
