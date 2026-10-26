package com.dex.boxkoth.session;

import com.dex.boxkoth.config.PluginConfig.SelectionMode;
import com.dex.boxkoth.zone.Zone;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KothSessionTest {

    private static final Zone ZONE = Zone.of("z", "world", 0, 0, 0, 9, 9, 9);
    private final UUID a1 = UUID.randomUUID();
    private final UUID a2 = UUID.randomUUID();
    private final UUID b1 = UUID.randomUUID();

    /** Fake monotonic clock, in milliseconds; tests move it by hand. */
    private final long[] now = {1_000_000L};

    private KothSession session(SelectionMode mode, long seconds) {
        return new KothSession(ZONE, seconds * 1000L, mode, new Random(1), () -> now[0]);
    }

    @SafeVarargs
    private static Map<UUID, String> inside(Map.Entry<UUID, String>... entries) {
        Map<UUID, String> m = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> e : entries) m.put(e.getKey(), e.getValue());
        return m;
    }

    @Test
    void firstToEnterBecomesControllerAndClanAccumulates() {
        KothSession s = session(SelectionMode.RANDOM, 100);
        assertFalse(s.tick(inside()));
        assertTrue(s.tick(inside(Map.entry(a1, "A"))));
        s.tick(inside(Map.entry(a1, "A"), Map.entry(b1, "B")));
        assertEquals(a1, s.controller());
        assertEquals(2, s.clanTicks("A"));
        assertEquals(0, s.clanTicks("B"));
        assertEquals(2, s.playerTicks(a1, "A"));
    }

    @Test
    void emptyZoneHasNoController() {
        KothSession s = session(SelectionMode.RANDOM, 100);
        s.tick(inside());
        assertNull(s.controller());
        assertTrue(s.ranking().isEmpty());
    }

    @Test
    void controllerLeavingIsReplacedSameTick() {
        KothSession s = session(SelectionMode.LONGEST_INSIDE, 100);
        s.tick(inside(Map.entry(a1, "A")));
        s.tick(inside(Map.entry(a1, "A"), Map.entry(b1, "B"), Map.entry(a2, "A")));
        assertTrue(s.tick(inside(Map.entry(b1, "B"), Map.entry(a2, "A"))));
        assertEquals(b1, s.controller()); // longest inside after a1 left
        assertEquals(1, s.clanTicks("B"));
    }

    @Test
    void leavingClanDropsControlAndStopsClanTime() {
        KothSession s = session(SelectionMode.LONGEST_INSIDE, 100);
        s.tick(inside(Map.entry(a1, "A")));
        s.tick(inside(Map.entry(a1, "A")));
        // a1 left clan A -> no clan -> no longer eligible
        s.tick(inside());
        assertNull(s.controller());
        assertEquals(2, s.clanTicks("A"));
    }

    @Test
    void clanSwitchIsRevalidated() {
        KothSession s = session(SelectionMode.LONGEST_INSIDE, 100);
        s.tick(inside(Map.entry(a1, "A")));
        assertTrue(s.tick(inside(Map.entry(a1, "B"))));
        assertEquals("B", s.controllerClan());
        assertEquals(1, s.clanTicks("A"));
        assertEquals(1, s.clanTicks("B"));
        assertEquals(1, s.playerTicks(a1, "A"));
        assertEquals(1, s.playerTicks(a1, "B"));
    }

    @Test
    void returningControllerDoesNotRegainControl() {
        KothSession s = session(SelectionMode.LONGEST_INSIDE, 100);
        s.tick(inside(Map.entry(a1, "A"), Map.entry(b1, "B")));
        s.tick(inside(Map.entry(b1, "B")));
        s.tick(inside(Map.entry(b1, "B"), Map.entry(a1, "A")));
        assertEquals(b1, s.controller());
    }

    @Test
    void expiresAfterRealTimeNotTicks() {
        KothSession s = session(SelectionMode.RANDOM, 3);
        // 100 ticks in no time (e.g. catching up): not over
        for (int i = 0; i < 100; i++) s.tick(inside());
        assertFalse(s.isExpired());
        now[0] += 2_999;
        assertFalse(s.isExpired());
        now[0] += 1;
        assertTrue(s.isExpired());
    }

    @Test
    void lagDoesNotStretchTheSession() {
        // 10 TPS: one tick every 100 ms; a 3 s session still ends after 3 s, i.e. after 30 ticks, not 60
        KothSession s = session(SelectionMode.RANDOM, 3);
        int ticks = 0;
        while (!s.isExpired()) {
            s.tick(inside());
            now[0] += 100;
            ticks++;
        }
        assertEquals(30, ticks);
    }

    @Test
    void remainingTimeFollowsTheClock() {
        KothSession s = session(SelectionMode.RANDOM, 10);
        assertEquals(200, s.durationTicks());
        assertEquals(200, s.remainingTicks());
        now[0] += 1_001;
        assertEquals(180, s.remainingTicks()); // 8 999 ms left, rounded up to 50 ms units
        now[0] += 60_000;
        assertEquals(0, s.remainingTicks());
    }

    @Test
    void secondChangedOncePerRemainingSecond() {
        KothSession s = session(SelectionMode.RANDOM, 10);
        assertTrue(s.secondChanged());   // first call: 10 s
        assertFalse(s.secondChanged());
        now[0] += 400;
        assertFalse(s.secondChanged());  // 9.6 s -> still 10 when rounded up
        now[0] += 600;
        assertTrue(s.secondChanged());   // 9 s
        now[0] += 3_000;
        assertTrue(s.secondChanged());   // lag: jumps straight to 6 s, one refresh
        assertFalse(s.secondChanged());
    }

    @Test
    void endingIsCompareAndSetOnce() {
        KothSession s = session(SelectionMode.RANDOM, 3);
        assertTrue(s.beginEnding());
        assertFalse(s.beginEnding());
    }

    @Test
    void mvpRankingIsPerClan() {
        KothSession s = session(SelectionMode.LONGEST_INSIDE, 100);
        s.tick(inside(Map.entry(a1, "A")));
        s.tick(inside(Map.entry(a2, "A")));
        s.tick(inside(Map.entry(a2, "A")));
        assertEquals(a2, s.playerRanking("A").get(0).key());
        assertEquals(a1, s.playerRanking("A").get(1).key());
        assertTrue(s.playerRanking("B").isEmpty());
    }

    @Test
    void removedClanLeavesRanking() {
        KothSession s = session(SelectionMode.LONGEST_INSIDE, 100);
        s.tick(inside(Map.entry(a1, "A")));
        s.tick(inside(Map.entry(b1, "B")));
        s.removeClan("A");
        assertEquals(1, s.ranking().size());
        assertEquals("B", s.ranking().get(0).key());
    }
}
