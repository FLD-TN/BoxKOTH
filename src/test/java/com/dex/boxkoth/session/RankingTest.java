package com.dex.boxkoth.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingTest {

    @Test
    void ordersByTicksDescending() {
        List<Ranking.Entry<String>> r = Ranking.order(
                Map.of("a", 100L, "b", 300L, "c", 200L),
                Map.of("a", 1L, "b", 2L, "c", 3L));
        assertEquals(List.of("b", "c", "a"), r.stream().map(Ranking.Entry::key).toList());
    }

    @Test
    void tieBreakEarlierLastIncrementWins() {
        List<Ranking.Entry<String>> r = Ranking.order(
                Map.of("late", 200L, "early", 200L),
                Map.of("late", 950L, "early", 400L));
        assertEquals("early", r.get(0).key());
        assertEquals("late", r.get(1).key());
    }

    @Test
    void zeroTicksAreNotRanked() {
        List<Ranking.Entry<String>> r = Ranking.order(Map.of("a", 0L, "b", 5L), Map.of("a", 0L, "b", 1L));
        assertEquals(1, r.size());
        assertEquals("b", r.get(0).key());
    }

    @Test
    void emptyInput() {
        assertTrue(Ranking.order(Map.<String, Long>of(), Map.of()).isEmpty());
    }

    @Test
    void filterRemovesAndRanksMoveUp() {
        List<Ranking.Entry<String>> r = Ranking.order(
                Map.of("a", 300L, "b", 200L, "c", 100L),
                Map.of("a", 1L, "b", 2L, "c", 3L),
                k -> !k.equals("a"));
        assertEquals(List.of("b", "c"), r.stream().map(Ranking.Entry::key).toList());
    }
}
