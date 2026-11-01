package com.jiusis.mistep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/** Pinned component order followed by the existing usage order, without duplicates. */
final class QuickAppOrder {
    private QuickAppOrder() {}

    static List<String> decode(String saved) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (saved != null) for (String key : saved.split("\n")) {
            if (!key.isEmpty()) keys.add(key);
        }
        return new ArrayList<>(keys);
    }

    static String encode(Iterable<String> keys) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String key : keys) if (key != null && !key.isEmpty() && !key.contains("\n")) unique.add(key);
        return String.join("\n", unique);
    }

    static <T> List<T> arrange(List<T> available, Iterable<String> pinned, Function<T, String> key) {
        LinkedHashMap<String, T> remaining = new LinkedHashMap<>();
        for (T app : available) remaining.putIfAbsent(key.apply(app), app);
        List<T> result = new ArrayList<>();
        for (String id : pinned) {
            T app = remaining.remove(id);
            if (app != null) result.add(app);
        }
        result.addAll(remaining.values());
        return result;
    }
}
