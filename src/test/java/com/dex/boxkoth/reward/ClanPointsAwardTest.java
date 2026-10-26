package com.dex.boxkoth.reward;

import com.dex.boxkoth.clan.ClanProvider;
import com.dex.boxkoth.session.Ranking;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClanPointsAwardTest {

    /** Records awardPoints calls; can fail on one clan. */
    private static final class RecordingProvider implements ClanProvider {
        final List<String> calls = new ArrayList<>();
        String failOn;

        @Override
        public void awardPoints(String clanId, String source) {
            calls.add(clanId + ":" + source);
            if (clanId.equals(failOn)) throw new IllegalStateException("boom");
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public String problem() {
            return null;
        }

        @Override
        public String getClanId(UUID player) {
            return null;
        }

        @Override
        public void refresh(UUID player) {
        }

        @Override
        public void forget(UUID player) {
        }

        @Override
        public String getClanName(String clanId) {
            return null;
        }

        @Override
        public String getClanTag(String clanId) {
            return null;
        }

        @Override
        public UUID getLeader(String clanId) {
            return null;
        }

        @Override
        public Set<UUID> getMembers(String clanId) {
            return Set.of();
        }

        @Override
        public boolean clanExists(String clanId) {
            return true;
        }

        @Override
        public String describe() {
            return "test";
        }

        @Override
        public void start(Plugin plugin, EventSink sink) {
        }

        @Override
        public void stop() {
        }
    }

    private static List<Ranking.Entry<String>> ranking(String... clans) {
        List<Ranking.Entry<String>> out = new ArrayList<>();
        long ticks = 1000;
        for (String c : clans) out.add(new Ranking.Entry<>(c, ticks--, 0));
        return out;
    }

    @Test
    void onlyTheTopThreeGetPoints() {
        RecordingProvider p = new RecordingProvider();
        RewardService.awardClanPoints(ranking("a", "b", "c", "d", "e"), p, Logger.getAnonymousLogger());
        assertEquals(List.of("a:koth-top1", "b:koth-top2", "c:koth-top3"), p.calls);
    }

    @Test
    void fewerClansThanThree() {
        RecordingProvider p = new RecordingProvider();
        RewardService.awardClanPoints(ranking("a", "b"), p, Logger.getAnonymousLogger());
        assertEquals(List.of("a:koth-top1", "b:koth-top2"), p.calls);
        p.calls.clear();
        RewardService.awardClanPoints(ranking(), p, Logger.getAnonymousLogger());
        assertEquals(List.of(), p.calls);
    }

    @Test
    void oneFailureDoesNotStopTheOthers() {
        RecordingProvider p = new RecordingProvider();
        p.failOn = "b";
        RewardService.awardClanPoints(ranking("a", "b", "c"), p, Logger.getAnonymousLogger());
        assertEquals(List.of("a:koth-top1", "b:koth-top2", "c:koth-top3"), p.calls);
    }

    @Test
    void defaultProviderDoesNothing() {
        // the interface default is a no-op for providers that have no clan points
        ClanProvider plain = new ClanProvider() {
            public boolean isReady() { return true; }
            public String problem() { return null; }
            public String getClanId(UUID player) { return null; }
            public void refresh(UUID player) { }
            public void forget(UUID player) { }
            public String getClanName(String clanId) { return null; }
            public String getClanTag(String clanId) { return null; }
            public UUID getLeader(String clanId) { return null; }
            public Set<UUID> getMembers(String clanId) { return Set.of(); }
            public boolean clanExists(String clanId) { return true; }
            public String describe() { return ""; }
            public void start(Plugin plugin, EventSink sink) { }
            public void stop() { }
        };
        RewardService.awardClanPoints(ranking("a", "b", "c"), plain, Logger.getAnonymousLogger());
    }
}
