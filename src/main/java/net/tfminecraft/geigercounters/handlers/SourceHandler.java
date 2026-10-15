package net.tfminecraft.geigercounters.handlers;

import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

// ====================================
// Each hunter's radioactive source: placement, persistence and opening.
// A hunter is the character being played (the account without RPCharacters).
//
// A source is a cache buried in natural ground. Its location is the centre
// of the cache block, which the hunter digs down to and opens by hand.
// ====================================
public class SourceHandler {
    
    private static final String DEAD_GEIGER_PATH = "m.TOOLS.DEAD_GEIGER_COUNTER";

    // A player clicking at a cache they may not open yet would otherwise get
    // the limit message on every click
    private static final long LIMIT_MESSAGE_COOLDOWN_MILLIS = 15_000L;

    private static final String DATA_FILE_NAME = "sources.yml";
    private static final String SOURCES_PATH = "sources";

    // Sources saved before caches were buried sit on the surface; they lack
    // this flag and are dropped on load so a buried one replaces them
    private static final String BURIED_KEY = "buried";

    // Highest a tree trunk is followed down to the ground beneath it
    private static final int MAX_TRUNK_HEIGHT = 32;

    // Overworld trunks stand on the ground a cache is buried in
    private static final Set<Material> TRUNKS = EnumSet.of(
        Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG, Material.ACACIA_LOG,
        Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.MANGROVE_ROOTS, Material.CHERRY_LOG,
        Material.PALE_OAK_LOG, Material.MUSHROOM_STEM);

    // Random spots tried for one that is both in the ring and inside the area
    private static final int SPOT_TRIES = 100;
    
    private final JavaPlugin plugin;
    private final GeigerConfiguration config;
    private final ItemAPI api;
    private final SpawnLocationFilter spawnFilter;
    private final DropLimitManager dropLimits;
    private final CacheMenu cacheMenu;
    private final File dataFile;
    private final Random random = new Random();

    // Last time each player was told they are rate limited
    private final Map<UUID, Long> lastLimitMessage = new HashMap<>();

    // Hunter -> their source
    private final Map<UUID, Source> sources = new HashMap<>();

    // Hunter -> placement still resolving chunk loads
    private final Map<UUID, CompletableFuture<Location>> pending = new HashMap<>();

    public SourceHandler(JavaPlugin plugin, GeigerConfiguration config, ItemAPI api,
                         SpawnLocationFilter spawnFilter, DropLimitManager dropLimits, CacheMenu cacheMenu) {
        this.plugin = plugin;
        this.config = config;
        this.api = api;
        this.spawnFilter = spawnFilter;
        this.dropLimits = dropLimits;
        this.cacheMenu = cacheMenu;
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
    // WorldGuard regions) and can be buried in natural ground. Every
    // candidate needs its chunk loaded to read the
    // terrain, so attempts run one at a time through getChunkAtAsync instead
    // of stalling the main thread.
    //
    // If no candidate passes within the attempt budget the last one is used
    // anyway, so the hunter always gets a source. When no part of the ring lies
    // inside the area (the hunter is outside it) nothing is placed, any old
    // source is kept, and the future completes with null.
    //
    // The returned future completes on the main thread with the chosen location.
    // ====================================
    public CompletableFuture<Location> placeNear(UUID hunter, Location origin) {
        // Collapse overlapping requests onto the search already running
        CompletableFuture<Location> running = pending.get(hunter);
        if (running != null) {
            return running;
        }

        double[] first = randomSpotNear(origin.getX(), origin.getZ());
        if (first == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Clear the old source for the duration of the search so it cannot be
        // collected while attempts are still resolving
        sources.remove(hunter);

        CompletableFuture<Location> result = new CompletableFuture<>();
        pending.put(hunter, result);
        attemptPlacement(hunter, origin.getX(), origin.getZ(), first, 1, result);
        return result;
    }

    // ====================================
    // A random spot between min-distance and max-distance from the origin that
    // is also inside the area, or null if none turns up. Uniform over the
    // ring's area, so sources do not bunch at the inner edge.
    // ====================================
    private double[] randomSpotNear(double originX, double originZ) {
        double min = config.getMinSourceDistance();
        double max = config.getMaxSourceDistance();
        for (int attempt = 0; attempt < SPOT_TRIES; attempt++) {
            double angle = random.nextDouble() * 2.0 * Math.PI;
            double radius = Math.sqrt(min * min + random.nextDouble() * (max * max - min * min));
            double x = originX + radius * Math.cos(angle);
            double z = originZ + radius * Math.sin(angle);
            if (x >= config.getMinX() && x <= config.getMaxX() && z >= config.getMinZ() && z <= config.getMaxZ()) {
                return new double[] {x, z};
            }
        }
        return null;
    }

    private void attemptPlacement(UUID hunter, double originX, double originZ, double[] spot, int attempt,
                                  CompletableFuture<Location> result) {
        double x = spot[0];
        double z = spot[1];
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

            Candidate candidate = candidateAt(x, z);
            SpawnLocationFilter.Rejection rejection = candidate.check(spawnFilter);

            if (rejection == null) {
                applySource(hunter, candidate.cache(), attempt, result);
                return;
            }

            double[] next = attempt >= maxAttempts ? null : randomSpotNear(originX, originZ);
            if (next == null) {
                plugin.getLogger().warning(String.format(
                    "No valid source location found after %d attempts (last rejection: %s). "
                        + "Using the last candidate - check your source.spawn-filters settings.",
                    attempt, rejection.getDescription()));
                applySource(hunter, candidate.cache(), attempt, result);
                return;
            }

            attemptPlacement(hunter, originX, originZ, next, attempt + 1, result);
        });
    }

