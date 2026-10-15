package net.tfminecraft.geigercounters.handlers;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// ====================================
// Boss bar shown while a player holds a detector.
//
// It fills as the player closes in and peaks when they face their source, so
// sweeping round on the spot shows which way to walk. MMOCore owns the action
// bar, which is why this sits at the top of the screen.
// ====================================
public class SignalBar {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build();

    private final GeigerConfiguration config;

    // Players currently shown a bar
    private final Map<UUID, BossBar> bars = new HashMap<>();

    public SignalBar(GeigerConfiguration config) {
        this.config = config;
    }

    // ====================================
    // Show or refresh the player's bar for a source <distance> blocks away
    // ====================================
    public void update(Player player, Location source, double distance) {
        if (!config.isBarEnabled()) {
            hide(player.getUniqueId());
            return;
        }

        float progress = (float) signal(player.getLocation(), source, distance);
        BossBar bar = bars.get(player.getUniqueId());
        if (bar != null) {
            bar.progress(progress);
            return;
        }

        bar = BossBar.bossBar(LEGACY.deserialize(config.getMessages().get("player.signal-bar")), progress,
            BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        bars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);
    }

    // ====================================
    // Hide every bar except those of the given players, who are still hunting
    // ====================================
    public void retain(Set<UUID> hunting) {
        Iterator<Map.Entry<UUID, BossBar>> iterator = bars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BossBar> entry = iterator.next();
            if (hunting.contains(entry.getKey())) {
                continue;
            }
            iterator.remove();
            hideFrom(entry.getKey(), entry.getValue());
        }
    }

    public void clear() {
        retain(Set.of());
    }

    private void hide(UUID playerId) {
        BossBar bar = bars.remove(playerId);
        if (bar != null) {
            hideFrom(playerId, bar);
        }
    }

    private static void hideFrom(UUID playerId, BossBar bar) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.hideBossBar(bar);
        }
    }

    // ====================================
    // 0..1: closeness scaled by how squarely the player faces the source.
    // Facing away still shows (1 - facing-weight) of the closeness.
    // ====================================
    double signal(Location from, Location source, double distance) {
        double range = config.getBarRange();
        double closeness = 1.0 - Math.min(distance, range) / range;
        double weight = config.getBarFacingWeight();
        return closeness * ((1.0 - weight) + weight * facing(from, source));
    }

    // ====================================
    // 1 looking straight at the source, 0 facing directly away. Only yaw counts.
    // Squared so the peak is narrow enough to steer by.
    // ====================================
    static double facing(Location from, Location source) {
        double deltaX = source.getX() - from.getX();
        double deltaZ = source.getZ() - from.getZ();
        double length = Math.hypot(deltaX, deltaZ);
        if (length < 1.0e-6) {
            return 1.0;
        }

        // Minecraft yaw: 0 faces +Z, 90 faces -X
        double yaw = Math.toRadians(from.getYaw());
        double cosine = (-Math.sin(yaw) * deltaX + Math.cos(yaw) * deltaZ) / length;
        double half = (cosine + 1.0) / 2.0;
        return half * half;
    }
}
