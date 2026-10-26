package com.dex.boxkoth.display;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.config.Messages;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.session.KothSession;
import com.dex.boxkoth.util.TimeFormat;
import com.dex.boxkoth.zone.Zone;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Player-facing effects: start title and controller-change message/sound. Main thread only. */
public final class Notifier {

    private static final long MILLIS_PER_TICK = 50L;

    private final ClanKothPlugin plugin;
    private final NearbyTracker tracker;
    /** Zone -> clan controlling it after the last change (null = nobody). */
    private final Map<String, String> lastClan = new HashMap<>();

    public Notifier(ClanKothPlugin plugin, NearbyTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    /** Title to every online player when a session starts. */
    public void sessionStarted(Zone zone, int durationSeconds) {
        lastClan.remove(zone.name());
        PluginConfig cfg = plugin.config();
        Messages msg = plugin.messages();
        Title title = null;
        if (cfg.startTitleEnabled) {
            String[] kv = {"zone", zone.name(), "duration", TimeFormat.formatSeconds(durationSeconds),
                    "duration_human", humanTime(durationSeconds)};
            String main = Messages.format(msg.raw("title-start"), kv);
            String sub = Messages.format(msg.raw("subtitle-start"), kv);
            if (!main.isEmpty() || !sub.isEmpty()) {
                title = Title.title(Messages.colorize(main), Messages.colorize(sub), Title.Times.times(
                        Duration.ofMillis(cfg.titleFadeInTicks * MILLIS_PER_TICK),
                        Duration.ofMillis(cfg.titleStayTicks * MILLIS_PER_TICK),
                        Duration.ofMillis(cfg.titleFadeOutTicks * MILLIS_PER_TICK)));
            }
        }
        if (title == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
        }
    }

    public void sessionEnded(String zone) {
        lastClan.remove(zone);
    }

    /**
     * Called on every controller change. When someone took control: the capture sound to players near the zone
     * (the same area that shows the KOTH scoreboard). When a NEW clan took it (from nobody or from another clan):
     * {@code capture-chat} in chat or {@code capture-actionbar} on the action bar of every online player
     * (config: controller-change-display). Losing control, or a hand-over inside the same clan, shows nothing.
     * No throttling: overlapping sounds simply play on top of each other.
     */
    public void controller(KothSession s) {
        PluginConfig cfg = plugin.config();
        String zone = s.zone().name();
        UUID now = s.controller();
        String clan = now == null ? null : s.controllerClan();
        String clanBefore = lastClan.put(zone, clan);
        if (now == null) return;

        Sound sound = cfg.captureSound;
        if (sound != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (zone.equals(tracker.nearZone(p.getUniqueId()))) p.playSound(sound);
            }
        }
        if (cfg.controllerDisplay == PluginConfig.ControllerDisplay.NONE || clan.equals(clanBefore)) return;

        boolean chat = cfg.controllerDisplay == PluginConfig.ControllerDisplay.CHAT;
        Messages msg = plugin.messages();
        String raw = Messages.format(msg.raw(chat ? "capture-chat" : "capture-actionbar"),
                "zone", zone,
                "clan", plugin.sessions().clanName(clan),
                "player", playerName(now));
        if (raw.isEmpty()) return;
        Component text = Messages.colorize(raw);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (chat) {
                p.sendMessage(text);
            } else {
                p.sendActionBar(text);
            }
        }
    }

    private static String playerName(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        return p == null ? "" : p.getName();
    }

    public String humanTime(long seconds) {
        Messages msg = plugin.messages();
        return TimeFormat.human(seconds, msg.raw("unit-hour"), msg.raw("unit-minute"), msg.raw("unit-second"));
    }
}
