package com.dex.boxkoth.reward;

import com.dex.boxkoth.ClanKothPlugin;
import com.dex.boxkoth.clan.ClanProvider;
import com.dex.boxkoth.config.Messages;
import com.dex.boxkoth.config.RewardConfig;
import com.dex.boxkoth.session.KothSession;
import com.dex.boxkoth.session.Ranking;
import com.dex.boxkoth.util.TimeFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * §6: resolves leader / mvp / members per ranked clan and dispatches console commands. Rewards are online-only:
 * a recipient who is offline when the session ends gets nothing (logged so an admin can compensate by hand).
 * The one exception: the leader reward goes to the Phó Bang when the Minh Chủ is offline
 * ({@code vice-stands-in-for-leader}). It is always given to exactly one player.
 */
public final class RewardService {

    /** Why a player is rewarded; selects the {@code reward-<target>} message. */
    public enum Target {
        LEADER("reward-leader"), LEADER_STAND_IN("reward-leader-stand-in"), MVP("reward-mvp"),
        MEMBERS("reward-members");

        final String messageKey;

        Target(String messageKey) {
            this.messageKey = messageKey;
        }
    }

    private static final Pattern COLOUR_CODES = Pattern.compile("(?i)[&§]#[0-9a-f]{6}|[&§][0-9a-fk-orx]");
    private static final Pattern UNSAFE = Pattern.compile("[^\\p{L}\\p{N}_-]");
    private static final Pattern TOKEN = Pattern.compile("%([a-z_]+)%");

    private final ClanKothPlugin plugin;
    private final ClanProvider clans;

    public RewardService(ClanKothPlugin plugin, ClanProvider clans) {
        this.plugin = plugin;
        this.clans = clans;
    }

    /** Strips colours, then keeps only letters, digits, '_' and '-' (§16.1). */
    public static String sanitize(String text, String fallback) {
        if (text == null) return fallback;
        String clean = UNSAFE.matcher(COLOUR_CODES.matcher(text).replaceAll("")).replaceAll("");
        return clean.isEmpty() ? fallback : clean;
    }

    /** Gives rewards for an ended session. {@code ranking} already excludes disbanded clans. */
    public void give(KothSession session, List<Ranking.Entry<String>> ranking) {
        RewardConfig cfg = plugin.rewards();
        if (cfg.clanPoints) awardClanPoints(ranking, clans, plugin.getLogger());
        Set<String> usedIps = new HashSet<>();
        for (int i = 0; i < ranking.size(); i++) {
            int rank = i + 1;
            RewardConfig.RankRewards rr = cfg.ranks.get(rank);
            if (rr == null) continue;
            try {
                giveRank(session, ranking.get(i), rank, rr, cfg, usedIps);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Failed to give rank " + rank + " rewards", t);
            }
        }
    }

