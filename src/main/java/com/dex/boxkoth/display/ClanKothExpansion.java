package com.dex.boxkoth.display;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.session.SessionManager;
import com.dex.boxkoth.util.DependencyCheck;
import com.dex.boxkoth.util.TimeFormat;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * §8.3 PlaceholderAPI expansion. TAB may call it from async threads, so it only reads immutable snapshots
 * (session views, tracker maps, config) and never calls Bukkit or the Clans API (§16.2).
 * <ul>
 *     <li>{@code %clankoth_<key>%}: the zone the player is near</li>
 *     <li>{@code %clankoth_z:<zone>:<key>%}: a named zone</li>
 * </ul>
 */
public final class ClanKothExpansion extends PlaceholderExpansion {

    public static final DateTimeFormatter NEXT_FORMAT = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");
    private static final DateTimeFormatter TODAY_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern TOKEN = Pattern.compile("%([a-z0-9_]+)%");

    private final ClanKothPlugin plugin;

    public ClanKothExpansion(ClanKothPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "clankoth";
    }

    @Override
    public @NotNull String getAuthor() {
        return "dex";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    /**
     * Registers the expansion and verifies it end to end by resolving {@code %clankoth_active%} through
     * PlaceholderAPI. Any failure is reported once with a clear message instead of placeholders silently
     * showing as raw text on the scoreboard.
     */
    public void registerAndVerify() {
        String tested = "PlaceholderAPI " + DependencyCheck.TESTED_PLACEHOLDER_API;
        try {
            if (!register()) {
                plugin.getLogger().severe("PlaceholderAPI refused to register the 'clankoth' expansion; %clankoth_*% "
                        + "placeholders (TAB scoreboard) will show as raw text. Another expansion may use the same "
                        + "identifier.");
                return;
            }
            String probe = PlaceholderAPI.setPlaceholders((OfflinePlayer) null, "%clankoth_active%");
            if (!probe.equals("true") && !probe.equals("false")) {
                plugin.getLogger().severe("PlaceholderAPI self-test failed: %clankoth_active% resolved to '" + probe
                        + "'. The PlaceholderAPI expansion API may have changed (built for " + tested + ").");
            }
        } catch (LinkageError e) {
            plugin.getLogger().severe("PlaceholderAPI is not compatible with this ClanKoth build (" + e
                    + "); %clankoth_*% placeholders are unavailable. Built for " + tested + ".");
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "PlaceholderAPI registration failed", e);
        }
    }

    /** Unregisters, ignoring errors from an incompatible PlaceholderAPI. */
    public void safeUnregister() {
        try {
            unregister();
        } catch (LinkageError | RuntimeException ignored) {
            // Nothing to clean up if it never registered.
        }
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        try {
            UUID viewer = player == null ? null : player.getUniqueId();
            if (params.startsWith("z:")) {
                int sep = params.indexOf(':', 2);
                if (sep < 0) return "";
                String zone = params.substring(2, sep);
                String key = params.substring(sep + 1);
                if (key.equals("near") || !plugin.zones().exists(zone)) return "";
                return orEmpty(resolve(zone, key, viewer));
            }
            String zone = viewer == null ? null : plugin.tracker().nearZone(viewer);
            if (params.equals("near")) {
                return zone != null ? "true" : "false";
            }
            return orEmpty(resolve(zone, params, viewer));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Resolves a zone-scoped key. {@code zone} may be null (player not near any zone): the empty texts are
     * returned. Returns null for unknown keys so callers can leave them untouched.
     */
    public String resolve(String zone, String key, UUID viewer) {
        PluginConfig cfg = plugin.config();
        SessionManager.View view = plugin.sessions().view(zone);
        switch (key) {
            case "zone":
                return zone == null ? "" : zone;
            case "active":
                return view != null ? "true" : "false";
            case "controller":
                return view != null && view.controllerName() != null ? view.controllerName() : cfg.emptyController;
            case "controller_clan":
                return view != null && view.controllerClanName() != null ? view.controllerClanName() : cfg.emptyController;
            case "time_left":
                return view != null ? TimeFormat.mmssCeil(view.remainingTicks()) : cfg.emptyTime;
            case "my_clan_time": {
                // Provider cache read: thread-safe, never calls the Clans API.
                String clan = viewer == null ? null : plugin.clans().getClanId(viewer);
                if (view == null || clan == null) return cfg.emptyTime;
                return TimeFormat.mmss(view.clanTicks().getOrDefault(clan, 0L));
            }
            case "my_clan_rank": {
                String clan = viewer == null ? null : plugin.clans().getClanId(viewer);
                Integer rank = view == null || clan == null ? null : view.rankByClan().get(clan);
                return rank == null ? cfg.emptyTop : String.valueOf(rank);
            }
            case "next": {
                ZonedDateTime next = zone == null ? null : plugin.scheduler().next(zone);
                return next == null ? cfg.emptyNext : formatNext(next, cfg);
            }
            case "next_in": {
                ZonedDateTime next = zone == null ? null : plugin.scheduler().next(zone);
                if (next == null) return cfg.emptyNext;
                long millis = Duration.between(Instant.now(), next.toInstant()).toMillis();
                return TimeFormat.hhmmss((Math.max(0L, millis) + 999) / 1000);
            }
            default:
                return resolveTop(key, view, cfg);
        }
    }

    /**
     * Replaces {@code %key%} tokens (hologram / boss bar templates) with this zone's values in one pass, so a
     * clan name containing "%...%" is never expanded. Unknown tokens are left untouched.
     */
    public String fill(String zone, String text) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        while (m.find()) {
            String value = resolve(zone, m.group(1), null);
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** U2.4: "HH:mm:ss" if the start is today in the configured timezone, otherwise "dd/MM HH:mm:ss". */
    public static String formatNext(ZonedDateTime next, PluginConfig cfg) {
        ZonedDateTime local = next.withZoneSameInstant(cfg.timezone);
        boolean today = local.toLocalDate().equals(LocalDate.now(cfg.timezone));
        return (today ? TODAY_FORMAT : NEXT_FORMAT).format(local);
    }

    /** top_<n>_clan / top_<n>_time, n = 1..5. */
    private static String resolveTop(String key, SessionManager.View view, PluginConfig cfg) {
        if (!key.startsWith("top_") || key.length() < 10) return null;
        int end = key.indexOf('_', 4);
        if (end < 0) return null;
        int n;
        try {
            n = Integer.parseInt(key.substring(4, end));
        } catch (NumberFormatException e) {
            return null;
        }
        if (n < 1 || n > SessionManager.TOP_SIZE) return null;
        String field = key.substring(end + 1);
        SessionManager.TopEntry entry = view != null && view.top().size() >= n ? view.top().get(n - 1) : null;
        return switch (field) {
            case "clan" -> entry != null ? entry.name() : cfg.emptyTop;
            case "time" -> entry != null ? TimeFormat.mmss(entry.ticks()) : cfg.emptyTime;
            default -> null;
        };
    }
}
