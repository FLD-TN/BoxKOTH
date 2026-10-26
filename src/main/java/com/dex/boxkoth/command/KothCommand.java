package com.dex.boxkoth.command;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.config.Messages;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.display.ClanKothExpansion;
import com.dex.boxkoth.display.HologramService;
import com.dex.boxkoth.schedule.KothScheduler;
import com.dex.boxkoth.session.KothSession;
import com.dex.boxkoth.session.SessionManager;
import com.dex.boxkoth.util.TimeFormat;
import com.dex.boxkoth.zone.SelectionManager;
import com.dex.boxkoth.zone.Zone;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /koth sub-commands. Players (clankoth.player, default true): {@code /koth}, {@code /koth time}, {@code /koth help}.
 * Staff tiers per U3. Works from the console except where the sender's location is needed.
 */
public final class KothCommand implements TabExecutor {

    public static final String PERM_ADMIN = "clankoth.admin";
    public static final String PERM_START = "clankoth.start";
    public static final String PERM_PLAYER = "clankoth.player";
    private static final int MIN_SECONDS = 10;
    private static final int MAX_SECONDS = 86400;
    private static final int MAX_RADIUS = 1000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Sub-command -> required permission, in help order. */
    private static final Map<String, String> SUBCOMMANDS = orderedMap(
            "", PERM_PLAYER, "time", PERM_PLAYER, "help", PERM_PLAYER,
            "start", PERM_START, "stop", PERM_START, "schedule", PERM_START, "list", PERM_START, "info", PERM_START,
            "pos1", PERM_ADMIN, "pos2", PERM_ADMIN, "create", PERM_ADMIN, "delete", PERM_ADMIN,
            "holo", PERM_ADMIN, "infoholo", PERM_ADMIN, "set", PERM_ADMIN, "reload", PERM_ADMIN);

    private final ClanKothPlugin plugin;

    public KothCommand(ClanKothPlugin plugin) {
        this.plugin = plugin;
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        Messages msg = plugin.messages();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String required = SUBCOMMANDS.get(sub);
        if (required == null) {
            usage(sender);
            return true;
        }
        if (!sender.hasPermission(required)) {
            msg.send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "", "time" -> playerSchedule(sender);
            case "help" -> usage(sender);
            case "start" -> start(sender, args);
            case "stop" -> stop(sender, args);
            case "schedule" -> schedule(sender);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "pos1" -> pos(sender, 1);
            case "pos2" -> pos(sender, 2);
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "holo" -> holo(sender, args, HologramService.Kind.RANKING);
            case "infoholo" -> holo(sender, args, HologramService.Kind.INFO);
            case "set" -> set(sender, args);
            case "reload" -> reload(sender);
            default -> usage(sender);
        }
        return true;
    }

    /** Help lists only what the sender may use. */
    private void usage(CommandSender sender) {
        plugin.messages().sendList(sender, "usage-player");
        if (sender.hasPermission(PERM_START)) {
            plugin.messages().sendList(sender, "usage");
        }
        if (sender.hasPermission(PERM_ADMIN)) {
            plugin.messages().sendList(sender, "usage-admin");
        }
    }

    // ------------------------------------------------------------------ clankoth.player

    /** /koth and /koth time: running sessions, then the next scheduled starts. */
    private void playerSchedule(CommandSender sender) {
        Messages msg = plugin.messages();
        PluginConfig cfg = plugin.config();
        ZonedDateTime now = ZonedDateTime.now(cfg.timezone);
        msg.sendList(sender, "player-schedule-header", "timezone", cfg.timezone.getId());
        boolean any = false;
        for (Zone zone : plugin.zones().all()) {
            SessionManager.View view = plugin.sessions().view(zone.name());
            if (view == null) continue;
            any = true;
            long seconds = (view.remainingTicks() + TimeFormat.TICKS_PER_SECOND - 1) / TimeFormat.TICKS_PER_SECOND;
            msg.sendList(sender, "player-schedule-running", "zone", zone.name(),
                    "time_left", TimeFormat.mmssCeil(view.remainingTicks()),
                    "time_left_human", plugin.notifier().humanTime(seconds));
        }
        List<KothScheduler.Slot> upcoming = new ArrayList<>();
        for (KothScheduler.Slot slot : plugin.scheduler().slots()) {
            if (slot.nextFire() != null && plugin.zones().exists(slot.schedule().zone())) {
                upcoming.add(slot);
            }
        }
        upcoming.sort((a, b) -> a.nextFire().compareTo(b.nextFire()));
        for (KothScheduler.Slot slot : upcoming.subList(0, Math.min(cfg.playerScheduleLimit, upcoming.size()))) {
            any = true;
            long seconds = (Math.max(0L, Duration.between(now, slot.nextFire()).toMillis()) + 999) / 1000;
            msg.sendList(sender, "player-schedule-entry", "zone", slot.schedule().zone(),
                    "next", ClanKothExpansion.formatNext(slot.nextFire(), cfg),
                    "remaining", TimeFormat.hhmmss(seconds),
                    "remaining_human", plugin.notifier().humanTime(seconds));
        }
        if (!any) {
            msg.sendList(sender, "player-schedule-empty");
        }
        msg.sendList(sender, "player-schedule-footer");
    }

