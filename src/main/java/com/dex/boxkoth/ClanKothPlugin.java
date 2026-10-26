package com.dex.boxkoth;

import com.dex.boxkoth.clan.ClanProvider;
import com.dex.boxkoth.clan.BoxClansProvider;
import com.dex.boxkoth.command.KothCommand;
import com.dex.boxkoth.config.Messages;
import com.dex.boxkoth.config.PluginConfig;
import com.dex.boxkoth.config.RewardConfig;
import com.dex.boxkoth.display.BossBarService;
import com.dex.boxkoth.display.ClanKothExpansion;
import com.dex.boxkoth.display.GlowService;
import com.dex.boxkoth.display.HologramService;
import com.dex.boxkoth.display.NearbyTracker;
import com.dex.boxkoth.display.Notifier;
import com.dex.boxkoth.listener.ClanEventListener;
import com.dex.boxkoth.listener.PlayerListener;
import com.dex.boxkoth.reward.RewardService;
import com.dex.boxkoth.schedule.KothScheduler;
import com.dex.boxkoth.session.CaptureTask;
import com.dex.boxkoth.session.SessionManager;
import com.dex.boxkoth.util.DependencyCheck;
import com.dex.boxkoth.zone.SelectionManager;
import com.dex.boxkoth.zone.Zone;
import com.dex.boxkoth.zone.ZoneManager;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Registry;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;

/** Wiring and lifecycle only. Bukkit's /reload is not supported; use /koth reload. */
public final class ClanKothPlugin extends JavaPlugin {

    private volatile PluginConfig config;
    private volatile Messages messages;
    private volatile RewardConfig rewards;

    private ZoneManager zones;
    private SelectionManager selections;
    private SessionManager sessions;
    private CaptureTask captureTask;
    private NearbyTracker tracker;
    private HologramService holograms;
    private BossBarService bossBars;
    private GlowService glow;
    private Notifier notifier;
    private ClanKothExpansion expansion;
    private RewardService rewardService;
    private KothScheduler scheduler;
    private ClanProvider clans;
    // Every repeating task is referenced so it can be cancelled explicitly (U4.5).
    private BukkitTask captureHandle;
    private BukkitTask schedulerHandle;
    private BukkitTask trackerTask;
    private BukkitTask hologramTask;

    @Override
    public void onEnable() {
        config = PluginConfig.parse(loadOrDefaults("config.yml"), getLogger());
        checkSounds(config);
        messages = Messages.of(loadOrDefaults("messages.yml"));
        rewards = RewardConfig.parse(loadOrDefaults("rewards.yml"), getLogger());

        // First start (or the file was deleted): the bundled zones.yml holds the server's zones.
        if (!new File(getDataFolder(), "zones.yml").exists()) {
            saveResource("zones.yml", false);
        }
        zones = new ZoneManager(getDataFolder(), getLogger());
        zones.load();

        clans = new BoxClansProvider(getLogger());
        selections = new SelectionManager();
        tracker = new NearbyTracker(this, zones);
        sessions = new SessionManager(this, clans);
        notifier = new Notifier(this, tracker);
        rewardService = new RewardService(this, clans);
        expansion = new ClanKothExpansion(this);
        holograms = new HologramService(this, expansion);
        bossBars = new BossBarService(this, expansion);
        glow = new GlowService(this);
        sessions.wire(rewardService, holograms, notifier, bossBars, glow);
        scheduler = new KothScheduler(this);
        captureTask = new CaptureTask(this, sessions, clans);

        // Listeners are registered once here, never per session (U4.6).
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        holograms.registerListeners(this);
        clans.start(this, new ClanEventListener(this));

        KothCommand command = new KothCommand(this);
        PluginCommand koth = getCommand("koth");
        if (koth != null) {
            koth.setExecutor(command);
            koth.setTabCompleter(command);
        }

        expansion.registerAndVerify();
        holograms.createAll();

        for (Player p : getServer().getOnlinePlayers()) {
            clans.refresh(p.getUniqueId());
        }
        scheduler.reload();
        captureHandle = getServer().getScheduler().runTaskTimer(this, captureTask, 1L, 1L);
        schedulerHandle = getServer().getScheduler().runTaskTimer(this, scheduler, 20L, 20L);
        startDisplayTasks();

        DependencyCheck.report(this, clans.describe());
    }