    // ====================================
    // Move the hunter's source to specific X/Z coordinates, buried beneath the ground there
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

            Candidate candidate = candidateAt(x, z);

            SpawnLocationFilter.Rejection rejection = candidate.check(spawnFilter);
            if (rejection != null) {
                plugin.getLogger().warning(String.format(
                    "Source manually moved to a location that fails the spawn filters (%s).",
                    rejection.getDescription()));
            }

            applySource(hunter, candidate.cache(), 1, result);
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

    // ====================================
    // A cache buried burial-depth blocks under the ground at x/z.
    //
    // The column is kept one block inside its chunk, so the cache's neighbours
    // are in the chunk already loaded. Leaves are skipped by the height map and
    // tree trunks are followed down, so the ground is the soil the tree grows in.
    // ====================================
    private Candidate candidateAt(double x, double z) {
        World world = config.getWorld();
        int blockX = insideChunk(blockCoord(x));
        int blockZ = insideChunk(blockCoord(z));

        int groundY = world.getHighestBlockYAt(blockX, blockZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int trunkBottom = groundY - MAX_TRUNK_HEIGHT;
        while (groundY > trunkBottom && TRUNKS.contains(world.getBlockAt(blockX, groundY, blockZ).getType())) {
            groundY--;
        }

        int min = config.getMinBurialDepth();
        int depth = min + random.nextInt(config.getMaxBurialDepth() - min + 1);
        int cacheY = Math.max(world.getMinHeight(), groundY - depth);
        return new Candidate(
            new Location(world, blockX + 0.5, groundY + 1.0, blockZ + 0.5),
            new Location(world, blockX + 0.5, cacheY + 0.5, blockZ + 0.5));
    }

    // Local block coordinates 0 and 15 are moved one block inwards
    static int insideChunk(int blockCoord) {
        int local = blockCoord & 15;
        if (local == 0) {
            return blockCoord + 1;
        }
        return local == 15 ? blockCoord - 1 : blockCoord;
    }

    // floor, not truncation - casting rounds toward zero and lands one block
    // off for negative coordinates
    private static int blockCoord(double value) {
        return (int) Math.floor(value);
    }

    private void applySource(UUID hunter, Location location, int attempts, CompletableFuture<Location> result) {
        pending.remove(hunter);
        sources.put(hunter, new Source(location, System.currentTimeMillis()));
        save();

        plugin.getLogger().info(String.format("Source for %s buried at X = %d Y = %d Z = %d (%d attempt%s)",
            hunter, location.getBlockX(), location.getBlockY(), location.getBlockZ(), attempts, attempts == 1 ? "" : "s"));
        result.complete(location);
    }

    // ====================================
    // The hunter opened their cache: spend the detector and show the loot
    // ====================================
    public void open(Player player, UUID hunter, EquipmentSlot geigerSlot) {
        Source source = sources.get(hunter);
        if (source == null) {
            return;
        }

        // Out of finds for this window - the cache stays put for later
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
        replaceGeigerWithDeadVersion(player, geigerSlot);
        notifyPlayerOfCollection(player);

        player.playSound(collected, Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        player.spawnParticle(Particle.WITCH, collected, 30, 0.4, 0.4, 0.4, 0.0);
        cacheMenu.open(player, rollReward(player));
    }

    // One line: the find, then the spent counter. A blank half is left out.
    // A reset between them stops the first half's colour and format carrying over.
    private void notifyPlayerOfCollection(Player player) {
        String found = config.getMessages().get("player.found-source");
        String spent = config.getMessages().get("player.dead-geiger");
        if (found.isBlank() || spent.isBlank()) {
            String only = found.isBlank() ? spent : found;
            if (!only.isBlank()) {
                player.sendMessage(only);
            }
            return;
        }
        player.sendMessage(found + " " + ChatColor.RESET + spent);
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
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to give dead Geiger Counter to " + player.getName() + ": " + e.getMessage());
        }
    }
    
    // ====================================
    // A random reward based on tier weights, or null when there is none
    // ====================================
    private ItemStack rollReward(Player player) {
        List<TierReward> tiers = config.getTierRewards();
        if (tiers.isEmpty()) {
            return null;
        }
        
        // Select a tier based on weights
        TierReward selectedTier = selectRandomTier(tiers);
        if (selectedTier.isEmpty()) {
            return null;
        }
        
        // Select a random item from the tier
        List<ItemReward> tierItems = selectedTier.getItems();
        ItemReward randomItem = tierItems.get(random.nextInt(tierItems.size()));
        return rewardItem(player, randomItem, selectedTier.getTierName());
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
    // Build a specific reward item, or null if its path does not resolve
    // ====================================
    private ItemStack rewardItem(Player player, ItemReward reward, String tierName) {
        try {
            ItemStack rewardItem = createRewardItem(reward);
            plugin.getLogger().info(player.getName() + " received " + tierName + " reward: " + reward.getOutputItem());
            return rewardItem;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to give reward to " + player.getName() + ": " + e.getMessage());
            return null;
        }
    }
    
    static void giveItemOrDropLeftovers(Player player, ItemStack item) {
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
    // Read sources.yml. Sources in worlds that are not loaded, and surface
    // sources from before caches were buried, are dropped.
    // ====================================
    public void load() {
        sources.clear();
        int surfaceSources = 0;
        if (!dataFile.exists()) {
            return;
        }

        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = data.getConfigurationSection(SOURCES_PATH);
        if (section == null) {
            return;
        }

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
            if (!entry.getBoolean(BURIED_KEY)) {
                surfaceSources++;
                continue;
            }
            long placedAt = entry.getLong("placed");
            World world = Bukkit.getWorld(entry.getString("world", ""));
            if (world == null) {
                continue;
            }

            Location location = new Location(world, entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"));
            sources.put(hunter, new Source(location, placedAt));
        }

        if (surfaceSources > 0) {
            plugin.getLogger().info("Dropped " + surfaceSources + " surface source(s) from before caches were buried;"
                + " their hunters get a buried cache on their next hold.");
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
            data.set(base + BURIED_KEY, true);
        }

        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save " + DATA_FILE_NAME + ": " + e.getMessage());
        }
    }

    private record Source(Location location, long placedAt) {
    }

    // Where the hunter stands over the cache, and the cache itself
    private record Candidate(Location surface, Location cache) {

        // The surface rules look at the ground; the burial rule at the cache
        SpawnLocationFilter.Rejection check(SpawnLocationFilter filter) {
            SpawnLocationFilter.Rejection rejection = filter.check(surface);
            return rejection != null ? rejection : filter.checkBuried(cache.getBlock());
        }
    }
}
