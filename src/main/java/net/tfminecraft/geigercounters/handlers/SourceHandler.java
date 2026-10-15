package net.tfminecraft.geigercounters.handlers;

import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.events.GeigerSourceCollectEvent;
import net.tfminecraft.geigercounters.managers.DropLimitManager;
import net.tfminecraft.geigercounters.metrics.UsageStats;
import net.tfminecraft.geigercounters.models.ItemReward;
import net.tfminecraft.geigercounters.models.TierReward;
import net.tfminecraft.geigercounters.utils.Utils;
import net.tfminecraft.geigercounters.validators.SpawnLocationFilter;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// ====================================
// Each hunter's radioactive source: placement, persistence and collection.
// A hunter is the character being played (the account without RPCharacters).
// ====================================
public class SourceHandler {
    
    private static final String DEAD_GEIGER_PATH = "m.TOOLS.DEAD_GEIGER_COUNTER";

    // Collection is checked every few ticks, so the limit message needs its own
    // cooldown or a player standing on the source would be spammed
    private static final long LIMIT_MESSAGE_COOLDOWN_MILLIS = 15_000L;

    private static final String DATA_FILE_NAME = "sources.yml";
    private static final String SOURCES_PATH = "sources";

    // Sources of characters who stop hunting are dropped on load after this long
    private static final long SOURCE_EXPIRY_MILLIS = TimeUnit.DAYS.toMillis(30);
    
    private final JavaPlugin plugin;
    private final GeigerConfiguration config;
    private final ItemAPI api;
    private final SpawnLocationFilter spawnFilter;
    private final DropLimitManager dropLimits;
    private final File dataFile;
    private final Random random = new Random();

    // Last time each player was told they are rate limited
    private final Map<UUID, Long> lastLimitMessage = new HashMap<>();

    // Hunter -> their source
    private final Map<UUID, Source> sources = new HashMap<>();

    // Hunter -> placement still resolving chunk loads
    private final Map<UUID, CompletableFuture<Location>> pending = new HashMap<>();

    public SourceHandler(JavaPlugin plugin, GeigerConfiguration config, ItemAPI api,
                         SpawnLocationFilter spawnFilter, DropLimitManager dropLimits) {
        this.plugin = plugin;
        this.config = config;
        this.api = api;
        this.spawnFilter = spawnFilter;
        this.dropLimits = dropLimits;
        this.dataFile = new File(plugin.getDataFolder(), DATA_FILE_NAME);
    }
    
    // ====================================
    // The hunter's source, or null if they have none
    // ====================================
    public Location getSource(UUID hunter) {
        Source source = sources.get(hunter);
        return source == null ? null : source.location();
    }

    // Whether a placement for this hunter is still resolving
    public boolean isPlacing(UUID hunter) {
        return pending.containsKey(hunter);
    }

    // ====================================
    // Place the hunter's source at a random spot near <origin>
    //
    // Candidates sit between source.min-distance and source.max-distance from
    // the origin, inside the configured area, and are rolled until one passes
    // the spawn filters (liquid, void, blocked blocks, distance from spawn,
    // WorldGuard regions). Every candidate needs its chunk loaded to read the
    // terrain, so attempts run one at a time through getChunkAtAsync instead
    // of stalling the main thread.
    //
    // If no candidate passes within the attempt budget the last one is used
    // anyway, so the hunter always gets a source.
    //
    // The returned future completes on the main thread with the chosen location.
    // ====================================
    public CompletableFuture<Location> placeNear(UUID hunter, Location origin) {
        // Collapse overlapping requests onto the search already running
        CompletableFuture<Location> running = pending.get(hunter);
        if (running != null) {
            return running;
        }

        // Clear the old source for the duration of the search so it cannot be
        // collected while attempts are still resolving
        sources.remove(hunter);

        CompletableFuture<Location> result = new CompletableFuture<>();
        pending.put(hunter, result);
        attemptPlacement(hunter, origin.getX(), origin.getZ(), 1, result);
        return result;
    }

