package net.tfminecraft.geigercounters.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.geigercounters.models.ItemReward;
import net.tfminecraft.geigercounters.models.TierReward;
import net.tfminecraft.geigercounters.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// ====================================
// Handles loading and storing all Geiger Counter configuration
// ====================================
public class GeigerConfiguration {

    private static final String DEFAULT_SOUND = "block.note_block.hat";

    // Bukkit enum spellings admins are likely to paste in, mapped to the event
    // ID. Only the click-like sounds - anything else has to use the event ID.
    private static final Map<String, String> ENUM_TO_EVENT = new HashMap<>();
    static {
        ENUM_TO_EVENT.put("block_note_block_hat", "block.note_block.hat");
        ENUM_TO_EVENT.put("block_dispenser_fail", "block.dispenser.fail");
        ENUM_TO_EVENT.put("block_comparator_click", "block.comparator.click");
        ENUM_TO_EVENT.put("block_lever_click", "block.lever.click");
        ENUM_TO_EVENT.put("ui_button_click", "ui.button.click");
        ENUM_TO_EVENT.put("entity_item_pickup", "entity.item.pickup");
    }

    private final JavaPlugin plugin;
    
    // World settings
    private World world;
    private double minX, maxX, minZ, maxZ;

    // Where each character's source is placed, relative to the character
    private double minSourceDistance;
    private double maxSourceDistance;
    private double rerollDistance;

    // How many blocks of ground cover a cache
    private int minBurialDepth;
    private int maxBurialDepth;

    // Spawn location filters
    private SpawnFilterConfig spawnFilters = new SpawnFilterConfig();

    // Detection settings
    private double maxDetectionDistance;
    private double pinpointRange;
    private double closeRangeThreshold;
    private double threeRingsDistance;
    private double twoRingsDistance;
    
    // Signal bar
    private boolean barEnabled;
    private double barRange;
    private double barFacingWeight;

    // Click sound
    private boolean soundEnabled;
    private String soundName;
    private double soundVolume;
    private double soundPitch;
    private double soundPitchVariance;
    private double soundMinRate;
    private double soundMaxRate;
    private double soundCurve;

    // Drop limits
    private boolean limitEnabled;
    private int limitDrops;
    private long limitWindowMillis;

    // Messages (messages.yml, not config.yml)
    private Messages messages;
    
    // Colors
    private ColorConfig closeRangeStartColor;
    private ColorConfig closeRangeEndColor;
    private ColorConfig farRangeStartColor;
    private ColorConfig farRangeEndColor;
    
    // Rewards
    private String activeDropList;
    private final List<String> dropListNames = new ArrayList<>();
    private final List<TierReward> tierRewards = new ArrayList<>();
    
    public GeigerConfiguration(JavaPlugin plugin) {
        this.plugin = plugin;
    }
    
    // ====================================
    // Load all configuration from config.yml
    // ====================================
    public void load() {
        loadWorld();
        loadSearchArea();
        loadSourceDistances();
        loadSpawnFilters();
        loadDetectionSettings();
        loadBarSettings();
        loadDropLimits();
        loadSoundSettings();
        loadColorSettings();
        loadMessages();
        loadRewards();
    }
    
    private void loadWorld() {
        String worldName = plugin.getConfig().getString("source.world");
        world = Bukkit.getWorld(worldName);
    }
    
    private void loadSearchArea() {
        minX = plugin.getConfig().getDouble("source.top-left.x");
        maxX = plugin.getConfig().getDouble("source.bottom-right.x");
        minZ = plugin.getConfig().getDouble("source.top-left.z");
        maxZ = plugin.getConfig().getDouble("source.bottom-right.z");
        
        // Make sure that <min> is always less than <max>
        if (minX > maxX) {
            double temp = minX;
            minX = maxX;
            maxX = temp;
        }
        if (minZ > maxZ) {
            double temp = minZ;
            minZ = maxZ;
            maxZ = temp;
        }
    }
    
    // ====================================
    // How far from the character a source is placed, and when it is placed again
    // ====================================
    private void loadSourceDistances() {
        minSourceDistance = Math.max(0.0, plugin.getConfig().getDouble("source.min-distance", 300.0));
        maxSourceDistance = plugin.getConfig().getDouble("source.max-distance", 600.0);
        rerollDistance = plugin.getConfig().getDouble("source.reroll-distance", 1000.0);

        if (maxSourceDistance < minSourceDistance) {
            plugin.getLogger().warning("source.max-distance is below source.min-distance - using min-distance for both.");
            maxSourceDistance = minSourceDistance;
        }

        // Below max-distance a fresh source could be placed out of range and re-rolled forever
        if (rerollDistance < maxSourceDistance) {
            plugin.getLogger().warning("source.reroll-distance is below source.max-distance - using max-distance.");
            rerollDistance = maxSourceDistance;
        }

        minBurialDepth = Math.max(1, plugin.getConfig().getInt("source.burial.min-depth", 3));
        maxBurialDepth = plugin.getConfig().getInt("source.burial.max-depth", 6);
        if (maxBurialDepth < minBurialDepth) {
            plugin.getLogger().warning("source.burial.max-depth is below min-depth - using min-depth for both.");
            maxBurialDepth = minBurialDepth;
        }
    }

