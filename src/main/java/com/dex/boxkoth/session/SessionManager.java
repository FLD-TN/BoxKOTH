package com.dex.boxkoth.session;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.clan.ClanProvider;
import com.dex.boxkoth.config.Messages;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.display.BossBarService;
import com.dex.boxkoth.display.GlowService;
import com.dex.boxkoth.display.HologramService;
import com.dex.boxkoth.display.Notifier;
import com.dex.boxkoth.reward.RewardService;
import com.dex.boxkoth.util.TimeFormat;
import com.dex.boxkoth.zone.Zone;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Starts and ends sessions; one session per zone. Main thread only, except {@link #view(String)}. */
public final class SessionManager {

    public enum StartResult {STARTED, ALREADY_RUNNING, CLANS_INCOMPATIBLE, CLANS_NOT_READY, WORLD_NOT_LOADED}

    public enum EndReason {
        EXPIRED(true, true),
        STOPPED(true, true),
        STOPPED_NO_REWARDS(false, true),
        WORLD_UNLOADED(false, false),
        ERROR(false, false),
        DISABLE(false, false);

        final boolean rewards;
        final boolean announceResults;

        EndReason(boolean rewards, boolean announceResults) {
            this.rewards = rewards;
            this.announceResults = announceResults;
        }
    }

    public record TopEntry(String clanId, String name, long ticks) {
    }

    /**
     * Immutable snapshot of a running session, replaced every tick. Safe to read from any thread
     * (TAB requests placeholders asynchronously, §16.2).
     */
    public record View(String zone, String controllerName, String controllerClanName, long remainingTicks,
                       List<TopEntry> top, Map<String, Integer> rankByClan, Map<String, Long> clanTicks) {
    }

    public static final int TOP_SIZE = 5;

    private final ClanKothPlugin plugin;
    private final ClanProvider clans;
    private final Map<String, KothSession> sessions = new LinkedHashMap<>();
    private final Map<String, View> views = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private RewardService rewards;
    private HologramService holograms;
    private Notifier notifier;
    private BossBarService bossBars;
    private GlowService glow;

    public SessionManager(ClanKothPlugin plugin, ClanProvider clans) {
        this.plugin = plugin;
        this.clans = clans;
    }

    public void wire(RewardService rewards, HologramService holograms, Notifier notifier, BossBarService bossBars,
                     GlowService glow) {
        this.rewards = rewards;
        this.holograms = holograms;
        this.notifier = notifier;
        this.bossBars = bossBars;
        this.glow = glow;
    }

    public StartResult start(Zone zone, Integer durationSeconds) {
        if (sessions.containsKey(zone.name())) {
            return StartResult.ALREADY_RUNNING;
        }
        if (clans.problem() != null) {
            plugin.getLogger().warning("Refusing to start KOTH '" + zone.name() + "': Clans API is incompatible ("
                    + clans.problem() + ").");
            return StartResult.CLANS_INCOMPATIBLE;
        }
        if (!clans.isReady()) {
            plugin.getLogger().warning("Refusing to start KOTH '" + zone.name() + "': Clans API is not ready.");
            return StartResult.CLANS_NOT_READY;
        }
        if (Bukkit.getWorld(zone.world()) == null) {
            return StartResult.WORLD_NOT_LOADED;
        }
        PluginConfig cfg = plugin.config();
        int seconds = durationSeconds != null ? durationSeconds : cfg.durationSeconds(zone);
        KothSession session = new KothSession(zone, seconds * 1000L, cfg.selectionMode, random,
                KothSession.MONOTONIC_MILLIS);
        sessions.put(zone.name(), session);
        publishView(session);
        holograms.refresh(zone.name());
        bossBars.show(session);
        plugin.messages().broadcast("broadcast-start", "zone", zone.name(), "duration", TimeFormat.formatSeconds(seconds),
                "duration_human", notifier.humanTime(seconds));
        notifier.sessionStarted(zone, seconds);
        plugin.debug("Session started on " + zone.name() + " for " + seconds + "s");
        return StartResult.STARTED;
    }

    /**
     * The single end routine (§16.3). Stop command, natural expiry, errors and plugin disable all go
     * through here; the RUNNING -> ENDING compare-and-set guarantees it runs once per session.
     */
    public void end(KothSession session, EndReason reason) {
        if (!session.beginEnding()) {
            return;
        }
        Zone zone = session.zone();
        Messages msg = plugin.messages();
        try {
            List<Ranking.Entry<String>> ranking = finalRanking(session);
            logRanking(zone, ranking);
            if (reason.announceResults) {
                announceResults(zone, ranking, reason);
            } else if (reason != EndReason.DISABLE) {
                msg.broadcast("broadcast-cancelled", "zone", zone.name());
            }
            if (reason == EndReason.ERROR) {
                msg.staff("clankoth.admin", "staff-session-error", "zone", zone.name());
            }
            if (reason.rewards && !ranking.isEmpty()) {
                rewards.give(session, ranking);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Error while ending KOTH session '" + zone.name() + "'", t);
        } finally {
            sessions.remove(zone.name());
            views.remove(zone.name());
            session.finish();
            try {
                holograms.refresh(zone.name());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not reset hologram of '" + zone.name() + "'", t);
            }
            try {
                bossBars.hide(zone.name());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not remove boss bar of '" + zone.name() + "'", t);
            }
            notifier.sessionEnded(zone.name());
            try {
                glow.clear(zone.name());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Could not remove controller glow of '" + zone.name() + "'", t);
            }
        }
    }

    /** Ranking with disbanded clans removed (§16.4); ranks below move up. */
    private List<Ranking.Entry<String>> finalRanking(KothSession session) {
        List<Ranking.Entry<String>> ranking = new ArrayList<>(session.ranking());
        if (clans.isReady()) {
            ranking.removeIf(e -> !clans.clanExists(e.key()));
        }
        return ranking;
    }

    private void announceResults(Zone zone, List<Ranking.Entry<String>> ranking, EndReason reason) {
        Messages msg = plugin.messages();
        if (ranking.isEmpty()) {
            msg.broadcast("broadcast-end-no-winner", "zone", zone.name());
            return;
        }
        msg.broadcast("broadcast-end-header", "zone", zone.name());
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            Ranking.Entry<String> e = ranking.get(i);
            msg.broadcastRaw(Messages.format(msg.raw("broadcast-end-line"),
                    "rank", String.valueOf(i + 1),
                    "clan", clanName(e.key()),
                    "time", TimeFormat.mmss(e.ticks())));
        }
        if (reason == EndReason.STOPPED_NO_REWARDS) {
            msg.broadcast("broadcast-end-no-rewards", "zone", zone.name());
        }
    }

    private void logRanking(Zone zone, List<Ranking.Entry<String>> ranking) {
        if (!plugin.config().debug) return;
        StringBuilder sb = new StringBuilder("Ranking for ").append(zone.name()).append(':');
        int rank = 1;
        for (Ranking.Entry<String> e : ranking) {
            sb.append(" #").append(rank++).append(' ').append(e.key())
                    .append('=').append(e.ticks()).append("t(last ").append(e.lastTick()).append(')');
        }
        plugin.debug(sb.toString());
    }

    public boolean stop(String zoneName, boolean giveRewards) {
        KothSession s = sessions.get(zoneName);
        if (s == null) return false;
        end(s, giveRewards ? EndReason.STOPPED : EndReason.STOPPED_NO_REWARDS);
        return true;
    }

    public void endAll(EndReason reason) {
        for (KothSession s : new ArrayList<>(sessions.values())) {
            end(s, reason);
        }
    }

    /**
     * Called by the capture task after a tick changed the controller. The snapshot (and what displays it) is
     * republished first, so TAB / hologram / boss bar show the new controller in the same tick as the chat
     * message and sound instead of up to a second later.
     */
    public void onControllerChanged(KothSession s, UUID previous) {
        UUID now = s.controller();
        if (plugin.config().debug) {
            plugin.debug("Controller of " + s.zone().name() + ": " + previous + " -> " + now
                    + (now == null ? "" : " (" + playerName(now) + ", clan " + s.controllerClan() + ")"));
        }
        glow.update(s.zone().name(), now);
        refreshDisplays(s);
        notifier.controller(s);
    }

    /**
     * New snapshot, then everything that displays it, in the same tick so hologram, boss bar and placeholders
     * never disagree. Runs once per second and on every controller change.
     */
    public void refreshDisplays(KothSession s) {
        publishView(s);
        holograms.refresh(s.zone().name());
        bossBars.update(s);
    }

    /** Rebuilds the immutable snapshot for placeholders and holograms. */
    public void publishView(KothSession s) {
        List<Ranking.Entry<String>> ranking = s.ranking();
        List<TopEntry> top = new ArrayList<>(Math.min(TOP_SIZE, ranking.size()));
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < ranking.size(); i++) {
            Ranking.Entry<String> e = ranking.get(i);
            ranks.put(e.key(), i + 1);
            if (i < TOP_SIZE) {
                top.add(new TopEntry(e.key(), clanName(e.key()), e.ticks()));
            }
        }
        UUID controller = s.controller();
        views.put(s.zone().name(), new View(
                s.zone().name(),
                controller == null ? null : playerName(controller),
                controller == null ? null : clanName(s.controllerClan()),
                s.remainingTicks(),
                Collections.unmodifiableList(top),
                Collections.unmodifiableMap(ranks),
                s.clanTicksCopy()));
    }

    public void recordJoin(UUID player, String clanId) {
        for (KothSession s : sessions.values()) {
            if (s.isRunning()) {
                s.recordJoin(player, clanId);
            }
        }
        plugin.debug("Clan join recorded for running sessions: " + player + " -> " + clanId);
    }

    public void onClanDisbanded(String clanId) {
        if (clans.clanExists(clanId)) {
            return; // disband was cancelled or has not happened
        }
        for (KothSession s : sessions.values()) {
            if (s.isRunning()) {
                s.removeClan(clanId);
                publishView(s);
            }
        }
        plugin.debug("Disbanded clan removed from rankings: " + clanId);
    }

    /** Display name, falling back to the id when the clan is gone. */
    public String clanName(String clanId) {
        if (clanId == null) return "";
        String name = clans.getClanName(clanId);
        return name == null ? clanId : name;
    }

    private static String playerName(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString() : name;
    }

    public List<KothSession> running() {
        return new ArrayList<>(sessions.values());
    }

    public KothSession get(String zoneName) {
        return sessions.get(zoneName);
    }

    public boolean isRunning(String zoneName) {
        return sessions.containsKey(zoneName);
    }

    public boolean anyRunning() {
        return !sessions.isEmpty();
    }

    /** Thread-safe snapshot read. */
    public View view(String zoneName) {
        return zoneName == null ? null : views.get(zoneName);
    }
}
