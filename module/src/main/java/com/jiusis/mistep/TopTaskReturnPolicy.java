package com.jiusis.mistep;

final class TopTaskReturnPolicy {
    static final float CENTER_START_SCALE = .14f;

    static boolean keepUpperTask(boolean mainOccupiedOrUnknown) {
        return mainOccupiedOrUnknown;
    }

    static boolean liveCenterReveal(boolean mainOccupiedOrUnknown,
            boolean originatedFromMain, boolean originalSlotEmpty) {
        return !mainOccupiedOrUnknown && originatedFromMain && originalSlotEmpty;
    }

    static float revealScale(float fraction) {
        float bounded = Math.max(0f, Math.min(1f, fraction));
        return CENTER_START_SCALE + (1f - CENTER_START_SCALE) * bounded;
    }

    static float centeredOffset(int length, float scale) {
        return length * (1f - scale) / 2f;
    }

    private TopTaskReturnPolicy() {}
}
