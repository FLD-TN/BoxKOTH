package com.dex.boxkoth.config;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigTest {

    private static PluginConfig parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return PluginConfig.parse(c, Logger.getAnonymousLogger());
    }

    @Test
    void invalidTimesAreSkipped() throws Exception {
        PluginConfig cfg = parse("""
                scheduler:
                  schedules:
                    - zone: campus
                      times: ['12:00:00', '25:00:00', '19:00', '7:00:00', '19:00:00', '12:00:00']
                      days: [ALL]
                    - zone: other
                      times: ['19:00']
                      days: [ALL]
                """);
        assertEquals(1, cfg.schedules.size());
        assertEquals(List.of(LocalTime.of(12, 0), LocalTime.of(19, 0)), cfg.schedules.get(0).times());
        assertEquals(7, cfg.schedules.get(0).days().size());
    }

    @Test
    void daysAndDuration() throws Exception {
        PluginConfig cfg = parse("""
                scheduler:
                  schedules:
                    - zone: campus
                      times: ['20:30:15']
                      days: [MONDAY, monday, FRIDAY]
                      duration-seconds: 120
                """);
        PluginConfig.Schedule s = cfg.schedules.get(0);
        assertEquals(2, s.days().size());
        assertTrue(s.days().contains(DayOfWeek.FRIDAY));
        assertEquals(120, s.durationSeconds());
        assertEquals(LocalTime.of(20, 30, 15), s.times().get(0));
    }

    @Test
    void remindersMergeWithAnnouncements() throws Exception {
        PluginConfig cfg = parse("""
                scheduler:
                  reminder:
                    enabled: true
                    start-before-seconds: 1200
                    interval-seconds: 300
                  announce-before-seconds: [300, 60, 10]
                """);
        assertEquals(List.of(1200, 900, 600, 300, 60, 10), cfg.announceBeforeSeconds);
    }

    @Test
    void remindersCanBeDisabled() throws Exception {
        PluginConfig cfg = parse("""
                scheduler:
                  reminder:
                    enabled: false
                  announce-before-seconds: [60]
                """);
        assertEquals(List.of(60), cfg.announceBeforeSeconds);
    }

    @Test
    void captureSoundDefaultsToEnderDragon() throws Exception {
        PluginConfig cfg = parse("debug: false");
        assertEquals("minecraft:entity.ender_dragon.growl", cfg.captureSound.name().asString());
        assertTrue(cfg.startTitleEnabled);
        assertEquals(80, cfg.titleFadeInTicks + cfg.titleStayTicks + cfg.titleFadeOutTicks);
    }

    @Test
    void captureSoundValidation() throws Exception {
        PluginConfig custom = parse("""
                capture-sound:
                  sound: 'entity.player.levelup'
                  volume: 50
                  pitch: 1.5
                """);
        assertEquals("minecraft:entity.player.levelup", custom.captureSound.name().asString());
        assertEquals(1.0f, custom.captureSound.volume());   // out of range -> default
        assertEquals(1.5f, custom.captureSound.pitch());
        assertNull(parse("capture-sound:\n  sound: 'Bad Name!'").captureSound);   // invalid -> disabled
        assertNull(parse("capture-sound:\n  sound: ''").captureSound);            // '' -> disabled
    }

    @Test
    void legacyHologramKeysStillWork() throws Exception {
        PluginConfig cfg = parse("""
                hologram:
                  idle-lines: ['idle']
                  active-lines: ['active']
                """);
        assertEquals(List.of("active"), cfg.rankingLines);
        assertEquals(List.of("idle"), cfg.infoLines);
    }

    @Test
    void emptyRowsKeyedByRank() throws Exception {
        PluginConfig cfg = parse("""
                hologram:
                  ranking:
                    empty-rows:
                      1: 'one'
                      '5': 'five'
                      9: 'ignored'
                      x: 'ignored'
                """);
        assertEquals(Map.of(1, "one", 5, "five"), cfg.rankingEmptyRows);
    }

    @Test
    void captureMessageDisplay() throws Exception {
        assertEquals(PluginConfig.ControllerDisplay.CHAT, parse("debug: false").controllerDisplay);
        assertEquals(PluginConfig.ControllerDisplay.ACTIONBAR, parse("controller-change-display: actionbar").controllerDisplay);
        assertEquals(PluginConfig.ControllerDisplay.CHAT, parse("controller-change-display: BROADCAST").controllerDisplay);
        assertEquals(PluginConfig.ControllerDisplay.CHAT, parse("controller-change-display: WRONG").controllerDisplay);
    }

    @Test
    void bossBarSettings() throws Exception {
        PluginConfig cfg = parse("""
                bossbar:
                  color: red
                  overlay: WRONG
                """);
        assertEquals(BossBar.Color.RED, cfg.bossBarColor);
        assertEquals(BossBar.Overlay.PROGRESS, cfg.bossBarOverlay);
    }

    @Test
    void badValuesFallBack() throws Exception {
        PluginConfig cfg = parse("""
                default-duration-seconds: -5
                selection-mode: NOPE
                hologram:
                  update-interval-ticks: 5
                scheduler:
                  timezone: 'Mars/Olympus'
                """);
        assertEquals(300, cfg.defaultDurationSeconds);
        assertEquals(PluginConfig.SelectionMode.RANDOM, cfg.selectionMode);
        assertEquals(20, cfg.hologramUpdateInterval);
        assertEquals(PluginConfig.DEFAULT_TIMEZONE, cfg.timezone);
        assertTrue(cfg.schedulerEnabled);
    }

    @Test
    void clanLockDefaultsToEveryActionForOlderConfigs() throws Exception {
        PluginConfig cfg = parse("debug: false");
        assertTrue(cfg.clanLockEnabled);
        assertEquals(Set.copyOf(PluginConfig.CLAN_LOCK_ACTIONS), cfg.clanLockActions);
    }

    @Test
    void clanLockActionsAreValidated() throws Exception {
        PluginConfig cfg = parse("""
                clan-lock:
                  enabled: false
                  actions: [CREATE, ' kick ', fly]
                """);
        assertFalse(cfg.clanLockEnabled);
        assertEquals(Set.of("create", "kick"), cfg.clanLockActions);
    }
}
