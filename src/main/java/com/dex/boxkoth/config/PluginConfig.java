package com.dex.boxkoth.config;

import com.dex.boxkoth.zone.Zone;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/** Immutable, validated view of config.yml. Invalid values fall back to defaults with a warning (§16.5). */
public final class PluginConfig {

    public enum SelectionMode {RANDOM, LONGEST_INSIDE}

    /** How a capture by a new clan is shown to every online player. */
    public enum ControllerDisplay {CHAT, ACTIONBAR, NONE}

    /** One schedule entry; each (entry, time) pair is scheduled independently (U2). */
    public record Schedule(String zone, Set<DayOfWeek> days, List<LocalTime> times, Integer durationSeconds) {
    }

    /** Upper bound on announcement thresholds (reminder interval too small for its window). */
    static final int MAX_ANNOUNCEMENTS = 500;

    public static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String FILE = "config.yml";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    public final boolean debug;
    public final int defaultDurationSeconds;
    public final SelectionMode selectionMode;
    public final boolean allowVehicles;
    public final ControllerDisplay controllerDisplay;
    /** The current controller glows (white outline). */
    public final boolean controllerGlow;

    public final boolean startTitleEnabled;
    public final int titleFadeInTicks;
    public final int titleStayTicks;
    public final int titleFadeOutTicks;

    /** Played to everyone near the zone each time someone takes control. Null = disabled. */
    public final Sound captureSound;

    public final int playerScheduleLimit;

    public final int arenaRadius;
    public final int hysteresis;
    public final int scoreboardCheckInterval;

    public final double hologramOffsetY;
    public final int hologramUpdateInterval;
    /** Ranking hologram (moved by /koth holo movehere), same lines while idle and running. */
    public final List<String> rankingLines;
    public final boolean rankingShowWhenIdle;
    /** Rank (1-5) -> line used instead of a line with %top_<rank>_...% while that rank has no clan. */
    public final Map<Integer, String> rankingEmptyRows;
    /** Info hologram ("not running / next match"); always hidden while a session runs. Empty = never shown. */
    public final List<String> infoLines;

    public final boolean bossBarEnabled;
    public final String bossBarTitle;
    public final BossBar.Color bossBarColor;
    public final BossBar.Overlay bossBarOverlay;

    public final String emptyController;
    public final String emptyTop;
    public final String emptyTime;
    public final String emptyNext;

    public final boolean schedulerEnabled;
    public final ZoneId timezone;
    public final List<Schedule> schedules;
    public final boolean reminderEnabled;
    public final int reminderStartBeforeSeconds;
    public final int reminderIntervalSeconds;
    /** All announcement thresholds (explicit list + repeating reminders), largest first, no duplicates. */
    public final List<Integer> announceBeforeSeconds;

    /** Clan actions forbidden while any KOTH runs. */
    public static final List<String> CLAN_LOCK_ACTIONS = List.of("create", "disband", "leave", "join", "kick", "invite", "transfer");
    public final boolean clanLockEnabled;
    /** Lower-case names from {@link #CLAN_LOCK_ACTIONS}. */
    public final Set<String> clanLockActions;

