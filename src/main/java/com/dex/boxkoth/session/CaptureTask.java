package com.dex.boxkoth.session;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.clan.ClanProvider;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.zone.Zone;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The per-tick capture loop (§4.3). Runs on the main thread every tick. Clan ids come from the provider's
 * in-memory cache; only a player inside a zone whom the cache lists as clanless is re-checked in BoxClans (an
 * in-memory lookup, no database access, U4.1). The snapshot for placeholders and
 * holograms (including the top-5 sort) is rebuilt once per second (U4.3).
 */
public final class CaptureTask implements Runnable {

    private static final int MAX_CONSECUTIVE_FAILURES = 20;

    private final ClanKothPlugin plugin;
    private final SessionManager sessions;
    private final ClanProvider clans;
    /** Debug only: last logged eligibility decision per player, to avoid per-tick spam. Cleared on quit. */
    private final Map<UUID, String> lastDecision = new HashMap<>();

    public CaptureTask(ClanKothPlugin plugin, SessionManager sessions, ClanProvider clans) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.clans = clans;
    }

    @Override
    public void run() {
        if (!sessions.anyRunning()) {
            return;
        }
        if (clans.problem() != null) {
            // Clans became incompatible mid-session: results and rewards cannot be trusted.
            sessions.endAll(SessionManager.EndReason.ERROR);
            return;
        }
        PluginConfig cfg = plugin.config();
        for (KothSession s : sessions.running()) {
            if (!s.isRunning()) continue;
            try {
                World world = Bukkit.getWorld(s.zone().world());
                if (world == null) {
                    plugin.getLogger().warning("World of KOTH '" + s.zone().name() + "' is not loaded; ending without rewards.");
                    sessions.end(s, SessionManager.EndReason.WORLD_UNLOADED);
                    continue;
                }
                UUID before = s.controller();
                boolean changed = s.tick(eligible(world, s.zone(), cfg));
                if (changed) {
                    sessions.onControllerChanged(s, before);
                }
                s.recordSuccess();
                if (s.isExpired()) {
                    sessions.end(s, SessionManager.EndReason.EXPIRED);
                } else if (s.secondChanged()) {
                    sessions.refreshDisplays(s);
                }
            } catch (Throwable t) {
                if (s.markFailureLogged()) {
                    plugin.getLogger().log(Level.SEVERE, "Error in KOTH tick for '" + s.zone().name() + "'", t);
                } else {
                    plugin.getLogger().warning("Error in KOTH tick for '" + s.zone().name() + "': " + t);
                }
                if (s.recordFailure(MAX_CONSECUTIVE_FAILURES)) {
                    plugin.getLogger().severe("KOTH '" + s.zone().name() + "' failed " + MAX_CONSECUTIVE_FAILURES
                            + " ticks in a row; ending it without rewards.");
                    sessions.end(s, SessionManager.EndReason.ERROR);
                }
            }
        }
    }

    /** Eligible players inside the zone -> clan id (§4.2). Bounding box first, then the other checks. */
    private Map<UUID, String> eligible(World world, Zone zone, PluginConfig cfg) {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (Player p : world.getPlayers()) {
            if (!zone.contains(p.getX(), p.getY(), p.getZ())) {
                continue;
            }
            UUID uuid = p.getUniqueId();
            String reason = ineligibleReason(p, cfg);
            String clan = null;
            if (reason == null) {
                clan = clans.resolveClanId(uuid);
                if (clan == null) reason = "no clan";
            }
            if (cfg.debug) {
                logDecision(p, zone, reason == null ? "eligible (clan " + clan + ")" : "ineligible: " + reason);
            }
            if (reason == null) {
                result.put(uuid, clan);
            }
        }
        return result;
    }

    private static String ineligibleReason(Player p, PluginConfig cfg) {
        if (!p.isOnline()) return "offline";
        if (p.isDead()) return "dead";
        GameMode mode = p.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return "game mode " + mode;
        if (isVanished(p)) return "vanished";
        if (!cfg.allowVehicles && p.getVehicle() != null) return "in vehicle";
        return null;
    }

    private static boolean isVanished(Player p) {
        if (!p.hasMetadata("vanished")) return false;
        for (MetadataValue value : p.getMetadata("vanished")) {
            if (value.asBoolean()) return true;
        }
        return false;
    }

    private void logDecision(Player p, Zone zone, String decision) {
        String key = zone.name() + ": " + decision;
        if (!Objects.equals(lastDecision.put(p.getUniqueId(), key), key)) {
            plugin.debug(p.getName() + " in " + key);
        }
    }

    public void forget(UUID player) {
        lastDecision.remove(player);
    }
}
