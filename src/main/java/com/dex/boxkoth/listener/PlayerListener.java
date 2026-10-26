package com.dex.boxkoth.listener;

import com.dex.boxkoth.ClanKothPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * Join: fill the clan cache, show running boss bars, clear a leftover glow.
 * Quit: remove every per-player entry (U4.7).
 */
public final class PlayerListener implements Listener {

    /** Delay before a second clan lookup, in case Clans loads the user's data after the join event. */
    private static final long CLAN_RECHECK_TICKS = 40L;

    private final ClanKothPlugin plugin;

    public PlayerListener(ClanKothPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.clans().refresh(uuid);
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.clans().refresh(uuid), CLAN_RECHECK_TICKS);
        plugin.bossBars().onJoin(event.getPlayer());
        plugin.glow().onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.clans().forget(uuid);
        plugin.tracker().remove(uuid);
        plugin.selections().clear(uuid);
        plugin.bossBars().onQuit(event.getPlayer());
        plugin.glow().onQuit(event.getPlayer());
        plugin.captureTask().forget(uuid);
    }
}
