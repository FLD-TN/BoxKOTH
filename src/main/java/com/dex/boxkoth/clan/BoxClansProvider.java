package com.dex.boxkoth.clan;

import com.dex.boxclans.api.Clan;
import com.dex.boxclans.api.ClanPoints;
import com.dex.boxclans.api.ClanService;
import com.dex.boxclans.api.PointResult;
import com.dex.boxclans.api.event.ClanActionEvent;
import com.dex.boxclans.api.event.ClanCreateEvent;
import com.dex.boxclans.api.event.ClanDisbandEvent;
import com.dex.boxclans.api.event.ClanLeaderChangeEvent;
import com.dex.boxclans.api.event.ClanMemberJoinEvent;
import com.dex.boxclans.api.event.ClanMemberLeaveEvent;
import com.dex.boxclans.api.event.ClanRenameEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ClanProvider} backed by the BoxClans API ({@code com.dex:boxclans-api}). This is the only class that
 * imports BoxClans types. BoxClans keeps every clan in memory and its read API is thread-safe, so lookups are
 * cheap; the player -> clan cache is still kept like the previous provider (filled on join, kept current by
 * BoxClans events, which fire on the main thread after the change).
 */
public final class BoxClansProvider implements ClanProvider {

    private static final String NO_CLAN = "";

    private record Names(String name, String tag) {
    }

    private final Logger logger;
    /** Online player -> clan id ("" = no clan). Presence means the player is tracked. */
    private final Map<UUID, String> clanOf = new ConcurrentHashMap<>();
    private final Map<String, Names> names = new ConcurrentHashMap<>();
    private final Set<String> loggedFailures = ConcurrentHashMap.newKeySet();
    private volatile ClanService service;
    private volatile ClanPoints points;
    private Listener listener;
    private Listener actionGuard;

    public BoxClansProvider(Logger logger) {
        this.logger = logger;
    }