    // ------------------------------------------------------------------ clankoth.start

    private void start(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        Zone zone = requireZone(sender, args, "usage-start");
        if (zone == null) return;
        Integer seconds = null;
        if (args.length >= 3) {
            try {
                seconds = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                seconds = -1;
            }
            if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
                msg.send(sender, "usage-start");
                return;
            }
        }
        SessionManager.StartResult result = plugin.sessions().start(zone, seconds);
        switch (result) {
            case STARTED -> msg.send(sender, "session-started", "zone", zone.name());
            case ALREADY_RUNNING -> msg.send(sender, "session-already-running", "zone", zone.name());
            case CLANS_INCOMPATIBLE -> msg.send(sender, "clans-incompatible");
            case CLANS_NOT_READY -> msg.send(sender, "clans-not-ready");
            case WORLD_NOT_LOADED -> msg.send(sender, "world-not-loaded", "world", zone.world());
        }
    }

    private void stop(CommandSender sender, String[] args) {
        Zone zone = requireZone(sender, args, "usage-stop");
        if (zone == null) return;
        boolean rewards = !(args.length >= 3 && args[2].equalsIgnoreCase("--no-rewards"));
        if (plugin.sessions().stop(zone.name(), rewards)) {
            plugin.messages().send(sender, rewards ? "session-stopped" : "session-stopped-no-rewards", "zone", zone.name());
        } else {
            plugin.messages().send(sender, "session-not-running", "zone", zone.name());
        }
    }

    private void schedule(CommandSender sender) {
        Messages msg = plugin.messages();
        PluginConfig cfg = plugin.config();
        if (!cfg.schedulerEnabled) {
            msg.send(sender, "schedule-disabled");
            return;
        }
        List<KothScheduler.Slot> slots = plugin.scheduler().slots();
        if (slots.isEmpty()) {
            msg.send(sender, "schedule-empty");
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(cfg.timezone);
        msg.send(sender, "schedule-header", "timezone", cfg.timezone.getId(),
                "now", ClanKothExpansion.NEXT_FORMAT.format(now));
        List<KothScheduler.Slot> sorted = new ArrayList<>(slots);
        sorted.sort((a, b) -> a.nextFire() == null ? 1 : b.nextFire() == null ? -1 : a.nextFire().compareTo(b.nextFire()));
        for (KothScheduler.Slot slot : sorted) {
            ZonedDateTime next = slot.nextFire();
            String remaining = next == null ? "-"
                    : TimeFormat.hhmmss((Math.max(0L, Duration.between(now, next).toMillis()) + 999) / 1000);
            msg.sendList(sender, "schedule-entry",
                    "zone", slot.schedule().zone(),
                    "days", days(slot),
                    "time", TIME_FORMAT.format(slot.time()),
                    "next", next == null ? "-" : ClanKothExpansion.NEXT_FORMAT.format(next.withZoneSameInstant(cfg.timezone)),
                    "remaining", remaining);
        }
    }

    private static String days(KothScheduler.Slot slot) {
        if (slot.schedule().days().size() == 7) return "ALL";
        return slot.schedule().days().stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
    }

    private void list(CommandSender sender) {
        Messages msg = plugin.messages();
        if (plugin.zones().all().isEmpty()) {
            msg.send(sender, "list-empty");
            return;
        }
        msg.send(sender, "list-header", "count", String.valueOf(plugin.zones().all().size()));
        for (Zone zone : plugin.zones().all()) {
            boolean running = plugin.sessions().isRunning(zone.name());
            msg.sendList(sender, "list-entry", "zone", zone.name(), "world", zone.world(),
                    "state", msg.raw(running ? "state-running" : "state-idle"));
        }
    }

    private void info(CommandSender sender, String[] args) {
        Zone zone = requireZone(sender, args, "usage-info");
        if (zone == null) return;
        PluginConfig cfg = plugin.config();
        KothSession s = plugin.sessions().get(zone.name());
        double[] holo = zone.hologramPosition(cfg.hologramOffsetY);
        double[] infoHolo = zone.infoHologramPosition(cfg.hologramOffsetY);
        ZonedDateTime next = plugin.scheduler().next(zone.name());
        Messages msg = plugin.messages();
        msg.sendList(sender, "info",
                "zone", zone.name(),
                "world", zone.world(),
                "min", zone.minX() + ", " + zone.minY() + ", " + zone.minZ(),
                "max", zone.maxX() + ", " + zone.maxY() + ", " + zone.maxZ(),
                "radius", String.valueOf(cfg.arenaRadius(zone)),
                "duration", TimeFormat.formatSeconds(cfg.durationSeconds(zone)),
                "hologram", String.format(Locale.ROOT, "%.2f, %.2f, %.2f", holo[0], holo[1], holo[2])
                        + (zone.customHologram() == null ? " " + msg.raw("hologram-default") : ""),
                "info_hologram", String.format(Locale.ROOT, "%.2f, %.2f, %.2f", infoHolo[0], infoHolo[1], infoHolo[2])
                        + (zone.customInfoHologram() == null ? " " + msg.raw("hologram-default") : ""),
                "state", s == null ? msg.raw("state-idle")
                        : msg.raw("state-running") + " (" + TimeFormat.mmssCeil(s.remainingTicks()) + ")",
                "next", next == null ? cfg.emptyNext
                        : ClanKothExpansion.NEXT_FORMAT.format(next.withZoneSameInstant(cfg.timezone)));
    }

    // ------------------------------------------------------------------ clankoth.admin

    private void pos(CommandSender sender, int index) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return;
        }
        SelectionManager.Corner c = plugin.selections().set(player.getUniqueId(), index,
                player.getWorld().getName(), player.getX(), player.getY(), player.getZ());
        plugin.messages().send(sender, "pos-set", "pos", String.valueOf(index), "world", c.world(),
                "x", String.valueOf(c.x()), "y", String.valueOf(c.y()), "z", String.valueOf(c.z()));
    }

    private void create(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (!(sender instanceof Player player)) {
            // The zone comes from the sender's pos1/pos2 selection, which only a player can make.
            msg.send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            msg.send(sender, "usage-create");
            return;
        }
        String name = args[1];
        if (zonesLocked(sender)) return;
        if (!Zone.isValidName(name)) {
            msg.send(sender, "invalid-name", "zone", name);
            return;
        }
        if (plugin.zones().exists(name)) {
            msg.send(sender, "zone-exists", "zone", name);
            return;
        }
        SelectionManager.Corner a = plugin.selections().get(player.getUniqueId(), 1);
        SelectionManager.Corner b = plugin.selections().get(player.getUniqueId(), 2);
        if (a == null || b == null) {
            msg.send(sender, "pos-missing");
            return;
        }
        if (!a.world().equals(b.world())) {
            msg.send(sender, "pos-different-world");
            return;
        }
        Zone zone = Zone.of(name, a.world(), a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
        plugin.zones().put(zone);
        plugin.holograms().create(zone);
        msg.send(sender, "zone-created", "zone", name,
                "min", zone.minX() + ", " + zone.minY() + ", " + zone.minZ(),
                "max", zone.maxX() + ", " + zone.maxY() + ", " + zone.maxZ());
    }

    private void delete(CommandSender sender, String[] args) {
        Zone zone = requireZone(sender, args, "usage-delete");
        if (zone == null || zonesLocked(sender)) return;
        if (plugin.sessions().isRunning(zone.name())) {
            plugin.messages().send(sender, "zone-running", "zone", zone.name());
            return;
        }
        plugin.holograms().remove(zone.name());
        plugin.zones().remove(zone.name());
        plugin.messages().send(sender, "zone-deleted", "zone", zone.name());
    }

    /** /koth holo (ranking) and /koth infoholo (idle info) share the same movehere / reset actions. */
    private void holo(CommandSender sender, String[] args, HologramService.Kind kind) {
        Messages msg = plugin.messages();
        boolean info = kind == HologramService.Kind.INFO;
        String usage = info ? "usage-infoholo" : "usage-holo";
        if (args.length < 3) {
            msg.send(sender, usage);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (zonesLocked(sender)) return;
        Zone zone = plugin.zones().get(args[2].toLowerCase(Locale.ROOT));
        if (zone == null) {
            msg.send(sender, "zone-not-found", "zone", args[2]);
            return;
        }
        switch (action) {
            case "movehere" -> {
                if (!(sender instanceof Player player)) {
                    msg.send(sender, "player-only");
                    return;
                }
                Location loc = player.getLocation();
                if (!loc.getWorld().getName().equals(zone.world())) {
                    msg.send(sender, "holo-wrong-world", "world", zone.world());
                    return;
                }
                double[] position = {loc.getX(), loc.getY(), loc.getZ()};
                Zone updated = info ? zone.withInfoHologram(position) : zone.withHologram(position);
                plugin.zones().put(updated);
                plugin.holograms().move(updated, kind);
                msg.send(sender, info ? "infoholo-moved" : "holo-moved", "zone", zone.name());
            }
            case "reset" -> {
                Zone updated = info ? zone.withInfoHologram(null) : zone.withHologram(null);
                plugin.zones().put(updated);
                plugin.holograms().move(updated, kind);
                msg.send(sender, info ? "infoholo-reset" : "holo-reset", "zone", zone.name());
            }
            default -> msg.send(sender, usage);
        }
    }

    /** /koth set <zone> radius|duration <value|default> */
    private void set(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 4) {
            msg.send(sender, "usage-set");
            return;
        }
        Zone zone = requireZone(sender, args, "usage-set");
        if (zone == null || zonesLocked(sender)) return;
        String field = args[2].toLowerCase(Locale.ROOT);
        String raw = args[3].toLowerCase(Locale.ROOT);
        boolean reset = raw.equals("default");
        Integer value = null;
        if (!reset) {
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                msg.send(sender, "usage-set");
                return;
            }
        }
        Zone updated;
        switch (field) {
            case "radius" -> {
                if (value != null && (value < 0 || value > MAX_RADIUS)) {
                    msg.send(sender, "usage-set");
                    return;
                }
                updated = zone.withArenaRadius(value);
            }
            case "duration" -> {
                if (value != null && (value < MIN_SECONDS || value > MAX_SECONDS)) {
                    msg.send(sender, "usage-set");
                    return;
                }
                updated = zone.withDurationSeconds(value);
            }
            default -> {
                msg.send(sender, "usage-set");
                return;
            }
        }
        plugin.zones().put(updated);
        PluginConfig cfg = plugin.config();
        String shown = field.equals("radius")
                ? String.valueOf(cfg.arenaRadius(updated))
                : TimeFormat.formatSeconds(cfg.durationSeconds(updated));
        msg.send(sender, reset ? "set-reset" : "set-done", "zone", zone.name(), "field", field, "value", shown);
        if (field.equals("duration") && plugin.sessions().isRunning(zone.name())) {
            msg.send(sender, "set-next-session");
        }
    }

    private boolean zonesLocked(CommandSender sender) {
        if (plugin.zones().isLocked()) {
            plugin.messages().send(sender, "zones-locked");
            return true;
        }
        return false;
    }

    private void reload(CommandSender sender) {
        Messages msg = plugin.messages();
        if (plugin.sessions().anyRunning()) {
            msg.send(sender, "reload-refused");
            return;
        }
        String error = plugin.reloadAll();
        if (error == null) {
            plugin.messages().send(sender, "reload-success");
        } else {
            msg.send(sender, "reload-failed", "error", error);
        }
    }

    private Zone requireZone(CommandSender sender, String[] args, String usageKey) {
        if (args.length < 2) {
            plugin.messages().send(sender, usageKey);
            return null;
        }
        Zone zone = plugin.zones().get(args[1].toLowerCase(Locale.ROOT));
        if (zone == null) {
            plugin.messages().send(sender, "zone-not-found", "zone", args[1]);
        }
        return zone;
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            List<String> allowed = new ArrayList<>();
            for (Map.Entry<String, String> e : SUBCOMMANDS.entrySet()) {
                if (!e.getKey().isEmpty() && sender.hasPermission(e.getValue())) allowed.add(e.getKey());
            }
            return filter(allowed, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String required = SUBCOMMANDS.get(sub);
        if (required == null || !sender.hasPermission(required)) return List.of();
        if (args.length == 2) {
            return switch (sub) {
                case "delete", "info", "start", "stop", "set" -> filter(zoneNames(), args[1]);
                case "holo", "infoholo" -> filter(List.of("movehere", "reset"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "holo", "infoholo" -> filter(zoneNames(), args[2]);
                case "stop" -> filter(List.of("--no-rewards"), args[2]);
                case "set" -> filter(List.of("radius", "duration"), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("set")) {
            return filter(List.of("default"), args[3]);
        }
        return List.of();
    }

    private List<String> zoneNames() {
        List<String> names = new ArrayList<>();
        for (Zone zone : plugin.zones().all()) names.add(zone.name());
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(p)) result.add(o);
        }
        return result;
    }
}
