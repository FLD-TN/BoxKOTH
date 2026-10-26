package com.dex.boxkoth.display;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.zone.Zone;
import com.dex.boxkoth.zone.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * §8.2: which zone each player is near, with hysteresis (§3.4). One global task on the main thread; the result
 * is published as an immutable map through a volatile field so async placeholder requests never touch Bukkit
 * (§16.2). Only UUIDs and zone names are kept (U4.2).
 */
public final class NearbyTracker implements Runnable {

    private final ClanKothPlugin plugin;
    private final ZoneManager zones;

    private volatile Map<UUID, String> nearZone = Map.of();

    public NearbyTracker(ClanKothPlugin plugin, ZoneManager zones) {
        this.plugin = plugin;
        this.zones = zones;
    }

    @Override
    public void run() {
        PluginConfig cfg = plugin.config();
        Map<UUID, String> previous = nearZone;
        Map<UUID, String> near = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            String zone = choose(p.getWorld().getName(), p.getX(), p.getY(), p.getZ(), previous.get(uuid), cfg);
            if (zone != null) {
                near.put(uuid, zone);
            }
        }
        nearZone = Map.copyOf(near);
    }

    private String choose(String world, double x, double y, double z, String previous, PluginConfig cfg) {
        // Keep the previous zone until the player is beyond radius + hysteresis.
        if (previous != null) {
            Zone zone = zones.get(previous);
            if (zone != null && zone.world().equals(world)) {
                double outer = cfg.arenaRadius(zone) + cfg.hysteresis;
                if (zone.distanceSquared(x, y, z) <= outer * outer) {
                    return previous;
                }
            }
        }
        String best = null;
        double bestDist = Double.MAX_VALUE;
        for (Zone zone : zones.all()) {
            if (!zone.world().equals(world)) continue;
            double r = cfg.arenaRadius(zone);
            double d = zone.distanceSquared(x, y, z);
            if (d <= r * r && d < bestDist) {
                best = zone.name();
                bestDist = d;
            }
        }
        return best;
    }

    /** Thread-safe. */
    public String nearZone(UUID player) {
        return nearZone.get(player);
    }

    public void remove(UUID player) {
        if (nearZone.containsKey(player)) {
            Map<UUID, String> copy = new HashMap<>(nearZone);
            copy.remove(player);
            nearZone = Map.copyOf(copy);
        }
    }
}