    private ClanService service() {
        ClanService s = service;
        if (s == null) {
            try {
                s = Bukkit.getServicesManager().load(ClanService.class);
                service = s;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return s;
    }

    private ClanPoints points() {
        ClanPoints p = points;
        if (p == null) {
            try {
                p = Bukkit.getServicesManager().load(ClanPoints.class);
                points = p;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return p;
    }

    private void logOnce(String what, Throwable t) {
        if (loggedFailures.add(what)) {
            logger.log(Level.WARNING, "BoxClans call '" + what + "' failed; treated as 'no clan'. "
                    + "Further failures of this call are not logged.", t);
        }
    }

    private Optional<Clan> clan(String clanId) {
        ClanService s = service();
        if (s == null || clanId == null || clanId.isEmpty()) return Optional.empty();
        try {
            return s.clan(UUID.fromString(clanId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        } catch (Throwable t) {
            logOnce("ClanService.clan", t);
            return Optional.empty();
        }
    }

    @Override
    public boolean isReady() {
        return service() != null;
    }

    @Override
    public String problem() {
        return null;
    }

    // ---------------------------------------------------------------- player -> clan cache

    @Override
    public String getClanId(UUID player) {
        if (player == null) return null;
        String id = clanOf.get(player);
        return id == null || id.isEmpty() ? null : id;
    }

    @Override
    public void refresh(UUID player) {
        if (player == null) return;
        if (Bukkit.getPlayer(player) == null) {
            clanOf.remove(player);
            return;
        }
        ClanService s = service();
        if (s == null) return;
        try {
            clanOf.put(player, s.clanOf(player).map(c -> c.uuid().toString()).orElse(NO_CLAN));
        } catch (Throwable t) {
            logOnce("ClanService.clanOf", t);
        }
    }

    @Override
    public void forget(UUID player) {
        clanOf.remove(player);
    }

    /** Puts an online player's new clan into the cache (offline players are never tracked). */
    private void track(UUID player, String clanId) {
        if (Bukkit.getPlayer(player) != null) clanOf.put(player, clanId);
    }

    // ---------------------------------------------------------------- clan lookups

    private Names names(String clanId) {
        if (clanId == null) return null;
        Names cached = names.get(clanId);
        if (cached != null) return cached;
        Optional<Clan> clan = clan(clanId);
        if (clan.isEmpty()) return null;
        Names fresh = new Names(clan.get().nameAmpersand(), clan.get().key());
        names.put(clanId, fresh);
        return fresh;
    }

    @Override
    public String getClanName(String clanId) {
        Names n = names(clanId);
        return n == null ? null : n.name();
    }

    @Override
    public String getClanTag(String clanId) {
        Names n = names(clanId);
        return n == null ? null : n.tag();
    }

    @Override
    public UUID getLeader(String clanId) {
        return clan(clanId).map(Clan::leader).orElse(null);
    }

    @Override
    public UUID getVice(String clanId) {
        try {
            return clan(clanId).flatMap(Clan::vice).orElse(null);
        } catch (Throwable t) {
            logOnce("Clan.vice", t);
            return null;
        }
    }

    @Override
    public Set<UUID> getMembers(String clanId) {
        return clan(clanId).map(c -> Set.copyOf(c.members())).orElse(Collections.emptySet());
    }

    @Override
    public boolean clanExists(String clanId) {
        return clan(clanId).isPresent();
    }

    @Override
    public String describe() {
        Plugin p = Bukkit.getPluginManager().getPlugin("BoxClans");
        String version = p == null ? "(missing)" : p.getPluginMeta().getVersion();
        return "BoxClans " + version + (isReady() ? "" : ", service not registered");
    }

    /** Clan points through the BoxClans API (main thread); failures and {@code NO_CLAN} are only logged. */
    @Override
    public void awardPoints(String clanId, String source) {
        ClanPoints p = points();
        if (p == null) {
            logger.warning("Clan points '" + source + "' not given to " + clanId + ": BoxClans ClanPoints unavailable");
            return;
        }
        try {
            PointResult result = p.awardClan(UUID.fromString(clanId), source);
            if (result.success()) {
                logger.info("Clan points '" + source + "' given to " + clanId + ": +" + result.amount()
                        + " (total " + result.total() + ")");
            } else {
                logger.warning("Clan points '" + source + "' not given to " + clanId + ": " + result.status());
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Clan points '" + source + "' failed for " + clanId, t);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void start(Plugin plugin, EventSink sink) {
        this.listener = new Bridge(sink);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        // Separate listener: an older BoxClans without ClanActionEvent only loses the clan lock, nothing else.
        try {
            Listener guard = new ActionGuard(sink);
            Bukkit.getPluginManager().registerEvents(guard, plugin);
            this.actionGuard = guard;
        } catch (LinkageError | RuntimeException e) {
            logger.warning("This BoxClans has no ClanActionEvent: clans are NOT locked during KOTH. Update BoxClans.");
        }
    }

    @Override
    public void stop() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (actionGuard != null) {
            HandlerList.unregisterAll(actionGuard);
            actionGuard = null;
        }
        clanOf.clear();
        names.clear();
    }

    /** Event handlers must not throw into BoxClans: any failure is contained here. */
    private void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            logOnce("event " + what, t);
        }
    }

    /** BoxClans events (main thread, after the change). */
    private final class Bridge implements Listener {
        private final EventSink sink;

        Bridge(EventSink sink) {
            this.sink = sink;
        }

        /** Creating a clan fires no member join event: without this the creator stays "no clan" until rejoin. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onCreate(ClanCreateEvent event) {
            safely("ClanCreateEvent", () -> track(event.creator(), event.clan().uuid().toString()));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(ClanMemberJoinEvent event) {
            safely("ClanMemberJoinEvent", () -> {
                UUID player = event.player();
                String id = event.clan().uuid().toString();
                track(player, id);
                sink.onMemberJoined(player, id);
            });
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onLeave(ClanMemberLeaveEvent event) {
            safely("ClanMemberLeaveEvent", () -> clanOf.replace(event.player(), event.clan().uuid().toString(), NO_CLAN));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDisband(ClanDisbandEvent event) {
            safely("ClanDisbandEvent", () -> {
                String id = event.clan().uuid().toString();
                clanOf.replaceAll((uuid, clan) -> clan.equals(id) ? NO_CLAN : clan);
                names.remove(id);
                sink.onClanDisbanded(id);
            });
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRename(ClanRenameEvent event) {
            safely("ClanRenameEvent", () -> names.remove(event.clan().uuid().toString()));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onLeaderChange(ClanLeaderChangeEvent event) {
            safely("ClanLeaderChangeEvent", () -> {
                names.remove(event.clan().uuid().toString());
                for (UUID member : event.clan().members()) {
                    Player p = Bukkit.getPlayer(member);
                    if (p != null) refresh(member);
                }
            });
        }
    }

    /** BoxClans asks before a membership change (main thread); cancelling forbids it. */
    private final class ActionGuard implements Listener {
        private final EventSink sink;

        ActionGuard(EventSink sink) {
            this.sink = sink;
        }

        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onAction(ClanActionEvent event) {
            safely("ClanActionEvent", () -> {
                if (sink.blockClanAction(event.player(), event.action().name().toLowerCase(Locale.ROOT))) {
                    event.setCancelled(true);
                }
            });
        }
    }
}
