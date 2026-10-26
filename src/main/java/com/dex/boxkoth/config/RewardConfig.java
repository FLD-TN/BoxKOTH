package com.dex.boxkoth.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/** Immutable, validated view of rewards.yml. */
public final class RewardConfig {

    public record RankRewards(List<String> leader, List<String> members, List<String> mvp) {
    }

    private static final String FILE = "rewards.yml";

    /** Clan points through BoxClans for ranks 1-3 ({@code koth-top1..3}); true when the key is missing. */
    public final boolean clanPoints;
    public final boolean stackRewards;
    /** Minh Chủ offline at the end: the Phó Bang (if online) receives the leader reward instead. */
    public final boolean viceStandsIn;
    public final boolean membersOnePerIp;
    /** Rank (1–5) -> rewards. Missing ranks mean no reward. */
    public final Map<Integer, RankRewards> ranks;

    private RewardConfig(YamlConfiguration c, Logger log) {
        clanPoints = c.getBoolean("clan-points", true);
        stackRewards = c.getBoolean("stack-rewards", true);
        viceStandsIn = c.getBoolean("vice-stands-in-for-leader", true);
        membersOnePerIp = c.getBoolean("members-one-per-ip", false);

        Map<Integer, RankRewards> map = new TreeMap<>();
        ConfigurationSection section = c.getConfigurationSection("ranks");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int rank;
                try {
                    rank = Integer.parseInt(key.trim());
                } catch (NumberFormatException e) {
                    rank = -1;
                }
                if (rank < 1 || rank > 5) {
                    log.warning("[" + FILE + "] invalid rank key 'ranks." + key + "' (must be 1-5), ignored.");
                    continue;
                }
                ConfigurationSection r = section.getConfigurationSection(key);
                if (r == null) continue;
                map.put(rank, new RankRewards(
                        List.copyOf(r.getStringList("leader")),
                        List.copyOf(r.getStringList("members")),
                        List.copyOf(r.getStringList("mvp"))));
            }
        }
        ranks = Collections.unmodifiableMap(map);
    }

    public static RewardConfig parse(YamlConfiguration yaml, Logger log) {
        return new RewardConfig(yaml, log);
    }
}
