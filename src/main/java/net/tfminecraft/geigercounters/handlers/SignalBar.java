package net.tfminecraft.geigercounters.handlers;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.utils.Closeness;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// ====================================
// Boss bar shown while a player holds a detector.
//
// It fills as the player closes in and peaks when they face their source, so
// sweeping round on the spot shows which way to walk. It fills on a log
// scale, reaching full at pinpoint range, so it keeps moving on the way in.
// Within pinpoint range it retitles and shows closeness to the cache alone,
// to find where to dig.
// MMOCore owns the action bar, which is why this sits at the top of the screen.
// ====================================
public class SignalBar {

    static final String TITLE = "player.signal-bar";
    static final String PINPOINT_TITLE = "player.signal-bar-close";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build();

    private final GeigerConfiguration config;

    // Players currently shown a bar, and which title it carries
    private final Map<UUID, Shown> bars = new HashMap<>();

    public SignalBar(GeigerConfiguration config) {
        this.config = config;
    }

    // ====================================
    // Show or refresh the player's bar for a cache <distance> blocks away in 3D
    // ====================================
    public void update(Player player, Location source, double distance) {
        show(player, signal(player.getLocation(), source, distance), TITLE);
    }

    // ====================================
    // Show or refresh the close reading for a cache <distance> blocks away in 3D
    // ====================================
    public void pinpoint(Player player, double distance) {
        double range = config.getPinpointRange();
        show(player, 1.0 - Math.min(distance, range) / range, PINPOINT_TITLE);
    }

    private void show(Player player, double signal, String title) {
        if (!config.isBarEnabled()) {
            hide(player.getUniqueId());
            return;
        }

        float progress = (float) signal;
        Shown shown = bars.get(player.getUniqueId());
        if (shown != null) {
            shown.bar.progress(progress);
            if (!shown.title.equals(title)) {
                shown.bar.name(title(title));
                shown.title = title;
            }
            return;
        }

        BossBar bar = BossBar.bossBar(title(title), progress, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        bars.put(player.getUniqueId(), new Shown(bar, title));
        player.showBossBar(bar);
    }

    private Component title(String key) {
        return LEGACY.deserialize(config.getMessages().get(key));
    }

    // ====================================
    // Hide every bar except those of the given players, who are still hunting
    // ====================================
    public void retain(Set<UUID> hunting) {
        Iterator<Map.Entry<UUID, Shown>> iterator = bars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Shown> entry = iterator.next();
            if (hunting.contains(entry.getKey())) {
                continue;
            }
            iterator.remove();
            hideFrom(entry.getKey(), entry.getValue().bar);
        }
    }

    public void clear() {
        retain(Set.of());
    }

    private void hide(UUID playerId) {
        Shown shown = bars.remove(playerId);
        if (shown != null) {
            hideFrom(playerId, shown.bar);
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
        double closeness = Closeness.logarithmic(distance, config.getPinpointRange(), config.getBarRange());
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

    private static final class Shown {
        final BossBar bar;
        String title;

        Shown(BossBar bar, String title) {
            this.bar = bar;
            this.title = title;
        }
    }
}
