package com.dex.boxkoth.zone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneTest {

    // Blocks 0..9 on X and Z, 64..70 on Y  =>  continuous [0,10) x [64,71) x [0,10)
    private final Zone zone = Zone.of("campus", "world", 9, 70, 9, 0, 64, 0);

    @Test
    void cornersAreNormalised() {
        assertEquals(0, zone.minX());
        assertEquals(64, zone.minY());
        assertEquals(9, zone.maxX());
        assertEquals(70, zone.maxY());
    }

    @Test
    void containsUsesHalfOpenBlockExtent() {
        assertTrue(zone.contains(0.0, 64.0, 0.0));
        assertTrue(zone.contains(9.999, 70.999, 9.999));
        assertTrue(zone.contains(5.5, 65, 5.5));
        assertFalse(zone.contains(10.0, 65, 5));   // max + 1 is outside
        assertFalse(zone.contains(-0.001, 65, 5));
        assertFalse(zone.contains(5, 71.0, 5));
        assertFalse(zone.contains(5, 63.999, 5));
        assertFalse(zone.contains(5, 65, 10.0));
    }

    @Test
    void centerIncludesTheLastBlock() {
        assertEquals(5.0, zone.centerX());
        assertEquals(5.0, zone.centerZ());
        assertArrayEquals(new double[]{5.0, 73.0, 5.0}, zone.defaultHologramPosition(2.0));
    }

    @Test
    void singleBlockCenter() {
        Zone one = Zone.of("one", "world", 3, 10, -4, 3, 10, -4);
        assertEquals(3.5, one.centerX());
        assertEquals(-3.5, one.centerZ());
    }

    @Test
    void customHologramOverridesDefault() {
        Zone moved = zone.withHologram(new double[]{1, 2, 3});
        assertArrayEquals(new double[]{1, 2, 3}, moved.hologramPosition(2.0));
        assertArrayEquals(new double[]{5.0, 73.0, 5.0}, moved.withHologram(null).hologramPosition(2.0));
    }

    @Test
    void infoHologramIsIndependentOfRanking() {
        Zone moved = zone.withHologram(new double[]{1, 2, 3}).withInfoHologram(new double[]{7, 8, 9});
        assertArrayEquals(new double[]{1, 2, 3}, moved.hologramPosition(2.0));
        assertArrayEquals(new double[]{7, 8, 9}, moved.infoHologramPosition(2.0));
        Zone reset = moved.withInfoHologram(null);
        assertArrayEquals(new double[]{1, 2, 3}, reset.hologramPosition(2.0));
        assertArrayEquals(new double[]{5.0, 73.0, 5.0}, reset.infoHologramPosition(2.0));
    }

    @Test
    void distanceInsideIsZero() {
        assertEquals(0.0, zone.distanceSquared(5, 66, 5));
        assertEquals(0.0, zone.distanceSquared(0, 64, 0));
    }

    @Test
    void distanceToEachFace() {
        assertEquals(9.0, zone.distanceSquared(-3, 66, 5), 1e-9);  // -X
        assertEquals(9.0, zone.distanceSquared(13, 66, 5), 1e-9);  // +X (hi = 10)
        assertEquals(4.0, zone.distanceSquared(5, 62, 5), 1e-9);   // -Y
        assertEquals(4.0, zone.distanceSquared(5, 73, 5), 1e-9);   // +Y (hi = 71)
        assertEquals(1.0, zone.distanceSquared(5, 66, -1), 1e-9);  // -Z
        assertEquals(1.0, zone.distanceSquared(5, 66, 11), 1e-9);  // +Z (hi = 10)
    }

    @Test
    void distanceToCorner() {
        // nearest point is (10, 71, 10)
        assertEquals(1 + 4 + 9, zone.distanceSquared(11, 73, 13), 1e-9);
        // nearest point is (0, 64, 0)
        assertEquals(1 + 1 + 1, zone.distanceSquared(-1, 63, -1), 1e-9);
    }

    @Test
    void nameValidation() {
        assertTrue(Zone.isValidName("campus_1"));
        assertFalse(Zone.isValidName("Campus"));
        assertFalse(Zone.isValidName("a:b"));
        assertFalse(Zone.isValidName(""));
        assertFalse(Zone.isValidName("a".repeat(33)));
    }
}
