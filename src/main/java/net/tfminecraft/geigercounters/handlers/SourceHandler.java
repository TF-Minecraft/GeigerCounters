package net.tfminecraft.geigercounters.handlers;

import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Sound;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

// ====================================
// Handles radioactive source location and collection
// ====================================
public class SourceHandler {
    
    private static final String DEAD_GEIGER_PATH = "m.TOOLS.DEAD_GEIGER_COUNTER";

    // Collection is checked every few ticks, so the limit message needs its own
    // cooldown or a player standing on the source would be spammed
    private static final long LIMIT_MESSAGE_COOLDOWN_MILLIS = 15_000L;
    
    private final JavaPlugin plugin;
    private final GeigerConfiguration config;
    private final ItemAPI api;
    private final SpawnLocationFilter spawnFilter;
    private final DropLimitManager dropLimits;
    private final Random random = new Random();

    // Last time each player was told they are rate limited
    private final Map<UUID, Long> lastLimitMessage = new HashMap<>();

    private Location sourceLocation;

    // Non-null while a relocation is still resolving chunk loads
    private CompletableFuture<Location> pendingMove;

    public SourceHandler(JavaPlugin plugin, GeigerConfiguration config, ItemAPI api,
                         SpawnLocationFilter spawnFilter, DropLimitManager dropLimits) {
        this.plugin = plugin;
        this.config = config;
        this.api = api;
        this.spawnFilter = spawnFilter;
        this.dropLimits = dropLimits;
    }
    
    // ====================================
    // Get current source location
    // ====================================
    public Location getSourceLocation() {
        return sourceLocation;
    }
    
    // ====================================
    // Move source to a random location within configured bounds
    //
    // Candidates are rolled until one passes the spawn filters (liquid, void,
    // blocked blocks, distance from spawn, WorldGuard regions). Every candidate
    // needs its chunk loaded to read the terrain, so attempts run one at a time
    // through getChunkAtAsync instead of stalling the main thread.
    //
    // If no candidate passes within the attempt budget the last one is used
    // anyway, so the source always exists rather than silently disappearing.
    //
    // The returned future completes on the main thread with the chosen location.
    // ====================================
    public CompletableFuture<Location> moveSourceToRandomLocation() {
        // Collapse overlapping requests onto the search already running
        if (pendingMove != null && !pendingMove.isDone()) {
            return pendingMove;
        }

        // Clear the source for the duration of the search so nobody collects
        // a stale location while attempts are still resolving
        sourceLocation = null;

        CompletableFuture<Location> result = new CompletableFuture<>();
        pendingMove = result;
        attemptRandomPlacement(1, result);
        return result;
    }

    private void attemptRandomPlacement(int attempt, CompletableFuture<Location> result) {
        double x = config.getMinX() + (config.getMaxX() - config.getMinX()) * random.nextDouble();
        double z = config.getMinZ() + (config.getMaxZ() - config.getMinZ()) * random.nextDouble();
        int maxAttempts = config.getSpawnFilters().getMaxAttempts();

        loadChunkFor(x, z).whenComplete((chunk, error) -> {
            // Paper completes chunk futures on the main thread, so everything
            // below is safe to run against the world directly
            if (!plugin.isEnabled()) {
                result.complete(null);
                return;
            }
            if (error != null) {
                plugin.getLogger().warning("Failed to load chunk for candidate source location: " + error.getMessage());
            }

            Location candidate = toSurfaceLocation(x, z);
            SpawnLocationFilter.Rejection rejection = spawnFilter.check(candidate);

            if (rejection == null) {
                applySourceLocation(candidate, attempt);
                result.complete(candidate);
                return;
            }

            if (attempt >= maxAttempts) {
                plugin.getLogger().warning(String.format(
                    "No valid source location found after %d attempts (last rejection: %s). "
                        + "Using the last candidate - check your source.spawn-filters settings.",
                    maxAttempts, rejection.getDescription()));
                applySourceLocation(candidate, attempt);
                result.complete(candidate);
                return;
            }

            attemptRandomPlacement(attempt + 1, result);
        });
    }

    // ====================================
    // Move source to specific X/Z coordinates (Y snaps to surface)
    // Admin-driven moves bypass the spawn filters on purpose, but a failed
    // filter check is logged so the admin knows the spot is normally excluded
    // ====================================
    public CompletableFuture<Location> moveSourceToLocation(double x, double z) {
        CompletableFuture<Location> result = new CompletableFuture<>();
        pendingMove = result;

        loadChunkFor(x, z).whenComplete((chunk, error) -> {
            if (!plugin.isEnabled()) {
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

            applySourceLocation(location, 1);
            result.complete(location);
        });

        return result;
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

    private void applySourceLocation(Location location, int attempts) {
        sourceLocation = location;

        plugin.getLogger().info(String.format("Radioactive source moved to X = %.1f Z = %.1f (%d attempt%s)",
            location.getX(), location.getZ(), attempts, attempts == 1 ? "" : "s"));
    }

    // ====================================
    // Check if player is close enough to collect the source
    // ====================================
    public void tryCollectSource(Player player, double distance, EquipmentSlot geigerSlot) {
        if (distance > config.getCollectionDistance()) {
            return;
        }

        // The source is already being relocated - the caller is working from a
        // location that no longer counts, so don't hand out a second reward
        if (sourceLocation == null) {
            return;
        }

        // Player is out of collections for this window - the source stays put
        // so somebody else can still claim it
        if (!dropLimits.canCollect(player)) {
            notifyLimitReached(player);
            return;
        }

        collectSource(player, geigerSlot);
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

    private void collectSource(Player player, EquipmentSlot geigerSlot) {
        // moveSourceToRandomLocation() nulls out sourceLocation before it starts
        // searching, so the collected location has to be grabbed first
        Location collected = sourceLocation;

        dropLimits.recordCollection(player);
        UsageStats.getInstance().recordSourceCollected();
        moveSourceToRandomLocation();
        Bukkit.getPluginManager().callEvent(new GeigerSourceCollectEvent(player, collected));
        notifyPlayerOfCollection(player);
        replaceGeigerWithDeadVersion(player, geigerSlot);
        giveReward(player);
    }

    private void notifyPlayerOfCollection(Player player) {
        player.sendMessage(config.getMessages().get("player.found-source"));
    }

    private void replaceGeigerWithDeadVersion(Player player, EquipmentSlot geigerSlot) {
        // Remove active Geiger Counter from whichever hand held it
        if (geigerSlot == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(null);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        // Give dead Geiger Counter
        try {
            ItemStack deadGeiger = api.getCreator().getItemFromPath(DEAD_GEIGER_PATH).clone();
            player.getInventory().addItem(deadGeiger);
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
        if (selectedTier == null || selectedTier.isEmpty()) {
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
        for (TierReward tier : tiers) {
            cumulativeWeight += tier.getWeight();
            if (randomValue <= cumulativeWeight) {
                return tier;
            }
        }
        
        // Fallback to last tier (shouldnt happen but why not)
        return tiers.get(tiers.size() - 1);
    }
    
    // ====================================
    // Give a specific reward item to the player
    // ====================================
    private void giveRewardItem(Player player, ItemReward reward, String tierName) {
        try {
            ItemStack rewardItem = createRewardItem(reward);
            player.getInventory().addItem(rewardItem);
            plugin.getLogger().info(player.getName() + " received " + tierName + " reward: " + reward.getOutputItem());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to give reward to " + player.getName() + ": " + e.getMessage());
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
}