    @Override
    public void onDisable() {
        if (sessions != null) {
            sessions.endAll(SessionManager.EndReason.DISABLE);
        }
        if (holograms != null) {
            holograms.refreshAll();
        }
        if (expansion != null) {
            expansion.safeUnregister();
        }
        if (zones != null) {
            zones.save();
        }
        for (BukkitTask task : new BukkitTask[]{captureHandle, schedulerHandle, trackerTask, hologramTask}) {
            if (task != null) task.cancel();
        }
        if (clans != null) {
            clans.stop();
        }
        getServer().getScheduler().cancelTasks(this);
    }

    /** (Re)starts the tasks whose interval comes from config. */
    private void startDisplayTasks() {
        if (trackerTask != null) trackerTask.cancel();
        if (hologramTask != null) hologramTask.cancel();
        trackerTask = getServer().getScheduler().runTaskTimer(this, tracker, 1L, config.scoreboardCheckInterval);
        hologramTask = getServer().getScheduler().runTaskTimer(this, holograms::refreshAll,
                config.hologramUpdateInterval, config.hologramUpdateInterval);
    }

    /**
     * /koth reload: parse everything into new objects first and swap only if all files parse (§16.5).
     *
     * @return null on success, otherwise the error message (old config kept)
     */
    public String reloadAll() {
        PluginConfig newConfig;
        Messages newMessages;
        RewardConfig newRewards;
        Map<String, Zone> newZones;
        try {
            newConfig = PluginConfig.parse(load("config.yml"), getLogger());
            newMessages = Messages.of(load("messages.yml"));
            newRewards = RewardConfig.parse(load("rewards.yml"), getLogger());
            newZones = zones.read();
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Reload failed, keeping the previous configuration", e);
            return e.getMessage() == null ? e.getClass().getSimpleName() : firstLine(e.getMessage());
        }
        config = newConfig;
        messages = newMessages;
        rewards = newRewards;
        checkSounds(newConfig);
        // Holograms of zones that disappeared from zones.yml are removed; the rest are recreated below.
        for (Zone old : zones.all()) {
            if (!newZones.containsKey(old.name())) {
                holograms.remove(old.name());
            }
        }
        zones.replace(newZones);
        startDisplayTasks();
        scheduler.reload();
        holograms.createAll();
        return null;
    }

    /** Loads a data-folder YAML file with the bundled copy as defaults; throws if it cannot be parsed. */
    private YamlConfiguration load(String name) throws IOException, InvalidConfigurationException {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        yaml.setDefaults(bundled(name));
        return yaml;
    }

    /** Startup variant: a broken file falls back to the bundled defaults so the plugin still enables. */
    private YamlConfiguration loadOrDefaults(String name) {
        try {
            return load(name);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("[" + name + "] cannot be parsed, using built-in defaults: " + firstLine(e.getMessage()));
            return bundled(name);
        }
    }

    private YamlConfiguration bundled(String name) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = getResource(name)) {
            if (in != null) {
                yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.SEVERE, "Bundled " + name + " is invalid", e);
        }
        return yaml;
    }

    /** Warns about sound names that do not exist on this server version (they would play nothing). */
    private void checkSounds(PluginConfig cfg) {
        Sound sound = cfg.captureSound;
        if (sound == null) return;
        try {
            if (Registry.SOUNDS.get(sound.name()) == null) {
                getLogger().warning("[config.yml] unknown capture-sound '" + sound.name().asString()
                        + "'; it will not be heard. See https://minecraft.wiki/w/Sounds.json for valid names.");
            }
        } catch (RuntimeException | LinkageError e) {
            // Registry lookup unavailable: the client simply ignores unknown sounds.
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "unknown error";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    public void debug(String message) {
        if (config.debug) {
            getLogger().info("[debug] " + message);
        }
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public RewardConfig rewards() {
        return rewards;
    }

    public ZoneManager zones() {
        return zones;
    }

    public SelectionManager selections() {
        return selections;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public CaptureTask captureTask() {
        return captureTask;
    }

    public NearbyTracker tracker() {
        return tracker;
    }

    public HologramService holograms() {
        return holograms;
    }

    public BossBarService bossBars() {
        return bossBars;
    }

    public GlowService glow() {
        return glow;
    }

    public RewardService rewardService() {
        return rewardService;
    }

    public ClanProvider clans() {
        return clans;
    }

    public Notifier notifier() {
        return notifier;
    }

    public KothScheduler scheduler() {
        return scheduler;
    }
}
