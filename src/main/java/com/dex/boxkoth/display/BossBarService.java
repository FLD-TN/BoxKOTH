package com.dex.boxkoth.display;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.config.Messages;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.session.KothSession;
import com.dex.boxkoth.util.TimeFormat;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One server-wide boss bar per running session (config: bossbar), shown to every online player including those
 * who join mid-session. Updated once per second from the session snapshot, like the holograms. Main thread only.
 */
public final class BossBarService {

    private final ClanKothPlugin plugin;
    private final ClanKothExpansion resolver;
    private final Map<String, BossBar> bars = new LinkedHashMap<>();
    /** Last resolved title per zone, so the name component is only rebuilt when the text changed. */
    private final Map<String, String> lastTitle = new HashMap<>();

    public BossBarService(ClanKothPlugin plugin, ClanKothExpansion resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    /** Session started (its view is already published). */
    public void show(KothSession s) {
        String zone = s.zone().name();
        hide(zone);
        PluginConfig cfg = plugin.config();
        if (!cfg.bossBarEnabled) return;
        String title = resolver.fill(zone, cfg.bossBarTitle);
        BossBar bar = BossBar.bossBar(Messages.colorize(title), progress(s), cfg.bossBarColor, cfg.bossBarOverlay);
        bars.put(zone, bar);
        lastTitle.put(zone, title);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(bar);
        }
    }

    /** Called right after the session snapshot was republished. */
    public void update(KothSession s) {
        String zone = s.zone().name();
        BossBar bar = bars.get(zone);
        if (bar == null) return;
        String title = resolver.fill(zone, plugin.config().bossBarTitle);
        if (!title.equals(lastTitle.get(zone))) {
            bar.name(Messages.colorize(title));
            lastTitle.put(zone, title);
        }
        bar.progress(progress(s));
    }

    public void hide(String zone) {
        lastTitle.remove(zone);
        BossBar bar = bars.remove(zone);
        if (bar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(bar);
        }
    }

    public void onJoin(Player player) {
        for (BossBar bar : bars.values()) {
            player.showBossBar(bar);
        }
    }

    /** Drop the bar's reference to a leaving player now rather than when the session ends. */
    public void onQuit(Player player) {
        for (BossBar bar : bars.values()) {
            player.hideBossBar(bar);
        }
    }

    /**
     * Whole seconds, matching %time_left%: the value only changes once per second, so extra refreshes (every
     * controller change) do not send a boss bar packet to every player each tick.
     */
    private static float progress(KothSession s) {
        long total = (s.durationTicks() + TimeFormat.TICKS_PER_SECOND - 1) / TimeFormat.TICKS_PER_SECOND;
        if (total <= 0) return 0f;
        long left = (s.remainingTicks() + TimeFormat.TICKS_PER_SECOND - 1) / TimeFormat.TICKS_PER_SECOND;
        float p = (float) left / total;
        return Math.max(BossBar.MIN_PROGRESS, Math.min(BossBar.MAX_PROGRESS, p));
    }
}
