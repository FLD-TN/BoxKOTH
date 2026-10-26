package com.dex.boxkoth.display;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.session.SessionManager;
import com.dex.boxkoth.util.ColorFormat;
import com.dex.boxkoth.util.DependencyCheck;
import com.dex.boxkoth.zone.Zone;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import eu.decentsoftware.holograms.api.holograms.enums.EnumFlag;
import eu.decentsoftware.holograms.event.DecentHologramsReloadEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * §8.1: two runtime-only DecentHolograms holograms per zone. zones.yml is the single source of truth; holograms
 * are never saved by DH and are recreated on enable, world load and DH reload.
 * <ul>
 *     <li>{@link Kind#RANKING} ({@code koth_<zone>}, /koth holo): the ranking; while idle (if shown) every rank
 *     is empty. A line for a rank without a clan is replaced by its {@code empty-rows} line.</li>
 *     <li>{@link Kind#INFO} ({@code kothinfo_<zone>}, /koth infoholo): "not running / next match" while idle;
 *     removed while a session runs.</li>
 * </ul>
 * A hologram whose template is empty for the current state does not exist in DH at all.
 * <p>
 * Error boundary: a DecentHolograms or PlaceholderAPI API change (missing method/class) disables only the
 * hologram feature (or the PlaceholderAPI step) with one clear console message; KOTH itself keeps running.
 * Other failures are logged once per hologram and operation until they recover, never every second.
 */
public final class HologramService {

    public enum Kind {
        RANKING("koth_"),
        // Not "koth_<zone>_info": zone names may contain '_', so that could collide with another zone's ranking.
        INFO("kothinfo_");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }
    }

    /** The first %top_<n>_...% token of a ranking line decides which rank the line belongs to. */
    private static final Pattern TOP_TOKEN = Pattern.compile("%top_(\\d{1,2})_");

    private final ClanKothPlugin plugin;
    private final ClanKothExpansion resolver;
    /** Hologram name -> lines last sent to DH. */
    private final Map<String, List<String>> lastLines = new HashMap<>();
    /** "operation:hologram" keys currently failing, so each failure is logged once until it recovers. */
    private final Set<String> failing = new HashSet<>();
    private String dhProblem;
    private boolean papiProblem;
    private boolean probed;

    public HologramService(ClanKothPlugin plugin, ClanKothExpansion resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    public static String hologramName(Kind kind, String zone) {
        return kind.prefix + zone;
    }

    /** Separate listeners: a missing DH event class must not also drop the world-load handler. */
    public void registerListeners(Plugin owner) {
        Bukkit.getPluginManager().registerEvents(new WorldListener(), owner);
        try {
            Bukkit.getPluginManager().registerEvents(new DecentReloadListener(), owner);
        } catch (LinkageError e) {
            plugin.getLogger().warning("DecentHolograms reload event not available (" + e
                    + "); holograms are still recreated by the periodic refresh.");
        }
    }

    /** Null when holograms work; otherwise why the feature is disabled. */
    public String problem() {
        return dhProblem;
    }

    // ------------------------------------------------------------------ error boundary

    private void disableDh(String what, LinkageError e) {
        if (dhProblem != null) return;
        dhProblem = what + " -> " + e;
        lastLines.clear();
        plugin.getLogger().severe("==============================================================");
        plugin.getLogger().severe(" The installed DecentHolograms is NOT compatible with this ClanKoth build.");
        plugin.getLogger().severe(" Failed call : " + what);
        plugin.getLogger().severe(" Error       : " + e);
        plugin.getLogger().severe(" Built for   : DecentHolograms " + DependencyCheck.TESTED_DECENT_HOLOGRAMS);
        plugin.getLogger().severe(" Effect      : KOTH holograms are disabled; sessions, scoreboard and rewards still work.");
        plugin.getLogger().severe("==============================================================");
    }

    private void failed(String op, String name, Exception e) {
        if (failing.add(op + ":" + name)) {
            plugin.getLogger().log(Level.WARNING, "Hologram " + op + " failed for '" + name
                    + "' (not logged again until it succeeds)", e);
        }
    }

    private void succeeded(String op, String name) {
        if (failing.remove(op + ":" + name)) {
            plugin.getLogger().info("Hologram " + op + " for '" + name + "' works again.");
        }
    }

    /** Exercises the DH API once so an incompatible version is reported at startup, not on first use. */
    private void probe() {
        if (probed) return;
        probed = true;
        try {
            DHAPI.getHologram("clankoth_probe");
            EnumFlag.valueOf("DISABLE_PLACEHOLDERS");
            EnumFlag.valueOf("DISABLE_ANIMATIONS");
        } catch (LinkageError e) {
            disableDh("DHAPI.getHologram / EnumFlag", e);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "DecentHolograms API probe failed", e);
        }
    }

    // ------------------------------------------------------------------ operations

    /** Creates holograms for every zone whose world is loaded. */
    public void createAll() {
        probe();
        for (Zone zone : plugin.zones().all()) {
            create(zone);
        }
    }

    /** (Re)creates both holograms of the zone, removing any leftovers with the same names first. */
    public void create(Zone zone) {
        for (Kind kind : Kind.values()) {
            create(zone, kind);
        }
    }

    private void create(Zone zone, Kind kind) {
        if (dhProblem != null) return;
        World world = Bukkit.getWorld(zone.world());
        if (world == null) return;
        String name = hologramName(kind, zone.name());
        try {
            if (DHAPI.getHologram(name) != null) {
                DHAPI.removeHologram(name);
            }
            lastLines.remove(name);
            List<String> lines = build(zone, kind);
            if (lines == null) {
                succeeded("create", name);
                return; // hidden in the current state
            }
            // Lines are fully resolved by us; DH must not expand placeholders or animations inside clan names.
            // Flags go on before any real content is rendered.
            Hologram hologram = DHAPI.createHologram(name, location(zone, world, kind), false, List.of(""));
            hologram.addFlags(EnumFlag.DISABLE_PLACEHOLDERS, EnumFlag.DISABLE_ANIMATIONS);
            DHAPI.setHologramLines(hologram, lines);
            lastLines.put(name, lines);
            succeeded("create", name);
        } catch (LinkageError e) {
            disableDh("DHAPI.createHologram / setHologramLines", e);
        } catch (Exception e) {
            failed("create", name, e);
        }
    }

    /** Updates both holograms for the current state (idle/active): shows, hides or rewrites them as needed. */
    public void refresh(String zoneName) {
        Zone zone = plugin.zones().get(zoneName);
        if (zone == null) return;
        for (Kind kind : Kind.values()) {
            refresh(zone, kind);
        }
    }

    private void refresh(Zone zone, Kind kind) {
        if (dhProblem != null) return;
        if (Bukkit.getWorld(zone.world()) == null) return;
        String name = hologramName(kind, zone.name());
        try {
            Hologram hologram = DHAPI.getHologram(name);
            List<String> lines = build(zone, kind);
            if (lines == null) {
                if (hologram != null) {
                    DHAPI.removeHologram(name);
                }
                lastLines.remove(name);
            } else if (hologram == null) {
                create(zone, kind);
                return;
            } else if (!lines.equals(lastLines.get(name))) {
                DHAPI.setHologramLines(hologram, lines);
                lastLines.put(name, lines);
            }
            succeeded("update", name);
        } catch (LinkageError e) {
            disableDh("DHAPI.getHologram / setHologramLines", e);
        } catch (Exception e) {
            failed("update", name, e);
        }
    }

    /**
     * Periodic task: idle holograms only. Holograms of running zones are refreshed by the capture task in the
     * same tick the session snapshot is published, keeping them in step with the placeholders.
     */
    public void refreshAll() {
        if (dhProblem != null) return;
        for (Zone zone : plugin.zones().all()) {
            if (!plugin.sessions().isRunning(zone.name())) {
                refresh(zone.name());
            }
        }
    }

    /** Applies a changed hologram position (movehere / reset). */
    public void move(Zone zone, Kind kind) {
        if (dhProblem != null) return;
        World world = Bukkit.getWorld(zone.world());
        if (world == null) return;
        String name = hologramName(kind, zone.name());
        try {
            Hologram hologram = DHAPI.getHologram(name);
            if (hologram == null) {
                refresh(zone, kind); // created at the new position if it is visible right now
            } else {
                DHAPI.moveHologram(hologram, location(zone, world, kind));
            }
            succeeded("move", name);
        } catch (LinkageError e) {
            disableDh("DHAPI.moveHologram", e);
        } catch (Exception e) {
            failed("move", name, e);
        }
    }

    public void remove(String zoneName) {
        for (Kind kind : Kind.values()) {
            String name = hologramName(kind, zoneName);
            lastLines.remove(name);
            failing.removeIf(key -> key.endsWith(":" + name));
            if (dhProblem != null) continue;
            try {
                DHAPI.removeHologram(name);
            } catch (LinkageError e) {
                disableDh("DHAPI.removeHologram", e);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not remove hologram " + name, e);
            }
        }
    }

    private Location location(Zone zone, World world, Kind kind) {
        double offsetY = plugin.config().hologramOffsetY;
        double[] p = kind == Kind.RANKING ? zone.hologramPosition(offsetY) : zone.infoHologramPosition(offsetY);
        return new Location(world, p[0], p[1], p[2]);
    }

    private List<String> templates(Zone zone, Kind kind) {
        PluginConfig cfg = plugin.config();
        boolean running = plugin.sessions().isRunning(zone.name());
        return switch (kind) {
            case RANKING -> running || cfg.rankingShowWhenIdle ? cfg.rankingLines : List.of();
            case INFO -> running ? List.of() : cfg.infoLines;
        };
    }

    /**
     * §16.1 order: PlaceholderAPI runs on the template first, then internal values (clan and player names)
     * are substituted, so user-controlled text is never expanded.
     *
     * @return the lines, or null when this hologram is hidden in the current state (empty template)
     */
    private List<String> build(Zone zone, Kind kind) {
        List<String> templates = templates(zone, kind);
        if (templates.isEmpty()) return null;
        Map<Integer, String> emptyRows = plugin.config().rankingEmptyRows;
        SessionManager.View view = plugin.sessions().view(zone.name());
        int ranked = view == null ? 0 : view.top().size();
        List<String> lines = new ArrayList<>(templates.size());
        for (String template : templates) {
            if (kind == Kind.RANKING && !emptyRows.isEmpty()) {
                Matcher m = TOP_TOKEN.matcher(template);
                if (m.find()) {
                    int rank = Integer.parseInt(m.group(1));
                    if (rank > ranked && emptyRows.containsKey(rank)) {
                        template = emptyRows.get(rank);
                    }
                }
            }
            String line = resolver.fill(zone.name(), ColorFormat.normalize(applyPapi(template)));
            // DH treats lines starting with '#' as item/head/entity lines; keep them as text.
            lines.add(line.startsWith("#") ? "&r" + line : line);
        }
        return lines;
    }

    /** PlaceholderAPI on the template; skipped (with one message) if its API changed. */
    private String applyPapi(String template) {
        if (papiProblem || template.indexOf('%') < 0) return template;
        try {
            return PlaceholderAPI.setPlaceholders((OfflinePlayer) null, template);
        } catch (LinkageError e) {
            papiProblem = true;
            plugin.getLogger().severe("PlaceholderAPI.setPlaceholders is not compatible (" + e + "); "
                    + "other plugins' placeholders in hologram lines will not be resolved. Built for PlaceholderAPI "
                    + DependencyCheck.TESTED_PLACEHOLDER_API + ".");
            return template;
        } catch (RuntimeException e) {
            // A third-party expansion threw; keep the raw template for this line.
            return template;
        }
    }

    // ------------------------------------------------------------------ listeners

    private final class WorldListener implements Listener {
        @EventHandler
        public void onWorldLoad(WorldLoadEvent event) {
            String world = event.getWorld().getName();
            for (Zone zone : plugin.zones().all()) {
                if (zone.world().equals(world)) {
                    create(zone);
                }
            }
        }
    }

    /** /dh reload drops runtime holograms; recreate ours once the reload finished (§16.4). */
    private final class DecentReloadListener implements Listener {
        @EventHandler
        public void onDecentReload(DecentHologramsReloadEvent event) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (dhProblem != null) return;
                for (Zone zone : plugin.zones().all()) {
                    for (Kind kind : Kind.values()) {
                        String name = hologramName(kind, zone.name());
                        try {
                            if (DHAPI.getHologram(name) == null) {
                                lastLines.remove(name);
                                refresh(zone, kind);
                            }
                        } catch (LinkageError e) {
                            disableDh("DHAPI.getHologram", e);
                            return;
                        } catch (Exception e) {
                            failed("recreate", name, e);
                        }
                    }
                }
            }, 1L);
        }
    }
}
