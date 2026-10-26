package com.dex.boxkoth.zone;

import com.dex.boxkoth.util.AtomicYaml;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Zone CRUD and zones.yml persistence. Mutations happen on the main thread; the map is copy-on-write
 * behind a volatile field so async placeholder requests can read it safely.
 */
public final class ZoneManager {

    private final File file;
    private final Logger logger;
    private volatile Map<String, Zone> zones = Collections.emptyMap();
    private volatile boolean locked;

    public ZoneManager(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "zones.yml");
        this.logger = logger;
    }

    /**
     * Startup load. If zones.yml cannot be parsed, no zones are loaded and saving is LOCKED so the broken file
     * is never overwritten with an empty one; fix it and run /koth reload.
     */
    public void load() {
        try {
            replace(read());
        } catch (IOException | InvalidConfigurationException e) {
            zones = Collections.emptyMap();
            locked = true;
            logger.log(Level.SEVERE, "[zones.yml] cannot be parsed; no zones loaded and zones.yml will NOT be "
                    + "written until it is fixed (then run /koth reload): " + e.getMessage());
        }
    }

    /** Parses zones.yml without touching the current zones. Invalid entries are skipped with a warning. */
    public Map<String, Zone> read() throws IOException, InvalidConfigurationException {
        Map<String, Zone> loaded = new TreeMap<>();
        if (!file.exists()) return loaded;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        ConfigurationSection root = yaml.getConfigurationSection("zones");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                Zone zone = parse(name, root.getConfigurationSection(name));
                if (zone != null) {
                    loaded.put(name, zone);
                }
            }
        }
        return loaded;
    }

    /** Installs a freshly read zone set (startup or /koth reload) and unlocks saving. */
    public void replace(Map<String, Zone> loaded) {
        zones = Collections.unmodifiableMap(new TreeMap<>(loaded));
        locked = false;
        logger.info("Loaded " + loaded.size() + " zone(s).");
    }

    /** True when zones.yml failed to parse: zone changes are refused so the file is not overwritten. */
    public boolean isLocked() {
        return locked;
    }

    private Zone parse(String name, ConfigurationSection s) {
        if (!Zone.isValidName(name) || s == null) {
            logger.warning("[zones.yml] zones." + name + ": invalid zone name or section, skipped.");
            return null;
        }
        String world = s.getString("world");
        List<Integer> min = s.getIntegerList("min");
        List<Integer> max = s.getIntegerList("max");
        if (world == null || world.isBlank() || min.size() != 3 || max.size() != 3) {
            logger.warning("[zones.yml] zones." + name + ": needs 'world', 'min: [x,y,z]' and 'max: [x,y,z]', skipped.");
            return null;
        }
        Zone zone = Zone.of(name, world, min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2));
        if (s.isInt("arena-radius")) {
            int radius = s.getInt("arena-radius");
            if (radius >= 0) {
                zone = zone.withArenaRadius(radius);
            } else {
                logger.warning("[zones.yml] zones." + name + ".arena-radius: " + radius + " must be >= 0, using default.");
            }
        }
        if (s.isInt("duration-seconds")) {
            int duration = s.getInt("duration-seconds");
            if (duration > 0) {
                zone = zone.withDurationSeconds(duration);
            } else {
                logger.warning("[zones.yml] zones." + name + ".duration-seconds: " + duration + " must be > 0, using default.");
            }
        }
        List<Double> holo = s.getDoubleList("hologram");
        if (holo.size() == 3) {
            zone = zone.withHologram(new double[]{holo.get(0), holo.get(1), holo.get(2)});
        }
        List<Double> info = s.getDoubleList("info-hologram");
        if (info.size() == 3) {
            zone = zone.withInfoHologram(new double[]{info.get(0), info.get(1), info.get(2)});
        }
        return zone;
    }

    /** Writes zones.yml atomically. Returns false (and logs) on failure. */
    public boolean save() {
        if (locked) {
            logger.warning("zones.yml is locked (it failed to parse); not saving. Fix the file and run /koth reload.");
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("zones");
        for (Zone zone : zones.values()) {
            ConfigurationSection s = root.createSection(zone.name());
            s.set("world", zone.world());
            s.set("min", List.of(zone.minX(), zone.minY(), zone.minZ()));
            s.set("max", List.of(zone.maxX(), zone.maxY(), zone.maxZ()));
            if (zone.arenaRadius() != null) s.set("arena-radius", zone.arenaRadius());
            if (zone.durationSeconds() != null) s.set("duration-seconds", zone.durationSeconds());
            double[] holo = zone.customHologram();
            s.set("hologram", holo == null ? null : List.of(holo[0], holo[1], holo[2]));
            double[] info = zone.customInfoHologram();
            s.set("info-hologram", info == null ? null : List.of(info[0], info[1], info[2]));
        }
        try {
            AtomicYaml.save(yaml, file);
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save zones.yml", e);
            return false;
        }
    }

    public Zone get(String name) {
        return name == null ? null : zones.get(name);
    }

    public Collection<Zone> all() {
        return zones.values();
    }

    public boolean exists(String name) {
        return zones.containsKey(name);
    }

    /** Adds or replaces a zone and persists. */
    public void put(Zone zone) {
        Map<String, Zone> copy = new TreeMap<>(zones);
        copy.put(zone.name(), zone);
        zones = Collections.unmodifiableMap(copy);
        save();
    }

    public void remove(String name) {
        Map<String, Zone> copy = new TreeMap<>(zones);
        if (copy.remove(name) != null) {
            zones = Collections.unmodifiableMap(copy);
            save();
        }
    }
}
