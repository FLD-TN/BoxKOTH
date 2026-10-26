package com.dex.boxkoth.listener;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.clan.ClanProvider;
import com.dex.boxkoth.config.Messages;
import com.dex.boxkoth.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Receives Clans events through {@link ClanProvider.EventSink} (so this class needs no Clans API types) and
 * moves them onto the main thread before touching session state (§6.2, §16.2).
 */
public final class ClanEventListener implements ClanProvider.EventSink {

    public static final String BYPASS_PERMISSION = "clankoth.bypass.clanlock";

    private final ClanKothPlugin plugin;

    public ClanEventListener(ClanKothPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onMemberJoined(UUID player, String clanId) {
        onMain(() -> plugin.sessions().recordJoin(player, clanId));
    }

    @Override
    public void onClanDisbanded(String clanId) {
        // Clans fires the event before removing the clan: check one tick later that it is really gone.
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.sessions().onClanDisbanded(clanId), 1L);
        }
    }

    /** Clan lock (config.yml clan-lock): no membership changes while any KOTH runs; admins bypass. */
    @Override
    public boolean blockClanAction(Player player, String action) {
        PluginConfig cfg = plugin.config();
        if (!cfg.clanLockEnabled || !cfg.clanLockActions.contains(action)) return false;
        if (!plugin.sessions().anyRunning() || player.hasPermission(BYPASS_PERMISSION)) return false;
        Messages msg = plugin.messages();
        msg.send(player, "clan-locked", "action", msg.raw("clan-lock-actions." + action));
        plugin.debug("Clan action '" + action + "' of " + player.getName() + " blocked: KOTH running");
        return true;
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