    /**
     * Clan points for ranks 1-3: source {@code koth-top<rank>} straight to the clan (no one needs to be online).
     * A failure only logs and never blocks the command rewards.
     */
    public static void awardClanPoints(List<Ranking.Entry<String>> ranking, ClanProvider clans, Logger log) {
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            String source = "koth-top" + (i + 1);
            try {
                clans.awardPoints(ranking.get(i).key(), source);
            } catch (Throwable t) {
                log.log(Level.WARNING, "Clan points '" + source + "' failed", t);
            }
        }
    }

    private void giveRank(KothSession session, Ranking.Entry<String> entry, int rank, RewardConfig.RankRewards rr,
                          RewardConfig cfg, Set<String> usedIps) {
        String clanId = entry.key();
        Set<UUID> members = clans.getMembers(clanId);
        UUID leader = clans.getLeader(clanId);
        UUID mvp = findMvp(session, clanId, members);
        Context ctx = new Context(session, clanId, rank, entry.ticks());
        Set<UUID> rewarded = new HashSet<>();
        Map<UUID, Grant> grants = new LinkedHashMap<>();

        // Order matters when stack-rewards is false: leader, mvp, members.
        if (leader != null && !rr.leader().isEmpty()) {
            // The Phó Bang is only looked up once the Minh Chủ is known to be offline.
            UUID vice = cfg.viceStandsIn && !isOnline(leader) ? clans.getVice(clanId) : null;
            UUID recipient = leaderRecipient(leader, vice, cfg.viceStandsIn, RewardService::isOnline,
                    uuid -> session.joinedDuringSession(uuid, clanId));
            if (recipient == null) {
                plugin.getLogger().info("Reward LEADER rank " + rank + " (" + session.zone().name() + ") skipped: Minh Chu "
                        + leader + " is offline" + (cfg.viceStandsIn ? " and no online Pho Bang can stand in." : "."));
            } else {
                Target target = recipient.equals(leader) ? Target.LEADER : Target.LEADER_STAND_IN;
                if (target == Target.LEADER_STAND_IN) {
                    plugin.getLogger().info("Minh Chu " + leader + " is offline: Pho Bang " + recipient
                            + " receives the leader reward of rank " + rank + " (" + session.zone().name() + ").");
                }
                collect(grants, recipient, rr.leader(), ctx, target);
                rewarded.add(recipient);
            }
        }
        if (mvp != null && !rr.mvp().isEmpty() && (cfg.stackRewards || !rewarded.contains(mvp))) {
            collect(grants, mvp, rr.mvp(), ctx, Target.MVP);
            rewarded.add(mvp);
        }
        if (!rr.members().isEmpty()) {
            for (UUID uuid : members) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;
                if (session.joinedDuringSession(uuid, clanId)) {
                    plugin.debug("Members reward skipped for " + p.getName() + ": joined clan during the session");
                    continue;
                }
                if (!cfg.stackRewards && rewarded.contains(uuid)) continue;
                if (cfg.membersOnePerIp) {
                    String ip = ipOf(p);
                    if (ip != null && !usedIps.add(ip)) {
                        plugin.debug("Members reward skipped for " + p.getName() + ": IP already rewarded");
                        continue;
                    }
                }
                collect(grants, uuid, rr.members(), ctx, Target.MEMBERS);
                rewarded.add(uuid);
            }
        }
        for (Map.Entry<UUID, Grant> e : grants.entrySet()) {
            deliver(e.getKey(), e.getValue(), ctx);
        }
    }

    /**
     * Who receives the leader reward, or null for nobody. Exactly one player at most:
     * <ul>
     *     <li>the Minh Chủ if online;</li>
     *     <li>otherwise, if {@code viceStandsIn}, the Phó Bang, only when they are online, not the Minh Chủ
     *     themself and not {@code excluded} (joined the clan during the session);</li>
     *     <li>otherwise nobody (rewards are online-only, nothing is stored for later).</li>
     * </ul>
     * Runs on the main thread in the tick that ends the session, so nobody can log in between the online check
     * and the delivery.
     */
    static UUID leaderRecipient(UUID leader, UUID vice, boolean viceStandsIn, Predicate<UUID> online,
                                Predicate<UUID> excluded) {
        if (leader == null) return null;
        if (online.test(leader)) return leader;
        if (!viceStandsIn || vice == null || vice.equals(leader)) return null;
        return online.test(vice) && !excluded.test(vice) ? vice : null;
    }

    private static boolean isOnline(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        return p != null && p.isOnline();
    }

    /** Best player for this clan who is still a member and has time > 0. */
    private static UUID findMvp(KothSession session, String clanId, Set<UUID> members) {
        for (Ranking.Entry<UUID> e : session.playerRanking(clanId)) {
            if (members.contains(e.key())) {
                return e.key();
            }
        }
        return null;
    }

    /** Everything one player gets for one rank: delivered together, with one message. */
    private static final class Grant {
        final String name;
        final List<String> commands = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        final List<Target> targets = new ArrayList<>();

        Grant(String name) {
            this.name = name;
        }
    }

    /** Online players only; an offline leader or MVP loses that reward (members are online-only anyway). */
    private void collect(Map<UUID, Grant> grants, UUID uuid, List<String> templates, Context ctx, Target target) {
        Player online = Bukkit.getPlayer(uuid);
        if (online == null || !online.isOnline()) {
            if (target != Target.MEMBERS) {
                plugin.getLogger().info("Reward " + target + " rank " + ctx.rank + " (" + ctx.session.zone().name()
                        + ") skipped: " + uuid + " is offline.");
            }
            return;
        }
        Grant grant = grants.get(uuid);
        if (grant == null) {
            grant = new Grant(online.getName());
            grants.put(uuid, grant);
        }
        for (String template : templates) {
            grant.commands.add(ctx.resolve(template, grant.name, uuid));
        }
        String line = Messages.format(plugin.messages().raw(target.messageKey), ctx.messageValues(uuid));
        if (!line.isEmpty()) grant.lines.add(line);
        grant.targets.add(target);
    }

    private void deliver(UUID uuid, Grant grant, Context ctx) {
        String message = message(grant, ctx, uuid);
        Player online = Bukkit.getPlayer(uuid);
        String what = "Reward " + grant.targets + " rank " + ctx.rank + " (" + ctx.session.zone().name() + ")";
        if (online == null || !online.isOnline()) {
            // Collected and delivered in the same tick; kept as a guard only
            plugin.getLogger().info(what + " skipped: " + grant.name + " went offline: " + grant.commands);
            return;
        }
        dispatch(grant.commands);
        if (!message.isEmpty()) online.sendMessage(Messages.colorize(message));
        plugin.getLogger().info(what + " given to " + grant.name + ": " + grant.commands);
    }

    /** reward-header, one line per reward, reward-footer; "" when every reward line is disabled. */
    private String message(Grant grant, Context ctx, UUID uuid) {
        if (grant.lines.isEmpty()) return "";
        String[] kv = ctx.messageValues(uuid);
        List<String> all = new ArrayList<>(plugin.messages().lines("reward-header", kv));
        all.addAll(grant.lines);
        all.addAll(plugin.messages().lines("reward-footer", kv));
        // &r per line: a format left open on one line must not leak into the next.
        StringBuilder sb = new StringBuilder();
        for (String line : all) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("&r").append(line);
        }
        return sb.toString();
    }

    /** Dispatches as the console only (§16.1). */
    public void dispatch(List<String> commands) {
        for (String command : commands) {
            String cmd = command.startsWith("/") ? command.substring(1) : command;
            if (cmd.isBlank()) continue;
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Reward command failed: " + cmd, t);
            }
        }
    }

    private static String ipOf(Player p) {
        InetSocketAddress addr = p.getAddress();
        return addr == null || addr.getAddress() == null ? null : addr.getAddress().getHostAddress();
    }

    /** Values for §6.4 placeholders, computed once per clan. */
    private final class Context {
        final KothSession session;
        final String clanId;
        final int rank;
        final long clanTicks;
        private final String clanRaw;
        private final String clanSafe;
        private final String tagSafe;

        Context(KothSession session, String clanId, int rank, long clanTicks) {
            this.session = session;
            this.clanId = clanId;
            this.rank = rank;
            this.clanTicks = clanTicks;
            String name = clans.getClanName(clanId);
            this.clanRaw = name == null ? clanId : name;
            this.clanSafe = sanitize(name, clanId);
            this.tagSafe = sanitize(clans.getClanTag(clanId), clanId);
        }

        String[] messageValues(UUID player) {
            return new String[]{
                    "rank", String.valueOf(rank),
                    "zone", session.zone().name(),
                    "clan", clanRaw,
                    "clan_time", TimeFormat.mmss(clanTicks),
                    "player_time", TimeFormat.mmss(session.playerTicks(player, clanId))};
        }

        /** Single pass, so a substituted value is never scanned for placeholders again. */
        String resolve(String template, String playerName, UUID player) {
            Matcher m = TOKEN.matcher(template);
            StringBuilder out = new StringBuilder(template.length() + 32);
            while (m.find()) {
                String value = switch (m.group(1)) {
                    case "player" -> playerName;
                    case "player_time" -> TimeFormat.mmss(session.playerTicks(player, clanId));
                    case "clan_time" -> TimeFormat.mmss(clanTicks);
                    case "clan_display" -> clanRaw;
                    case "clan_tag" -> tagSafe;
                    case "clan" -> clanSafe;
                    case "rank" -> String.valueOf(rank);
                    case "zone" -> session.zone().name();
                    default -> m.group();
                };
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            m.appendTail(out);
            return out.toString();
        }
    }
}
