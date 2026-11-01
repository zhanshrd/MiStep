package com.jiusis.mistep;

public final class TopTaskReturnPolicyTest {
    public static void main(String[] args) {
        check(TopTaskReturnPolicy.keepUpperTask(true));
        check(!TopTaskReturnPolicy.keepUpperTask(false));
        check(!TopTaskReturnPolicy.liveCenterReveal(true, true, true));
        check(!TopTaskReturnPolicy.liveCenterReveal(false, false, true));
        check(!TopTaskReturnPolicy.liveCenterReveal(false, true, false));
        check(TopTaskReturnPolicy.liveCenterReveal(false, true, true));
        check(Math.abs(TopTaskReturnPolicy.revealScale(0f)
                - TopTaskReturnPolicy.CENTER_START_SCALE) < .0001f);
        check(TopTaskReturnPolicy.revealScale(1f) == 1f);
        check(TopTaskReturnPolicy.revealScale(-1f)
                == TopTaskReturnPolicy.CENTER_START_SCALE);
        check(TopTaskReturnPolicy.revealScale(2f) == 1f);
        for (float scale : new float[]{.14f, .5f, .9f, 1f}) {
            float center = TopTaskReturnPolicy.centeredOffset(1080, scale)
                    + 1080 * scale / 2f;
            check(Math.abs(center - 540f) < .001f);
        }
        System.out.println("TopTaskReturnPolicy: 14 checks passed");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("top return policy changed");
    }
}
