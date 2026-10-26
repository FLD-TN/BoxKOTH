package com.dex.boxkoth.clan;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * The only gateway to the Clans plugin (§7). Methods never throw; every "not found" result is null / empty.
 * <p>
 * Player -> clan lookups are served from an in-memory cache (U4.1): {@link #getClanId(UUID)} never calls the
 * Clans API and is safe from any thread. The cache is filled by {@link #refresh(UUID)} (main thread) and kept
 * current by Clans events.
 */
public interface ClanProvider {

    /** False while Clans is loading, and permanently false once {@link #problem()} is set. */
    boolean isReady();

    /** Non-null when the installed Clans is incompatible with this build (missing API method/class). */
    String problem();

    /** Cached clan id of an online player, or null (no clan / not cached). Thread-safe, no Clans call. */
    String getClanId(UUID player);

    /** Main thread: looks the player up in Clans and updates the cache. */
    void refresh(UUID player);

    /**
     * Main thread: cached clan id, re-checked in Clans when the cache says "no clan", so a clan change the cache
     * missed never keeps a player out of a zone until they rejoin. Used for players inside a zone only.
     */
    default String resolveClanId(UUID player) {
        String id = getClanId(player);
        if (id != null) return id;
        refresh(player);
        return getClanId(player);
    }

    /** Drops a player from the cache (quit). */
    void forget(UUID player);

    /** Display name, may contain & colour codes; null if the clan no longer exists. Cached. */
    String getClanName(String clanId);

    /** Cached. */
    String getClanTag(String clanId);

    /** Main thread. Owner at call time. */
    UUID getLeader(String clanId);

    /** Main thread. The Phó Bang at call time (the first one if several), or null. Default: none. */
    default UUID getVice(String clanId) {
        return null;
    }

    /** Main thread. All members including the owner; empty if the clan no longer exists. */
    Set<UUID> getMembers(String clanId);

    /** Main thread. */
    boolean clanExists(String clanId);

    /** Version string of the Clans plugin API, for diagnostics. */
    String describe();

    /** Registers Clans event listeners and the cache resync task. Callbacks may arrive on any thread. */
    void start(Plugin plugin, EventSink sink);

    /**
     * Main thread: awards clan points of a source to a clan (BoxClans {@code points.yml}), e.g. {@code koth-top1}.
     * Never throws; failures are only logged. Default: no clan points.
     */
    default void awardPoints(String clanId, String source) {
    }

    /** Cancels the resync task and clears caches. */
    void stop();

    /** Clan events translated to plain ids, so no other class needs Clans API types. */
    interface EventSink {
        void onMemberJoined(UUID player, String clanId);

        void onClanDisbanded(String clanId);

        /**
         * Main thread, BEFORE a player creates / disbands / leaves / joins a clan, kicks or invites someone, or
         * hands the clan over.
         * {@code action} is {@code create}, {@code disband}, {@code leave}, {@code join}, {@code kick},
         * {@code invite} or {@code transfer}. Return true to forbid it; the sink tells the player why.
         */
        boolean blockClanAction(Player player, String action);
    }
}
