package net.tfminecraft.geigercounters.managers;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ====================================
// Per-player rate limit on source collections.
//
// A player may collect at most <drops> sources within any <time> window.
// The window slides: each collection is stamped, stamps older than the window
// are dropped, and the limit is hit while the remaining stamps reach <drops>.
// The oldest stamp therefore decides when the next slot frees up.
//
// Collections are stored on disk so a restart cannot be used to wipe the limit.
// ====================================
public class DropLimitManager {

    private static final String DATA_FILE_NAME = "drop-limits.yml";
    private static final String COLLECTIONS_PATH = "collections";
    private static final String BYPASS_PERMISSION = "geiger.limit.bypass";

    private final JavaPlugin plugin;
    private final GeigerConfiguration config;
    private final File dataFile;

    // UUID -> collection timestamps (epoch millis), oldest first
    private final Map<UUID, Deque<Long>> collections = new HashMap<>();

    public DropLimitManager(JavaPlugin plugin, GeigerConfiguration config) {
        this.plugin = plugin;
        this.config = config;
        this.dataFile = new File(plugin.getDataFolder(), DATA_FILE_NAME);
    }

    // ====================================
    // Whether the player is allowed to collect a source right now
    // ====================================
    public boolean canCollect(Player player) {
        if (!config.isLimitEnabled() || player.hasPermission(BYPASS_PERMISSION)) {
            return true;
        }
        return getRemaining(player.getUniqueId()) > 0;
    }

    // ====================================
    // Stamp a collection against the player's limit
    // No-op when limiting is off or the player bypasses it, so a bypassing
    // player never builds up a history that would bite them later
    // ====================================
    public void recordCollection(Player player) {
        if (!config.isLimitEnabled() || player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }

        Deque<Long> stamps = collections.computeIfAbsent(player.getUniqueId(), key -> new ArrayDeque<>());
        prune(stamps);
        stamps.addLast(System.currentTimeMillis());
        save();
    }

    // ====================================
    // Collections the player has left in the current window
    // ====================================
    public int getRemaining(UUID playerId) {
        if (!config.isLimitEnabled()) {
            return config.getLimitDrops();
        }

        Deque<Long> stamps = collections.get(playerId);
        if (stamps == null) {
            return config.getLimitDrops();
        }

        prune(stamps);
        return Math.max(0, config.getLimitDrops() - stamps.size());
    }

    // ====================================
    // Millis until the next collection slot frees up, or 0 if one is free.
    // That is the oldest stamp in the window plus the window length.
    // ====================================
    public long getMillisUntilNextDrop(UUID playerId) {
        if (getRemaining(playerId) > 0) {
            return 0L;
        }

        Deque<Long> stamps = collections.get(playerId);
        if (stamps == null || stamps.isEmpty()) {
            return 0L;
        }

        long readyAt = stamps.peekFirst() + config.getLimitWindowMillis();
        return Math.max(0L, readyAt - System.currentTimeMillis());
    }

    // ====================================
    // Clear a player's history, freeing every slot immediately
    // ====================================
    public void reset(UUID playerId) {
        if (collections.remove(playerId) != null) {
            save();
        }
    }

    // Drop stamps that have aged out of the window
    private void prune(Deque<Long> stamps) {
        long cutoff = System.currentTimeMillis() - config.getLimitWindowMillis();
        while (!stamps.isEmpty() && stamps.peekFirst() <= cutoff) {
            stamps.removeFirst();
        }
    }

    // ===== PERSISTENCE =====

    public void load() {
        collections.clear();
        if (!dataFile.exists()) {
            return;
        }

        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        if (!data.isConfigurationSection(COLLECTIONS_PATH)) {
            return;
        }

        for (String key : data.getConfigurationSection(COLLECTIONS_PATH).getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid UUID in " + DATA_FILE_NAME + ": " + key);
                continue;
            }

            List<Long> stamps = new ArrayList<>(data.getLongList(COLLECTIONS_PATH + "." + key));
            if (stamps.isEmpty()) {
                continue;
            }

            Collections.sort(stamps);
            collections.put(playerId, new ArrayDeque<>(stamps));
        }
    }

    // ====================================
    // Write the current histories to disk.
    // Called after every collection - the file only holds stamps inside the
    // window, so it stays small no matter how long the server runs.
    // ====================================
    public void save() {
        YamlConfiguration data = new YamlConfiguration();

        for (Map.Entry<UUID, Deque<Long>> entry : collections.entrySet()) {
            Deque<Long> stamps = entry.getValue();
            prune(stamps);
            if (stamps.isEmpty()) {
                continue;
            }
            data.set(COLLECTIONS_PATH + "." + entry.getKey(), new ArrayList<>(stamps));
        }

        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save " + DATA_FILE_NAME + ": " + e.getMessage());
        }
    }
}
