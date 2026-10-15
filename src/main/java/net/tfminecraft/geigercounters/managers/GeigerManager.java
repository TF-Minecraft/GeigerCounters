package net.tfminecraft.geigercounters.managers;

import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.TLibs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.handlers.GeigerClickPlayer;
import net.tfminecraft.geigercounters.handlers.ParticleRenderer;
import net.tfminecraft.geigercounters.handlers.SignalBar;
import net.tfminecraft.geigercounters.handlers.SourceHandler;
import net.tfminecraft.geigercounters.validators.GeigerValidator;
import net.tfminecraft.geigercounters.utils.Hunters;
import net.tfminecraft.geigercounters.validators.SpawnLocationFilter;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private SignalBar signalBar;
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
        api = TLibs.getItemAPI();
        
        configuration = new GeigerConfiguration(plugin);
        configuration.load();
        
        validator = new GeigerValidator(plugin, api);
        
        particleRenderer = new ParticleRenderer(configuration);

        clickPlayer = new GeigerClickPlayer(configuration);

        signalBar = new SignalBar(configuration);
        
        spawnFilter = new SpawnLocationFilter(plugin, configuration);

        dropLimitManager = new DropLimitManager(plugin, configuration);
        dropLimitManager.load();

        sourceHandler = new SourceHandler(plugin, configuration, api, spawnFilter, dropLimitManager);
        sourceHandler.load();
        
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
    // Flush drop limits and sources to disk on shutdown
    // ====================================
    public void shutdown() {
        if (clickPlayer != null) {
            clickPlayer.clear();
        }
        if (signalBar != null) {
            signalBar.clear();
        }
        if (dropLimitManager != null) {
            dropLimitManager.save();
        }
        if (sourceHandler != null) {
            sourceHandler.save();
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
    // Check every online player in the source world holding a Geiger Counter
    // in either hand
    // ====================================
    private void checkAllPlayers() {
        World world = configuration.getWorld();
        Set<UUID> hunting = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (world == null || !world.equals(player.getWorld())) {
                continue;
            }
            EquipmentSlot geigerSlot = findGeigerSlot(player);
            if (geigerSlot == null) {
                continue;
            }
            Optional<UUID> hunter = Hunters.of(player);
            if (hunter.isPresent() && handlePlayerWithGeiger(player, hunter.get(), geigerSlot)) {
                hunting.add(player.getUniqueId());
            }
        }

        signalBar.retain(hunting);
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

    // ====================================
    // Returns whether the player has a source to read. One is placed near
    // them when they have none, or have strayed beyond reroll-distance.
    // ====================================
    private boolean handlePlayerWithGeiger(Player player, UUID hunter, EquipmentSlot geigerSlot) {
        Location source = sourceHandler.getSource(hunter);
        if (source == null) {
            if (!sourceHandler.isPlacing(hunter)) {
                sourceHandler.placeNear(hunter, player.getLocation());
            }
            return false;
        }

        double distance = calculateHorizontalDistance(player.getLocation(), source);
        if (!player.getWorld().equals(source.getWorld()) || distance > configuration.getRerollDistance()) {
            sourceHandler.placeNear(hunter, player.getLocation());
            return false;
        }

        particleRenderer.showParticleEffect(player, distance);
        clickPlayer.updateRate(player, distance);
        signalBar.update(player, source, distance);
        sourceHandler.tryCollectSource(player, hunter, distance, geigerSlot);
        return true;
    }
    
    // ===== DISTANCE CALCULATION =====

    // Calculate horizontal distance between two locations (ignores Y axis)
    private double calculateHorizontalDistance(Location from, Location to) {
        double deltaX = from.getX() - to.getX();
        double deltaZ = from.getZ() - to.getZ();
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }
}
