package com.dex.boxkoth.zone;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable axis-aligned KOTH zone (§3). Block corners are normalised; a block at integer x occupies
 * [x, x+1), so the continuous extent on each axis is [min, max + 1).
 * <p>
 * No Bukkit types, scheduler or I/O here so it can be unit-tested.
 */
public final class Zone {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    private final String name;
    private final String world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final Integer arenaRadius;
    private final Integer durationSeconds;
    private final double[] hologram;
    private final double[] infoHologram;

    private Zone(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                 Integer arenaRadius, Integer durationSeconds, double[] hologram, double[] infoHologram) {
        this.name = Objects.requireNonNull(name, "name");
        this.world = Objects.requireNonNull(world, "world");
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.arenaRadius = arenaRadius;
        this.durationSeconds = durationSeconds;
        this.hologram = hologram == null ? null : hologram.clone();
        this.infoHologram = infoHologram == null ? null : infoHologram.clone();
    }

    public static Zone of(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Zone(name, world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2),
                null, null, null, null);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    public Zone withArenaRadius(Integer radius) {
        return new Zone(name, world, minX, minY, minZ, maxX, maxY, maxZ, radius, durationSeconds, hologram, infoHologram);
    }

    public Zone withDurationSeconds(Integer seconds) {
        return new Zone(name, world, minX, minY, minZ, maxX, maxY, maxZ, arenaRadius, seconds, hologram, infoHologram);
    }

    /** Ranking hologram. @param position {x, y, z}, or null to use the default centre. */
    public Zone withHologram(double[] position) {
        checkPosition(position);
        return new Zone(name, world, minX, minY, minZ, maxX, maxY, maxZ, arenaRadius, durationSeconds, position, infoHologram);
    }

    /** Idle info hologram. @param position {x, y, z}, or null to use the default centre. */
    public Zone withInfoHologram(double[] position) {
        checkPosition(position);
        return new Zone(name, world, minX, minY, minZ, maxX, maxY, maxZ, arenaRadius, durationSeconds, hologram, position);
    }

    private static void checkPosition(double[] position) {
        if (position != null && position.length != 3) {
            throw new IllegalArgumentException("hologram position needs 3 coordinates");
        }
    }

    /** §3.1: min <= p < max + 1 on every axis. */
    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX + 1
                && y >= minY && y < maxY + 1
                && z >= minZ && z < maxZ + 1;
    }

    /** §3.3: squared distance from the point to the nearest point of the box (0 inside). */
    public double distanceSquared(double x, double y, double z) {
        double dx = axisDistance(x, minX, maxX + 1);
        double dy = axisDistance(y, minY, maxY + 1);
        double dz = axisDistance(z, minZ, maxZ + 1);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double p, double lo, double hi) {
        return Math.max(Math.max(lo - p, 0.0), p - hi);
    }

    public double centerX() {
        return (minX + maxX + 1) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ + 1) / 2.0;
    }

    /** §3.2: centre on X/Z, above the roof on Y. */
    public double[] defaultHologramPosition(double offsetY) {
        return new double[]{centerX(), maxY + 1 + offsetY, centerZ()};
    }

    /** Stored ranking hologram position if set, otherwise the default. */
    public double[] hologramPosition(double offsetY) {
        return hologram != null ? hologram.clone() : defaultHologramPosition(offsetY);
    }

    /** Stored info hologram position if set, otherwise the default. */
    public double[] infoHologramPosition(double offsetY) {
        return infoHologram != null ? infoHologram.clone() : defaultHologramPosition(offsetY);
    }

    public String name() {
        return name;
    }

    public String world() {
        return world;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /** Per-zone override, or null to use the config default. */
    public Integer arenaRadius() {
        return arenaRadius;
    }

    /** Per-zone override, or null to use the config default. */
    public Integer durationSeconds() {
        return durationSeconds;
    }

    /** Custom ranking hologram position, or null when the default centre is used. */
    public double[] customHologram() {
        return hologram == null ? null : hologram.clone();
    }

    /** Custom info hologram position, or null when the default centre is used. */
    public double[] customInfoHologram() {
        return infoHologram == null ? null : infoHologram.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Zone z)) return false;
        return minX == z.minX && minY == z.minY && minZ == z.minZ
                && maxX == z.maxX && maxY == z.maxY && maxZ == z.maxZ
                && name.equals(z.name) && world.equals(z.world)
                && Objects.equals(arenaRadius, z.arenaRadius)
                && Objects.equals(durationSeconds, z.durationSeconds)
                && Arrays.equals(hologram, z.hologram)
                && Arrays.equals(infoHologram, z.infoHologram);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public String toString() {
        return name + "[" + world + " " + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