    private PluginConfig(YamlConfiguration c, Logger log) {
        Validator v = new Validator(log);
        debug = c.getBoolean("debug", false);
        defaultDurationSeconds = v.intAtLeast(c, "default-duration-seconds", 300, 1);
        selectionMode = v.enumValue(c, "selection-mode", SelectionMode.class, SelectionMode.RANDOM);
        allowVehicles = c.getBoolean("allow-vehicles", false);
        // 'BROADCAST' was the earlier name of the server-wide chat mode.
        controllerDisplay = "BROADCAST".equalsIgnoreCase(c.getString("controller-change-display", "").trim())
                ? ControllerDisplay.CHAT
                : v.enumValue(c, "controller-change-display", ControllerDisplay.class, ControllerDisplay.CHAT);
        controllerGlow = c.getBoolean("controller-glow", true);

        startTitleEnabled = c.getBoolean("start-title.enabled", true);
        titleFadeInTicks = v.intAtLeast(c, "start-title.fade-in-ticks", 10, 0);
        titleStayTicks = v.intAtLeast(c, "start-title.stay-ticks", 60, 1);
        titleFadeOutTicks = v.intAtLeast(c, "start-title.fade-out-ticks", 10, 0);

        captureSound = parseSound(c, "capture-sound", log);

        playerScheduleLimit = v.intAtLeast(c, "player-schedule-limit", 5, 1);

        arenaRadius = v.intAtLeast(c, "scoreboard.arena-radius", 30, 0);
        hysteresis = v.intAtLeast(c, "scoreboard.hysteresis", 3, 0);
        scoreboardCheckInterval = v.intAtLeast(c, "scoreboard.check-interval-ticks", 10, 1);

        hologramOffsetY = c.getDouble("hologram.offset-y", 2.0);
        // Line building (PlaceholderAPI, colours) happens at most once per second per hologram (U4.8).
        hologramUpdateInterval = v.intAtLeast(c, "hologram.update-interval-ticks", 20, 20);
        // Older configs: 'hologram.(ranking.)active-lines' was the ranking, 'hologram.idle-lines' the info text.
        rankingLines = lines(c, "hologram.ranking.lines", "hologram.ranking.active-lines", "hologram.active-lines");
        rankingShowWhenIdle = c.getBoolean("hologram.ranking.show-when-idle", true);
        rankingEmptyRows = parseEmptyRows(c, log);
        infoLines = lines(c, "hologram.info.lines", "hologram.idle-lines");

        bossBarEnabled = c.getBoolean("bossbar.enabled", true);
        bossBarTitle = c.getString("bossbar.title", "&6&lKOTH &fCòn lại: &a%time_left% &8| &e#1 &f%top_1_clan%");
        bossBarColor = v.enumValue(c, "bossbar.color", BossBar.Color.class, BossBar.Color.YELLOW);
        bossBarOverlay = v.enumValue(c, "bossbar.overlay", BossBar.Overlay.class, BossBar.Overlay.PROGRESS);

        emptyController = c.getString("empty.controller", "Không có ai");
        emptyTop = c.getString("empty.top", "---");
        emptyTime = c.getString("empty.time", "--:--");
        emptyNext = c.getString("empty.next", "Chưa lên lịch");

        schedulerEnabled = c.getBoolean("scheduler.enabled", true);
        timezone = parseZone(c.getString("scheduler.timezone", "Asia/Ho_Chi_Minh"), log);
        schedules = parseSchedules(c, log);
        reminderEnabled = c.getBoolean("scheduler.reminder.enabled", true);
        reminderStartBeforeSeconds = v.intAtLeast(c, "scheduler.reminder.start-before-seconds", 1800, 1);
        reminderIntervalSeconds = v.intAtLeast(c, "scheduler.reminder.interval-seconds", 300, 10);
        announceBeforeSeconds = mergeAnnouncements(parseAnnounce(c, log), log);

        clanLockEnabled = c.getBoolean("clan-lock.enabled", true);
        clanLockActions = parseClanLockActions(c, log);
    }

    /** Missing list (older config.yml): every action is locked. */
    private static Set<String> parseClanLockActions(YamlConfiguration c, Logger log) {
        if (!c.contains("clan-lock.actions")) return Set.copyOf(CLAN_LOCK_ACTIONS);
        Set<String> result = new TreeSet<>();
        for (String raw : c.getStringList("clan-lock.actions")) {
            String action = raw.trim().toLowerCase(Locale.ROOT);
            if (CLAN_LOCK_ACTIONS.contains(action)) {
                result.add(action);
            } else {
                warn(log, "clan-lock.actions", raw, "nothing for it (valid: " + CLAN_LOCK_ACTIONS + ")");
            }
        }
        return Set.copyOf(result);
    }