    private void attemptPlacement(UUID hunter, double originX, double originZ, int attempt,
                                  CompletableFuture<Location> result) {
        double angle = random.nextDouble() * 2.0 * Math.PI;
        double min = config.getMinSourceDistance();
        double max = config.getMaxSourceDistance();
        // Uniform over the ring's area, so sources do not bunch at the inner edge
        double radius = Math.sqrt(min * min + random.nextDouble() * (max * max - min * min));
        double x = clamp(originX + radius * Math.cos(angle), config.getMinX(), config.getMaxX());
        double z = clamp(originZ + radius * Math.sin(angle), config.getMinZ(), config.getMaxZ());
        int maxAttempts = config.getSpawnFilters().getMaxAttempts();

        loadChunkFor(x, z).whenComplete((chunk, error) -> {
            // Paper completes chunk futures on the main thread, so everything
            // below is safe to run against the world directly
            if (pending.get(hunter) != result || !plugin.isEnabled()) {
                result.complete(null);
                return;
            }
            if (error != null) {
                plugin.getLogger().warning("Failed to load chunk for candidate source location: " + error.getMessage());
            }

            Location candidate = toSurfaceLocation(x, z);
            SpawnLocationFilter.Rejection rejection = spawnFilter.check(candidate);

            if (rejection == null) {
                applySource(hunter, candidate, attempt, result);
                return;
            }

            if (attempt >= maxAttempts) {
                plugin.getLogger().warning(String.format(
                    "No valid source location found after %d attempts (last rejection: %s). "
                        + "Using the last candidate - check your source.spawn-filters settings.",
                    maxAttempts, rejection.getDescription()));
                applySource(hunter, candidate, attempt, result);
                return;
            }

            attemptPlacement(hunter, originX, originZ, attempt + 1, result);
        });
    }

    // ====================================
    // Move the hunter's source to specific X/Z coordinates (Y snaps to surface)
    // Admin-driven moves bypass the spawn filters on purpose, but a failed
    // filter check is logged so the admin knows the spot is normally excluded
    // ====================================
    public CompletableFuture<Location> placeAt(UUID hunter, double x, double z) {
        sources.remove(hunter);
        CompletableFuture<Location> result = new CompletableFuture<>();
        pending.put(hunter, result);

        loadChunkFor(x, z).whenComplete((chunk, error) -> {
            if (pending.get(hunter) != result || !plugin.isEnabled()) {
                result.complete(null);
                return;
            }
            if (error != null) {
                plugin.getLogger().warning("Failed to load chunk for the requested source location: " + error.getMessage());
            }

            Location location = toSurfaceLocation(x, z);

            SpawnLocationFilter.Rejection rejection = spawnFilter.check(location);
            if (rejection != null) {
                plugin.getLogger().warning(String.format(
                    "Source manually moved to a location that fails the spawn filters (%s).",
                    rejection.getDescription()));
            }

            applySource(hunter, location, 1, result);
        });

        return result;
    }

    // ====================================
    // Forget the hunter's source; a new one is placed when they next hunt
    // ====================================
    public void clear(UUID hunter) {
        pending.remove(hunter);
        if (sources.remove(hunter) != null) {
            save();
        }
    }

    // Loads (generating if needed) the chunk containing the given block coords
    private CompletableFuture<Chunk> loadChunkFor(double x, double z) {
        return config.getWorld().getChunkAtAsync(blockCoord(x) >> 4, blockCoord(z) >> 4, true);
    }

    // Y snaps to one block above the highest block in the column
    private Location toSurfaceLocation(double x, double z) {
        double surfaceY = config.getWorld().getHighestBlockYAt(blockCoord(x), blockCoord(z)) + 1.0;
        return new Location(config.getWorld(), x, surfaceY, z);
    }

