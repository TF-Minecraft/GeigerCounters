package net.tfminecraft.geigercounters.managers;

import net.tfminecraft.tlibs.enums.APIType;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.TLibs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.handlers.GeigerClickPlayer;
import net.tfminecraft.geigercounters.handlers.ParticleRenderer;
import net.tfminecraft.geigercounters.handlers.SourceHandler;
import net.tfminecraft.geigercounters.validators.GeigerValidator;
import net.tfminecraft.geigercounters.validators.SpawnLocationFilter;

// ====================================
// Main manager for the Geiger Counter system
// Coordinates between configuration, validation, particles, and source handling
// ====================================
public class GeigerManager {
    
    private static final long CHECK_INTERVAL_TICKS = 5L;
    
    private static GeigerManager instance;
    private final JavaPlugin plugin;
    
    // ===== COMPONENTS =====
    private GeigerConfiguration configuration;
    private GeigerValidator validator;
    private SpawnLocationFilter spawnFilter;
    private ParticleRenderer particleRenderer;
    private GeigerClickPlayer clickPlayer;
    private SourceHandler sourceHandler;
    private DropLimitManager dropLimitManager;
    private ItemAPI api;
    
    // ===== INITIALIZATION =====
    
    private GeigerManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }
    
    public static GeigerManager getInstance(JavaPlugin plugin) {
        if (instance == null) {
            instance = new GeigerManager(plugin);
        }
        return instance;
    }
    
    public static GeigerManager getInstance() {
        return instance;
    }
    
    // ====================================
    // Initialize the Geiger Counter system
    // ====================================
    public void initialize() {
        api = (ItemAPI) TLibs.getApiInstance(APIType.ITEM_API);
        
        configuration = new GeigerConfiguration(plugin);
        configuration.load();
        
        validator = new GeigerValidator(plugin, api);
        
        particleRenderer = new ParticleRenderer(configuration);

        clickPlayer = new GeigerClickPlayer(configuration);
        
        spawnFilter = new SpawnLocationFilter(plugin, configuration);

        dropLimitManager = new DropLimitManager(plugin, configuration);
        dropLimitManager.load();

        sourceHandler = new SourceHandler(plugin, configuration, api, spawnFilter, dropLimitManager);
        
        // Spawn initial source
        sourceHandler.moveSourceToRandomLocation();
        
        // Start player checking task
        startPlayerCheckTask();

        // Clicks roll every tick so close range can crackle instead of tick
        startClickTask();
        
        plugin.getLogger().info("Geiger Counter plugin has been enabled.");
    }
    
    // ====================================
    // Reload configuration from disk
    // ====================================
    public void reload() {
        plugin.reloadConfig();
        configuration.load();
    }

    // ====================================
    // Flush per-player drop limits to disk on shutdown
    // ====================================
    public void shutdown() {
        if (clickPlayer != null) {
            clickPlayer.clear();
        }
        if (dropLimitManager != null) {
            dropLimitManager.save();
        }
    }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public GeigerClickPlayer getClickPlayer() {
        return clickPlayer;
    }

    public DropLimitManager getDropLimitManager() {
        return dropLimitManager;
    }

    public SourceHandler getSourceHandler() {
        return sourceHandler;
    }

    public GeigerConfiguration getConfiguration() {
        return configuration;
    }

    private void startPlayerCheckTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkAllPlayers, 0L, CHECK_INTERVAL_TICKS);
    }

    private void startClickTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, clickPlayer::tick, 0L, 1L);
    }
    
    // ===== PLAYER CHECKING =====
    
    // ====================================
    // Check all online players to see if they're holding a Geiger Counter
    // in either hand
    // ====================================
    private void checkAllPlayers() {
        Location source = sourceHandler.getSourceLocation();
        if (source == null) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            EquipmentSlot geigerSlot = findGeigerSlot(player);

            if (geigerSlot != null) {
                handlePlayerWithGeiger(player, source, geigerSlot);
            }
        }
    }

    // Returns the hand holding a Geiger Counter, or null if neither
    private EquipmentSlot findGeigerSlot(Player player) {
        if (validator.isGeigerCounter(player.getInventory().getItemInMainHand())) {
            return EquipmentSlot.HAND;
        }
        if (validator.isGeigerCounter(player.getInventory().getItemInOffHand())) {
            return EquipmentSlot.OFF_HAND;
        }
        return null;
    }

    private void handlePlayerWithGeiger(Player player, Location source, EquipmentSlot geigerSlot) {
        double distance = calculateHorizontalDistance(player.getLocation(), source);
        particleRenderer.showParticleEffect(player, distance);
        clickPlayer.updateRate(player, distance);
        sourceHandler.tryCollectSource(player, distance, geigerSlot);
    }
    
    // ===== DISTANCE CALCULATION =====

    // Calculate horizontal distance between two locations (ignores Y axis)
    private double calculateHorizontalDistance(Location from, Location to) {
        double deltaX = from.getX() - to.getX();
        double deltaZ = from.getZ() - to.getZ();
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }
}