    /** Explicit thresholds plus start-before, start-before - interval, ... (> 0), largest first. */
    private List<Integer> mergeAnnouncements(List<Integer> explicit, Logger log) {
        TreeSet<Integer> all = new TreeSet<>(explicit);
        if (reminderEnabled) {
            for (int s = reminderStartBeforeSeconds; s > 0 && all.size() < MAX_ANNOUNCEMENTS; s -= reminderIntervalSeconds) {
                all.add(s);
            }
            if (all.size() >= MAX_ANNOUNCEMENTS) {
                warn(log, "scheduler.reminder", "start-before " + reminderStartBeforeSeconds + " / interval "
                        + reminderIntervalSeconds, "only the first " + MAX_ANNOUNCEMENTS + " reminders");
            }
        }
        return List.copyOf(all.descendingSet());
    }

    /** The user's value at {@code path}, else the first legacy key the user still has, else the bundled default. */
    private static List<String> lines(YamlConfiguration c, String path, String... legacy) {
        if (!c.contains(path, true)) {
            for (String old : legacy) {
                if (c.contains(old, true)) return List.copyOf(c.getStringList(old));
            }
        }
        return List.copyOf(c.getStringList(path));
    }

    /** {@code hologram.ranking.empty-rows}: rank (1-5) -> line. */
    private static Map<Integer, String> parseEmptyRows(YamlConfiguration c, Logger log) {
        ConfigurationSection section = c.getConfigurationSection("hologram.ranking.empty-rows");
        if (section == null) return Map.of();
        Map<Integer, String> result = new HashMap<>();
        for (String key : section.getKeys(false)) {
            int rank;
            try {
                rank = Integer.parseInt(key.trim());
            } catch (NumberFormatException e) {
                rank = -1;
            }
            if (rank < 1 || rank > 5 || !section.isString(key)) {
                warn(log, "hologram.ranking.empty-rows." + key, section.get(key), "(row ignored, keys are 1-5)");
                continue;
            }
            result.put(rank, section.getString(key));
        }
        return Map.copyOf(result);
    }

    /** {@code path.sound} ('' or 'none' = off), {@code path.volume}, {@code path.pitch}. */
    private static Sound parseSound(YamlConfiguration c, String path, Logger log) {
        String name = c.getString(path + ".sound", "entity.ender_dragon.growl");
        if (name == null || name.isBlank() || name.equalsIgnoreCase("none")) return null;
        Key key;
        try {
            key = Key.key(name.trim().toLowerCase(Locale.ROOT));
        } catch (InvalidKeyException e) {
            warn(log, path + ".sound", name, "no sound");
            return null;
        }
        double volume = c.getDouble(path + ".volume", 1.0);
        double pitch = c.getDouble(path + ".pitch", 1.0);
        if (volume < 0 || volume > 10) {
            warn(log, path + ".volume", volume, 1.0);
            volume = 1.0;
        }
        if (pitch < 0.5 || pitch > 2.0) {
            warn(log, path + ".pitch", pitch, 1.0);
            pitch = 1.0;
        }
        return Sound.sound(key, Sound.Source.MASTER, (float) volume, (float) pitch);
    }

    public static PluginConfig parse(YamlConfiguration yaml, Logger log) {
        return new PluginConfig(yaml, log);
    }

    public int arenaRadius(Zone zone) {
        return zone.arenaRadius() != null ? zone.arenaRadius() : arenaRadius;
    }

    public int durationSeconds(Zone zone) {
        return zone.durationSeconds() != null ? zone.durationSeconds() : defaultDurationSeconds;
    }