    // floor, not truncation - casting rounds toward zero and lands one block
    // off for negative coordinates
    private static int blockCoord(double value) {
        return (int) Math.floor(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private void applySource(UUID hunter, Location location, int attempts, CompletableFuture<Location> result) {
        pending.remove(hunter);
        sources.put(hunter, new Source(location, System.currentTimeMillis()));
        save();

        plugin.getLogger().info(String.format("Source for %s placed at X = %.1f Z = %.1f (%d attempt%s)",
            hunter, location.getX(), location.getZ(), attempts, attempts == 1 ? "" : "s"));
        result.complete(location);
    }

    // ====================================
    // Check if the player is close enough to collect their source
    // ====================================
    public void tryCollectSource(Player player, UUID hunter, double distance, EquipmentSlot geigerSlot) {
        if (distance > config.getCollectionDistance()) {
            return;
        }

        Source source = sources.get(hunter);
        if (source == null) {
            return;
        }

        // Out of collections for this window - the source stays put for later
        if (!dropLimits.canCollect(player)) {
            notifyLimitReached(player);
            return;
        }

        collectSource(player, hunter, source.location(), geigerSlot);
    }

    // ====================================
    // Tell the player when their next collection slot opens up
    // ====================================
    private void notifyLimitReached(Player player) {
        long now = System.currentTimeMillis();
        Long lastSent = lastLimitMessage.get(player.getUniqueId());
        if (lastSent != null && now - lastSent < LIMIT_MESSAGE_COOLDOWN_MILLIS) {
            return;
        }
        lastLimitMessage.put(player.getUniqueId(), now);

        long waitMillis = dropLimits.getMillisUntilNextDrop(player.getUniqueId());

        player.sendMessage(config.getMessages().get("player.limit-reached",
            "%max%", config.getLimitDrops(),
            "%window%", Utils.formatDuration(config.getLimitWindowMillis()),
            "%time%", Utils.formatDuration(waitMillis)));
    }

    private void collectSource(Player player, UUID hunter, Location collected, EquipmentSlot geigerSlot) {
        // No new source yet - the next hold of a live detector places one near the hunter
        clear(hunter);

        dropLimits.recordCollection(player);
        UsageStats.getInstance().recordSourceCollected();
        Bukkit.getPluginManager().callEvent(new GeigerSourceCollectEvent(player, collected));
        notifyPlayerOfCollection(player);
        replaceGeigerWithDeadVersion(player, geigerSlot);
        giveReward(player);
    }

    private void notifyPlayerOfCollection(Player player) {
        player.sendMessage(config.getMessages().get("player.found-source"));
    }

    private void replaceGeigerWithDeadVersion(Player player, EquipmentSlot geigerSlot) {
        // Consume one counter, preserving any others stacked in the same hand.
        boolean offHand = geigerSlot == EquipmentSlot.OFF_HAND;
        ItemStack active = offHand ? player.getInventory().getItemInOffHand()
            : player.getInventory().getItemInMainHand();
        ItemStack remaining = null;
        if (active.getAmount() > 1) {
            remaining = active.clone();
            remaining.setAmount(active.getAmount() - 1);
        }
        if (offHand) {
            player.getInventory().setItemInOffHand(remaining);
        } else {
            player.getInventory().setItemInMainHand(remaining);
        }

        // Give dead Geiger Counter
        try {
            ItemStack deadGeiger = api.getCreator().getItemFromPath(DEAD_GEIGER_PATH).clone();
            deadGeiger.setAmount(1);
            giveItemOrDropLeftovers(player, deadGeiger);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            player.sendMessage(config.getMessages().get("player.dead-geiger"));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to give dead Geiger Counter to " + player.getName() + ": " + e.getMessage());
        }
    }
    
    // ====================================
    // Give a random reward to the player based on tier weights
    // ====================================
    private void giveReward(Player player) {
        List<TierReward> tiers = config.getTierRewards();
        if (tiers.isEmpty()) {
            return;
        }
        
        // Select a tier based on weights
        TierReward selectedTier = selectRandomTier(tiers);
        if (selectedTier.isEmpty()) {
            return;
        }
        
        // Select a random item from the tier
        List<ItemReward> tierItems = selectedTier.getItems();
        ItemReward randomItem = tierItems.get(random.nextInt(tierItems.size()));
        
        // Give the item to the player
        giveRewardItem(player, randomItem, selectedTier.getTierName());
    }
    
    // ====================================
    // Select a random tier based on weights
    // Uses cumulative weight distribution
    // ====================================
    private TierReward selectRandomTier(List<TierReward> tiers) {
        // Calculate total weight
        double totalWeight = 0.0;
        for (TierReward tier : tiers) {
            totalWeight += tier.getWeight();
        }
        
        // Generate random value between 0 and total weight
        double randomValue = random.nextDouble() * totalWeight;
        
        // Find which tier this value falls into
        double cumulativeWeight = 0.0;
        for (int index = 0; index < tiers.size() - 1; index++) {
            TierReward tier = tiers.get(index);
            cumulativeWeight += tier.getWeight();
            if (randomValue <= cumulativeWeight) {
                return tier;
            }
        }

        // Every remaining draw selects the final tier, including rounding fallback.
        return tiers.getLast();
    }
    
    // ====================================
    // Give a specific reward item to the player
    // ====================================
    private void giveRewardItem(Player player, ItemReward reward, String tierName) {
        try {
            ItemStack rewardItem = createRewardItem(reward);
            giveItemOrDropLeftovers(player, rewardItem);
            plugin.getLogger().info(player.getName() + " received " + tierName + " reward: " + reward.getOutputItem());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to give reward to " + player.getName() + ": " + e.getMessage());
        }
    }
    
    private void giveItemOrDropLeftovers(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    // ====================================
    // Create an ItemStack for the reward
    // ====================================
    private ItemStack createRewardItem(ItemReward reward) {
        String itemPath = parseItemPath(reward.getOutputItem());
        int amount = reward.getOutputAmount();
        
        ItemStack item = api.getCreator().getItemFromPath(itemPath.toLowerCase());
        if (item == null) {
            throw new IllegalArgumentException("Invalid item path: " + itemPath);
        }
        
        item = item.clone();
        item.setAmount(amount);
        return item;
    }
    
    private String parseItemPath(String rawPath) {
        if (rawPath.contains(":")) {
            return rawPath.split(":")[0];
        }
        return rawPath;
    }

    // ===== PERSISTENCE =====

    // ====================================
    // Read sources.yml. Sources in worlds that are not loaded, or older than
    // the expiry, are dropped.
    // ====================================
    public void load() {
        sources.clear();
        if (!dataFile.exists()) {
            return;
        }

        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = data.getConfigurationSection(SOURCES_PATH);
        if (section == null) {
            return;
        }

        long cutoff = System.currentTimeMillis() - SOURCE_EXPIRY_MILLIS;
        for (String key : section.getKeys(false)) {
            UUID hunter;
            try {
                hunter = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid UUID in " + DATA_FILE_NAME + ": " + key);
                continue;
            }

            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            long placedAt = entry.getLong("placed");
            World world = Bukkit.getWorld(entry.getString("world", ""));
            if (world == null || placedAt <= cutoff) {
                continue;
            }

            Location location = new Location(world, entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"));
            sources.put(hunter, new Source(location, placedAt));
        }
    }

    // ====================================
    // Write every source to disk. Called whenever one is placed or cleared.
    // ====================================
    public void save() {
        YamlConfiguration data = new YamlConfiguration();

        for (Map.Entry<UUID, Source> entry : sources.entrySet()) {
            String base = SOURCES_PATH + "." + entry.getKey() + ".";
            Location location = entry.getValue().location();
            data.set(base + "world", location.getWorld().getName());
            data.set(base + "x", location.getX());
            data.set(base + "y", location.getY());
            data.set(base + "z", location.getZ());
            data.set(base + "placed", entry.getValue().placedAt());
        }

        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save " + DATA_FILE_NAME + ": " + e.getMessage());
        }
    }

    private record Source(Location location, long placedAt) {
    }
}
