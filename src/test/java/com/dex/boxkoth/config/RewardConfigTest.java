package com.dex.boxkoth.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardConfigTest {

    private static RewardConfig parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return RewardConfig.parse(c, Logger.getAnonymousLogger());
    }

    @Test
    void clanPointsDefaultsToTrueWhenMissing() throws Exception {
        assertTrue(parse("stack-rewards: true\n").clanPoints);
    }

    @Test
    void clanPointsCanBeTurnedOff() throws Exception {
        assertFalse(parse("clan-points: false\n").clanPoints);
        assertTrue(parse("clan-points: true\n").clanPoints);
    }

    @Test
    void bundledRewardsFileEnablesClanPoints() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        try (InputStreamReader r = new InputStreamReader(
                RewardConfigTest.class.getClassLoader().getResourceAsStream("rewards.yml"), StandardCharsets.UTF_8)) {
            c.load(r);
        }
        RewardConfig cfg = RewardConfig.parse(c, Logger.getAnonymousLogger());
        assertTrue(cfg.clanPoints);
        assertTrue(cfg.stackRewards, "the other keys are unchanged");
    }
}
