package com.jiusis.mistep;

import java.util.Arrays;
import java.util.List;

public final class QuickAppOrderTest {
    private static int checks;
    private static void equal(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
        checks++;
    }
    public static void main(String[] args) {
        List<String> apps = List.of("recent/A", "often/B", "custom/C", "other/D");
        equal(QuickAppOrder.arrange(apps, List.of("custom/C", "often/B"), s -> s),
                List.of("custom/C", "often/B", "recent/A", "other/D"));
        equal(QuickAppOrder.arrange(apps, List.of("missing/Z", "custom/C", "custom/C"), s -> s),
                List.of("custom/C", "recent/A", "often/B", "other/D"));
        equal(QuickAppOrder.arrange(apps, List.of(), s -> s), apps);
        equal(QuickAppOrder.arrange(List.of("A", "A", "B"), List.of("B"), s -> s), List.of("B", "A"));
        equal(QuickAppOrder.decode(null), List.of());
        equal(QuickAppOrder.decode("A\nB\n\nA\n"), List.of("A", "B"));
        equal(QuickAppOrder.encode(Arrays.asList("A", null, "B", "", "A", "bad\nkey")), "A\nB");
        equal(QuickAppOrder.decode(QuickAppOrder.encode(List.of("custom/C", "often/B"))),
                List.of("custom/C", "often/B"));
        equal(QuickAppOrder.arrange(List.of("other/D", "often/B", "custom/C", "recent/A"),
                QuickAppOrder.decode("custom/C\noften/B"), s -> s),
                List.of("custom/C", "often/B", "other/D", "recent/A"));
        equal(apps, List.of("recent/A", "often/B", "custom/C", "other/D"));
        System.out.println("QuickAppOrder: " + checks + " checks passed");
    }
}