    private static ZoneId parseZone(String id, Logger log) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException | NullPointerException e) {
            warn(log, "scheduler.timezone", id, DEFAULT_TIMEZONE);
            return DEFAULT_TIMEZONE;
        }
    }

    private static List<Schedule> parseSchedules(YamlConfiguration c, Logger log) {
        List<Schedule> result = new ArrayList<>();
        List<Map<?, ?>> raw = c.getMapList("scheduler.schedules");
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> m = raw.get(i);
            String path = "scheduler.schedules[" + i + "]";
            Object zoneObj = m.get("zone");
            String zone = zoneObj == null ? null : zoneObj.toString();
            if (!Zone.isValidName(zone)) {
                warn(log, path + ".zone", zone, "(schedule skipped)");
                continue;
            }
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            boolean daysOk = true;
            Object daysObj = m.get("days");
            if (daysObj instanceof List<?> list && !list.isEmpty()) {
                for (Object d : list) {
                    String day = String.valueOf(d).trim().toUpperCase(Locale.ROOT);
                    if (day.equals("ALL")) {
                        days.addAll(EnumSet.allOf(DayOfWeek.class));
                        continue;
                    }
                    try {
                        days.add(DayOfWeek.valueOf(day));
                    } catch (IllegalArgumentException e) {
                        warn(log, path + ".days", d, "(schedule skipped)");
                        daysOk = false;
                    }
                }
            } else {
                warn(log, path + ".days", daysObj, "(schedule skipped)");
                daysOk = false;
            }
            if (!daysOk) continue;
            List<LocalTime> times = new ArrayList<>();
            Object timesObj = m.get("times");
            if (timesObj instanceof List<?> list) {
                for (Object t : list) {
                    try {
                        LocalTime time = LocalTime.parse(String.valueOf(t).trim(), TIME);
                        if (!times.contains(time)) times.add(time);
                    } catch (DateTimeParseException e) {
                        warn(log, path + ".times (zone " + zone + ")", t, "(time ignored, expected HH:mm:ss)");
                    }
                }
            } else {
                warn(log, path + ".times (zone " + zone + ")", timesObj, "(schedule skipped, expected a list of 'HH:mm:ss')");
            }
            if (times.isEmpty()) continue;
            Integer duration = null;
            Object durObj = m.get("duration-seconds");
            if (durObj != null) {
                if (durObj instanceof Number n && n.intValue() > 0) {
                    duration = n.intValue();
                } else {
                    warn(log, path + ".duration-seconds", durObj, "zone/default duration");
                }
            }
            result.add(new Schedule(zone, Set.copyOf(days), List.copyOf(times), duration));
        }
        return List.copyOf(result);
    }

    private static List<Integer> parseAnnounce(YamlConfiguration c, Logger log) {
        if (!c.isList("scheduler.announce-before-seconds")) {
            return List.of(60, 10);
        }
        List<Integer> result = new ArrayList<>();
        for (Object o : c.getList("scheduler.announce-before-seconds", List.of())) {
            if (o instanceof Number n && n.intValue() > 0) {
                result.add(n.intValue());
            } else {
                warn(log, "scheduler.announce-before-seconds", o, "(entry ignored)");
            }
        }
        return List.copyOf(result);
    }

    static void warn(Logger log, String key, Object bad, Object fallback) {
        log.warning("[" + FILE + "] invalid value for '" + key + "': '" + bad + "'. Using " + fallback + ".");
    }

    /** Small helper for typed reads with range checks. */
    private record Validator(Logger log) {
        int intAtLeast(ConfigurationSection c, String key, int def, int min) {
            if (!c.contains(key)) return def;
            if (!c.isInt(key) || c.getInt(key) < min) {
                warn(log, key, c.get(key), def);
                return def;
            }
            return c.getInt(key);
        }

        <E extends Enum<E>> E enumValue(ConfigurationSection c, String key, Class<E> type, E def) {
            String raw = c.getString(key);
            if (raw == null) return def;
            try {
                return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warn(log, key, raw, def);
                return def;
            }
        }
    }
}
