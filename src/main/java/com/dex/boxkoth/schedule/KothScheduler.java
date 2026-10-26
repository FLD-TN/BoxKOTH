package com.dex.boxkoth.schedule;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.session.SessionManager;
import com.dex.boxkoth.util.TimeFormat;
import com.dex.boxkoth.zone.Zone;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * U2 scheduler. Every (schedule entry, time) pair is a {@link Slot} with its own {@code nextFire}.
 * All times come from {@code ZonedDateTime.now(configured zone)}, never the host timezone. A single global
 * task runs every second on the main thread.
 */
public final class KothScheduler implements Runnable {

    /** One (entry, time) pair. Pure logic, no Bukkit, so it can be unit-tested. */
    public static final class Slot {
        private final PluginConfig.Schedule schedule;
        private final LocalTime time;
        private final Set<Integer> announced = new HashSet<>();
        private ZonedDateTime nextFire;

        public Slot(PluginConfig.Schedule schedule, LocalTime time) {
            this.schedule = schedule;
            this.time = time;
        }

        /**
         * Sets nextFire strictly after {@code now} (no catch-up) and resets announcements; thresholds that are
         * already passed at this moment are marked as announced so they never fire late.
         */
        public void recompute(ZonedDateTime now, List<Integer> announceBefore) {
            nextFire = KothScheduler.nextFire(schedule.days(), time, now);
            announced.clear();
            if (nextFire == null) return;
            for (int a : announceBefore) {
                if (!now.isBefore(nextFire.minusSeconds(a))) {
                    announced.add(a);
                }
            }
        }

        /** {@code now >= nextFire}, compared as instants, so a lag spike over the exact second still fires. */
        public boolean isDue(ZonedDateTime now) {
            return nextFire != null && !now.toInstant().isBefore(nextFire.toInstant());
        }

        /** Thresholds that became due now; each is returned once per nextFire. */
        public List<Integer> dueAnnouncements(ZonedDateTime now, List<Integer> announceBefore) {
            if (nextFire == null) return List.of();
            List<Integer> due = new ArrayList<>(1);
            for (int a : announceBefore) {
                if (!now.isBefore(nextFire.minusSeconds(a)) && announced.add(a)) {
                    due.add(a);
                }
            }
            return due;
        }

        public PluginConfig.Schedule schedule() {
            return schedule;
        }

        public LocalTime time() {
            return time;
        }

        public ZonedDateTime nextFire() {
            return nextFire;
        }
    }

    /** Earliest instant strictly after {@code now} on one of {@code days} at {@code time}, in now's zone. */
    public static ZonedDateTime nextFire(Set<DayOfWeek> days, LocalTime time, ZonedDateTime now) {
        if (days.isEmpty()) return null;
        ZoneId zone = now.getZone();
        LocalDate today = now.toLocalDate();
        for (int d = 0; d <= 8; d++) {
            LocalDate date = today.plusDays(d);
            if (!days.contains(date.getDayOfWeek())) continue;
            ZonedDateTime candidate = ZonedDateTime.of(date, time, zone);
            if (candidate.isAfter(now)) {
                return candidate;
            }
        }
        return null;
    }

    private final ClanKothPlugin plugin;
    private List<Slot> slots = List.of();
    private volatile Map<String, ZonedDateTime> nextByZone = Map.of();

    public KothScheduler(ClanKothPlugin plugin) {
        this.plugin = plugin;
    }

    /** On enable and on /koth reload: rebuild all slots from config, next fire strictly after now. */
    public void reload() {
        PluginConfig cfg = plugin.config();
        List<Slot> fresh = new ArrayList<>();
        if (cfg.schedulerEnabled) {
            ZonedDateTime now = ZonedDateTime.now(cfg.timezone);
            for (PluginConfig.Schedule schedule : cfg.schedules) {
                if (!plugin.zones().exists(schedule.zone())) {
                    plugin.getLogger().warning("[config.yml] schedule for zone '" + schedule.zone()
                            + "' refers to a zone that does not exist; it will not start or be announced.");
                }
                for (LocalTime time : schedule.times()) {
                    Slot slot = new Slot(schedule, time);
                    slot.recompute(now, cfg.announceBeforeSeconds);
                    fresh.add(slot);
                }
            }
        }
        slots = Collections.unmodifiableList(fresh);
        publish();
    }

    @Override
    public void run() {
        if (slots.isEmpty()) return;
        PluginConfig cfg = plugin.config();
        ZonedDateTime now = ZonedDateTime.now(cfg.timezone);
        boolean changed = false;
        for (Slot slot : slots) {
            if (slot.isDue(now)) {
                trigger(slot.schedule());
                slot.recompute(now, cfg.announceBeforeSeconds);
                changed = true;
                continue;
            }
            for (int before : slot.dueAnnouncements(now, cfg.announceBeforeSeconds)) {
                if (!plugin.zones().exists(slot.schedule().zone())) continue; // would never start
                plugin.messages().broadcast("schedule-announce",
                        "zone", slot.schedule().zone(),
                        "seconds", String.valueOf(before),
                        "minutes", String.valueOf(before / 60),
                        "time", TimeFormat.formatSeconds(before),
                        "human", plugin.notifier().humanTime(before));
            }
        }
        if (changed) {
            publish();
        }
    }

    private void publish() {
        Map<String, ZonedDateTime> next = new HashMap<>();
        for (Slot slot : slots) {
            if (slot.nextFire() != null) {
                next.merge(slot.schedule().zone(), slot.nextFire(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        nextByZone = Map.copyOf(next);
    }

    private void trigger(PluginConfig.Schedule schedule) {
        Zone zone = plugin.zones().get(schedule.zone());
        if (zone == null) {
            plugin.getLogger().warning("Scheduled KOTH skipped: zone '" + schedule.zone() + "' does not exist.");
            return;
        }
        SessionManager.StartResult result = plugin.sessions().start(zone, schedule.durationSeconds());
        switch (result) {
            case STARTED -> plugin.getLogger().info("Scheduled KOTH started on '" + zone.name() + "'.");
            case ALREADY_RUNNING -> plugin.getLogger().warning("Scheduled KOTH skipped: '" + zone.name() + "' is already running.");
            case CLANS_INCOMPATIBLE -> plugin.getLogger().warning("Scheduled KOTH skipped: Clans API is incompatible.");
            case CLANS_NOT_READY -> plugin.getLogger().warning("Scheduled KOTH skipped: Clans API is not ready.");
            case WORLD_NOT_LOADED -> plugin.getLogger().warning("Scheduled KOTH skipped: world '" + zone.world() + "' is not loaded.");
        }
    }

    /** Main thread: all slots, for /koth schedule. */
    public List<Slot> slots() {
        return slots;
    }

    /** Thread-safe. Earliest next start for the zone, or null. */
    public ZonedDateTime next(String zone) {
        return nextByZone.get(zone);
    }
}