    // ====================================
    // Load the rules that decide where the source is allowed to spawn
    // ====================================
    private void loadSpawnFilters() {
        String base = "source.spawn-filters.";
        SpawnFilterConfig filters = new SpawnFilterConfig();

        filters.maxAttempts = Math.max(1, plugin.getConfig().getInt(base + "max-attempts", 50));
        filters.rejectLiquid = plugin.getConfig().getBoolean(base + "reject-liquid", true);
        filters.rejectVoid = plugin.getConfig().getBoolean(base + "reject-void", true);
        filters.minDistanceFromSpawn = plugin.getConfig().getDouble(base + "min-distance-from-spawn", 0.0);

        for (String name : plugin.getConfig().getStringList(base + "blocked-blocks")) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                plugin.getLogger().warning("Unknown material in spawn-filters.blocked-blocks: " + name);
                continue;
            }
            filters.blockedBlocks.add(material);
        }

        filters.worldGuardEnabled = plugin.getConfig().getBoolean(base + "worldguard.enabled", true);
        for (String region : plugin.getConfig().getStringList(base + "worldguard.blacklisted-regions")) {
            filters.blacklistedRegions.add(region.toLowerCase());
        }

        spawnFilters = filters;
    }

    private void loadDetectionSettings() {
        maxDetectionDistance = plugin.getConfig().getDouble("detection.max-detection-distance", 1000.0);
        pinpointRange = plugin.getConfig().getDouble("detection.pinpoint-range", 12.0);
        closeRangeThreshold = plugin.getConfig().getDouble("detection.close-range-threshold", 200.0);
        threeRingsDistance = plugin.getConfig().getDouble("detection.ring-thresholds.three-rings", 100.0);
        twoRingsDistance = plugin.getConfig().getDouble("detection.ring-thresholds.two-rings", 300.0);

        if (pinpointRange <= 0.0) {
            plugin.getLogger().warning("detection.pinpoint-range must be greater than 0 - falling back to 12.");
            pinpointRange = 12.0;
        }
    }
    
    // ====================================
    // Boss bar that fills with closeness and facing
    // ====================================
    private void loadBarSettings() {
        barEnabled = plugin.getConfig().getBoolean("bar.enabled", true);
        barRange = plugin.getConfig().getDouble("bar.range", 750.0);
        barFacingWeight = Math.min(1.0, Math.max(0.0, plugin.getConfig().getDouble("bar.facing-weight", 0.6)));

        if (barRange <= 0.0) {
            plugin.getLogger().warning("bar.range must be greater than 0 - falling back to 750.");
            barRange = 750.0;
        }
    }

    // ====================================
    // Geiger clicking sound
    // ====================================
    private void loadSoundSettings() {
        soundEnabled = plugin.getConfig().getBoolean("sound.enabled", true);
        soundName = normalizeSoundName(plugin.getConfig().getString("sound.sound", DEFAULT_SOUND));
        soundVolume = plugin.getConfig().getDouble("sound.volume", 0.35);
        soundPitch = plugin.getConfig().getDouble("sound.pitch", 1.7);
        soundPitchVariance = Math.max(0.0, plugin.getConfig().getDouble("sound.pitch-variance", 0.15));
        soundMinRate = Math.max(0.0, plugin.getConfig().getDouble("sound.min-rate", 0.5));
        soundMaxRate = Math.max(0.0, plugin.getConfig().getDouble("sound.max-rate", 18.0));
        soundCurve = plugin.getConfig().getDouble("sound.curve", 2.0);

        // An inverted range would make the sound quieten as the player closes in
        if (soundMaxRate < soundMinRate) {
            plugin.getLogger().warning("sound.max-rate is below sound.min-rate - swapping them so clicks speed up near the source.");
            double swap = soundMinRate;
            soundMinRate = soundMaxRate;
            soundMaxRate = swap;
        }

        // A non-positive curve would make every distance click at full rate
        if (soundCurve <= 0.0) {
            plugin.getLogger().warning("sound.curve must be greater than 0 - falling back to 2.0.");
            soundCurve = 2.0;
        }
    }

    // Accepts the sound event ID, with or without the "minecraft:" namespace.
    // Bukkit's enum spelling (BLOCK_NOTE_BLOCK_HAT) is accepted too - it maps
    // onto the same event once lowercased, since the enum name is the event ID
    // with dots turned into underscores.
    private String normalizeSoundName(String raw) {
        if (raw.trim().isEmpty()) {
            return DEFAULT_SOUND;
        }

        String name = raw.trim().toLowerCase();
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }

        // Enum spelling has no dots at all - rebuild the event ID from it
        if (!name.contains(".")) {
            String rebuilt = ENUM_TO_EVENT.get(name);
            if (rebuilt == null) {
                plugin.getLogger().warning("sound.sound '" + raw + "' is not a sound event ID (expected something like "
                    + DEFAULT_SOUND + ") - falling back to " + DEFAULT_SOUND + ".");
                return DEFAULT_SOUND;
            }
            return rebuilt;
        }

        return name;
    }

    // ====================================
    // How many sources a single player may collect per time window
    // ====================================
    private void loadDropLimits() {
        limitEnabled = plugin.getConfig().getBoolean("limits.enabled", true);
        limitDrops = plugin.getConfig().getInt("limits.drops", 3);

        String rawTime = plugin.getConfig().getString("limits.time", "12h");
        limitWindowMillis = Utils.parseDurationMillis(rawTime);
        if (limitWindowMillis <= 0) {
            plugin.getLogger().warning("Invalid limits.time value '" + rawTime + "' - expected something like 30s, 45m, 12h or 1d. Falling back to 12h.");
            limitWindowMillis = TimeUnit.HOURS.toMillis(12);
        }

        // A non-positive drop count would lock everyone out permanently, which
        // is never what an admin means - they mean "turn the limit off"
        if (limitEnabled && limitDrops <= 0) {
            plugin.getLogger().warning("limits.drops must be at least 1 - disabling the drop limit. Set limits.enabled to false to silence this.");
            limitEnabled = false;
        }
    }

    // ====================================
    // Text lives in messages.yml - re-read from disk on every load so
    // /geiger reload picks up edits without a restart
    // ====================================
    private void loadMessages() {
        if (messages == null) {
            messages = new Messages(plugin);
        } else {
            messages.reload();
        }
    }
    
    // =========== Color Settings ======================
    private void loadColorSettings() {
        closeRangeStartColor = new ColorConfig(
            plugin.getConfig().getInt("colors.close-range.start.red", 255),
            plugin.getConfig().getInt("colors.close-range.start.green", 255),
            plugin.getConfig().getInt("colors.close-range.start.blue", 255)
        );
        closeRangeEndColor = new ColorConfig(
            plugin.getConfig().getInt("colors.close-range.end.red", 255),
            plugin.getConfig().getInt("colors.close-range.end.green", 0),
            plugin.getConfig().getInt("colors.close-range.end.blue", 255)
        );
        
        farRangeStartColor = new ColorConfig(
            plugin.getConfig().getInt("colors.far-range.start.red", 255),
            plugin.getConfig().getInt("colors.far-range.start.green", 0),
            plugin.getConfig().getInt("colors.far-range.start.blue", 255)
        );
        farRangeEndColor = new ColorConfig(
            plugin.getConfig().getInt("colors.far-range.end.red", 17),
            plugin.getConfig().getInt("colors.far-range.end.green", 0),
            plugin.getConfig().getInt("colors.far-range.end.blue", 17)
        );
    }
    
    // ====================================
    // Load tiered reward system from config
    // ====================================
    private void loadRewards() {
        tierRewards.clear();
        dropListNames.clear();

        // Load tier weights
        ConfigurationSection weightsSection = plugin.getConfig().getConfigurationSection("drops.tier-weights");
        if (weightsSection == null) {
            plugin.getLogger().warning("No tier-weights section found in config!");
            return;
        }

        // Several named lists live under drops.lists; drops.active-list picks one
        ConfigurationSection listsSection = plugin.getConfig().getConfigurationSection("drops.lists");
        if (listsSection == null) {
            plugin.getLogger().warning("No drops.lists section found in config!");
            return;
        }
        dropListNames.addAll(listsSection.getKeys(false));

        activeDropList = plugin.getConfig().getString("drops.active-list", "default");
        if (!listsSection.isConfigurationSection(activeDropList)) {
            if (dropListNames.isEmpty()) {
                plugin.getLogger().warning("drops.lists has no drop lists in it!");
                return;
            }
            String fallback = dropListNames.get(0);
            plugin.getLogger().warning("Drop list '" + activeDropList + "' not found in drops.lists - using '" + fallback + "' instead.");
            activeDropList = fallback;
        }

        ConfigurationSection tiersSection = listsSection.getConfigurationSection(activeDropList + ".tiers");
        if (tiersSection == null) {
            plugin.getLogger().warning("Drop list '" + activeDropList + "' has no tiers section!");
            return;
        }

        // Process each tier
        String[] tierNames = {"common", "uncommon", "rare", "epic", "legendary", "mythical"};
        for (String tierName : tierNames) {
            double weight = weightsSection.getDouble(tierName, 0.0);

            if (weight > 0) {
                TierReward tier = new TierReward(tierName, weight);

                // Load items for this tier
                List<String> tierItems = tiersSection.getStringList(tierName);
                for (String itemString : tierItems) {
                    ItemReward item = parseItemReward(itemString);
                    if (item != null) {
                        tier.addItem(item);
                    }
                }

                // Only add tier if it has items
                if (!tier.isEmpty()) {
                    tierRewards.add(tier);
                }
            }
        }

        plugin.getLogger().info("Loaded " + tierRewards.size() + " reward tiers from drop list '" + activeDropList + "'");
    }

    private ItemReward parseItemReward(String itemString) {
        // Split on the last colon to separate item path from amount
        int lastColonIndex = itemString.lastIndexOf(':');
        if (lastColonIndex == -1) {
            return null;
        }
        
        String itemPath = itemString.substring(0, lastColonIndex);
        String amountString = itemString.substring(lastColonIndex + 1);
        
        try {
            int amount = Integer.parseInt(amountString);
            return new ItemReward(itemPath, amount);
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Invalid amount in item reward: " + itemString);
            return null;
        }
    }
    
    // ===== GETTERS =====
    
    public World getWorld() { return world; }
    public double getMinX() { return minX; }
    public double getMaxX() { return maxX; }
    public double getMinZ() { return minZ; }
    public double getMaxZ() { return maxZ; }
    public double getMinSourceDistance() { return minSourceDistance; }
    public double getMaxSourceDistance() { return maxSourceDistance; }
    public double getRerollDistance() { return rerollDistance; }
    public int getMinBurialDepth() { return minBurialDepth; }
    public int getMaxBurialDepth() { return maxBurialDepth; }
    public SpawnFilterConfig getSpawnFilters() { return spawnFilters; }
    public double getPinpointRange() { return pinpointRange; }
    public double getMaxDetectionDistance() { return maxDetectionDistance; }
    public double getCloseRangeThreshold() { return closeRangeThreshold; }
    public double getThreeRingsDistance() { return threeRingsDistance; }
    public double getTwoRingsDistance() { return twoRingsDistance; }
    public Messages getMessages() { return messages; }
    public boolean isBarEnabled() { return barEnabled; }
    public double getBarRange() { return barRange; }
    public double getBarFacingWeight() { return barFacingWeight; }
    public boolean isSoundEnabled() { return soundEnabled; }
    public String getSoundName() { return soundName; }
    public double getSoundVolume() { return soundVolume; }
    public double getSoundPitch() { return soundPitch; }
    public double getSoundPitchVariance() { return soundPitchVariance; }
    public double getSoundMinRate() { return soundMinRate; }
    public double getSoundMaxRate() { return soundMaxRate; }
    public double getSoundCurve() { return soundCurve; }
    public boolean isLimitEnabled() { return limitEnabled; }
    public int getLimitDrops() { return limitDrops; }
    public long getLimitWindowMillis() { return limitWindowMillis; }
    public ColorConfig getCloseRangeStartColor() { return closeRangeStartColor; }
    public ColorConfig getCloseRangeEndColor() { return closeRangeEndColor; }
    public ColorConfig getFarRangeStartColor() { return farRangeStartColor; }
    public ColorConfig getFarRangeEndColor() { return farRangeEndColor; }
    public List<TierReward> getTierRewards() { return tierRewards; }
    public String getActiveDropList() { return activeDropList; }
    public List<String> getDropListNames() { return dropListNames; }


    // =========== Rules for valid source spawn locations ================
    public static class SpawnFilterConfig {
        private int maxAttempts = 50;
        private boolean rejectLiquid = true;
        private boolean rejectVoid = true;
        private double minDistanceFromSpawn = 0.0;
        private final Set<Material> blockedBlocks = EnumSet.noneOf(Material.class);
        private boolean worldGuardEnabled = true;
        private final Set<String> blacklistedRegions = new HashSet<>();

        public int getMaxAttempts() { return maxAttempts; }
        public boolean isRejectLiquid() { return rejectLiquid; }
        public boolean isRejectVoid() { return rejectVoid; }
        public double getMinDistanceFromSpawn() { return minDistanceFromSpawn; }
        public Set<Material> getBlockedBlocks() { return blockedBlocks; }
        public boolean isWorldGuardEnabled() { return worldGuardEnabled; }
        public Set<String> getBlacklistedRegions() { return blacklistedRegions; }
    }

    // =========== Store RGB color values ================
    public static class ColorConfig {
        private final int red;
        private final int green;
        private final int blue;
        
        public ColorConfig(int red, int green, int blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
        
        public int getRed() { return red; }
        public int getGreen() { return green; }
        public int getBlue() { return blue; }
    }
}

