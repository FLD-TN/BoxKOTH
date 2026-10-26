package com.dex.boxkoth.reward;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LeaderRecipientTest {

    private final UUID leader = UUID.randomUUID();
    private final UUID vice = UUID.randomUUID();
    private static final Predicate<UUID> NOBODY_EXCLUDED = u -> false;

    private static Predicate<UUID> online(UUID... players) {
        return Set.of(players)::contains;
    }

    @Test
    void onlineLeaderAlwaysReceivesEvenIfViceIsOnline() {
        assertEquals(leader, RewardService.leaderRecipient(leader, vice, true, online(leader, vice), NOBODY_EXCLUDED));
    }

    @Test
    void offlineLeaderOnlineViceStandsIn() {
        assertEquals(vice, RewardService.leaderRecipient(leader, vice, true, online(vice), NOBODY_EXCLUDED));
    }

    @Test
    void bothOfflineNobody() {
        assertNull(RewardService.leaderRecipient(leader, vice, true, online(), NOBODY_EXCLUDED));
    }

    @Test
    void noViceNobody() {
        assertNull(RewardService.leaderRecipient(leader, null, true, online(), NOBODY_EXCLUDED));
    }

    @Test
    void disabledNeverGivesToVice() {
        assertNull(RewardService.leaderRecipient(leader, vice, false, online(vice), NOBODY_EXCLUDED));
    }

    @Test
    void viceWhoJoinedDuringSessionDoesNotStandIn() {
        assertNull(RewardService.leaderRecipient(leader, vice, true, online(vice), vice::equals));
    }

    @Test
    void viceEqualToLeaderIsNeverASecondRecipient() {
        assertNull(RewardService.leaderRecipient(leader, leader, true, online(), NOBODY_EXCLUDED));
    }

    @Test
    void noLeaderNobody() {
        assertNull(RewardService.leaderRecipient(null, vice, true, online(vice), NOBODY_EXCLUDED));
    }
}
