package com.dex.boxkoth.zone;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** pos1 / pos2 per admin, in memory only. Stores world names and block coordinates, never live objects. */
public final class SelectionManager {

    public record Corner(String world, int x, int y, int z) {
    }

    private final Map<UUID, Corner> pos1 = new HashMap<>();
    private final Map<UUID, Corner> pos2 = new HashMap<>();

    /** Store the block the player's feet are in, so a flat zone always contains players standing on its floor. */
    public Corner set(UUID admin, int index, String world, double x, double y, double z) {
        Corner corner = new Corner(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        (index == 1 ? pos1 : pos2).put(admin, corner);
        return corner;
    }

    public Corner get(UUID admin, int index) {
        return (index == 1 ? pos1 : pos2).get(admin);
    }

    public void clear(UUID admin) {
        pos1.remove(admin);
        pos2.remove(admin);
    }
}
