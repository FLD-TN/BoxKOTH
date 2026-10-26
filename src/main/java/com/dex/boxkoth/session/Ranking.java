package com.dex.boxkoth.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * §5 ordering. Higher total first; on equal totals the entry whose last increment happened earlier ranks
 * higher, because it reached that total first. Pure, no Bukkit dependencies.
 */
public final class Ranking {

    public record Entry<K>(K key, long ticks, long lastTick) {
    }

    private Ranking() {
    }

    public static <K> List<Entry<K>> order(Map<K, Long> ticks, Map<K, Long> lastTick) {
        return order(ticks, lastTick, k -> true);
    }

    /** Entries with ticks > 0 that pass {@code filter}, best first. */
    public static <K> List<Entry<K>> order(Map<K, Long> ticks, Map<K, Long> lastTick, Predicate<K> filter) {
        List<Entry<K>> list = new ArrayList<>(ticks.size());
        for (Map.Entry<K, Long> e : ticks.entrySet()) {
            long t = e.getValue() == null ? 0L : e.getValue();
            if (t <= 0 || !filter.test(e.getKey())) continue;
            Long last = lastTick.get(e.getKey());
            list.add(new Entry<>(e.getKey(), t, last == null ? Long.MAX_VALUE : last));
        }
        list.sort(comparator());
        return list;
    }

    public static <K> Comparator<Entry<K>> comparator() {
        return Comparator.<Entry<K>>comparingLong(Entry::ticks).reversed()
                .thenComparingLong(Entry::lastTick)
                .thenComparing(e -> String.valueOf(e.key()));
    }
}
