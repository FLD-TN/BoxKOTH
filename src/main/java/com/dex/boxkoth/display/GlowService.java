package com.dex.boxkoth.display;

import com.dex.boxkoth.ClanKothPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The current controller of each zone glows (config: controller-glow), switched in the same tick the controller
 * changes. Only the glowing outline is touched: no teams, so the colour is the default white and nametags are
 * left alone. Main thread only.
 * <p>
 * Glowing is saved in the player file, so a marker is stored next to it: a glow left behind by a crash is removed
 * on the next join. A player who already glowed for another reason keeps that glow afterwards.
 */
public final class GlowService {

    private record Glow(UUID player, boolean hadGlow) {
    }

    private final ClanKothPlugin plugin;
    private final NamespacedKey marker;
    /** Zone -> glowing controller. */
    private final Map<String, Glow> glowing = new HashMap<>();

    public GlowService(ClanKothPlugin plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "controller_glow");
    }

    /** Controller of {@code zone} is now {@code controller} (null = nobody). */
    public void update(String zone, UUID controller) {
        Glow current = glowing.get(zone);
        if (current != null && current.player().equals(controller)) return;
        if (current != null) {
            glowing.remove(zone);
            Player old = Bukkit.getPlayer(current.player());
            if (old != null) off(old, current);
        }
        if (controller == null || !plugin.config().controllerGlow) return;
        Player p = Bukkit.getPlayer(controller);
        if (p != null) glowing.put(zone, on(p));
    }

    /** Session ended. */
    public void clear(String zone) {
        update(zone, null);
    }

    /** Before the player file is saved, so the glow is not written to it. */
    public void onQuit(Player player) {
        glowing.entrySet().removeIf(e -> {
            if (!e.getValue().player().equals(player.getUniqueId())) return false;
            off(player, e.getValue());
            return true;
        });
    }

    /** Removes a glow left behind by a crash. */
    public void onJoin(Player player) {
        if (player.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().remove(marker);
            player.setGlowing(false);
        }
    }

    private Glow on(Player p) {
        boolean hadGlow = p.isGlowing();
        if (!hadGlow) {
            p.setGlowing(true);
            p.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        }
        return new Glow(p.getUniqueId(), hadGlow);
    }

    private void off(Player p, Glow glow) {
        if (glow.hadGlow()) return;
        p.setGlowing(false);
        p.getPersistentDataContainer().remove(marker);
    }
}
