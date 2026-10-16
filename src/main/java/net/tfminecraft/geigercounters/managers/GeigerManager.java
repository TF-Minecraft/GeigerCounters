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
import net.tfminecraft.geigercounters.config.GeigerConfiguration.HuntMode;
import net.tfminecraft.geigercounters.handlers.BuriedChest;
import net.tfminecraft.geigercounters.handlers.CacheMenu;
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
public class GeigerManager implements BuriedChest.Actions {
    
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
    private BuriedChest buriedChest;
    private CacheMenu cacheMenu;
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

        cacheMenu = new CacheMenu(configuration);

        sourceHandler = new SourceHandler(plugin, configuration, api, spawnFilter, dropLimitManager, cacheMenu);
        sourceHandler.load();

        buriedChest = new BuriedChest(this);
        Bukkit.getPluginManager().registerEvents(buriedChest, plugin);
        Bukkit.getPluginManager().registerEvents(cacheMenu, plugin);
        
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
        if (buriedChest != null) {
            buriedChest.clear();
        }
        if (cacheMenu != null) {
            cacheMenu.closeAll();
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
        Set<UUID> revealed = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (world == null || !world.equals(player.getWorld())) {
                continue;
            }
            EquipmentSlot geigerSlot = findGeigerSlot(player);
            if (geigerSlot == null) {
                continue;
            }
            Optional<UUID> hunter = huntOf(player);
            if (hunter.isPresent() && handlePlayerWithGeiger(player, hunter.get(), revealed)) {
                hunting.add(player.getUniqueId());
            }
        }

        signalBar.retain(hunting);
        buriedChest.retain(revealed);
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
    // Whose source the player reads: their character's, or in global mode the
    // shared one. A player with no active character reads none.
    // ====================================
    private Optional<UUID> huntOf(Player player) {
        Optional<UUID> hunter = Hunters.of(player);
        if (configuration.getHuntMode() == HuntMode.GLOBAL) {
            return hunter.map(character -> SourceHandler.SHARED);
        }
        return hunter;
    }

    // The shared source goes anywhere in the area, a character's near them
    private void placeSource(UUID hunter, Player player) {
        if (SourceHandler.SHARED.equals(hunter)) {
            sourceHandler.placeAnywhere(hunter);
        } else {
            sourceHandler.placeNear(hunter, player.getLocation());
        }
    }

    // ====================================
    // Returns whether the player has a source to read. One is placed when
    // there is none, or a character's has been left beyond reroll-distance.
    // Players near enough to see their cache are added to <revealed>.
    // ====================================
    private boolean handlePlayerWithGeiger(Player player, UUID hunter, Set<UUID> revealed) {
        Location source = sourceHandler.getSource(hunter);
        if (source == null) {
            if (!sourceHandler.isPlacing(hunter)) {
                placeSource(hunter, player);
            }
            return false;
        }

        double distance = calculateHorizontalDistance(player.getLocation(), source);
        boolean leftBehind = !SourceHandler.SHARED.equals(hunter) && distance > configuration.getRerollDistance();
        if (!player.getWorld().equals(source.getWorld()) || leftBehind) {
            placeSource(hunter, player);
            return false;
        }

        particleRenderer.showParticleEffect(player, distance);

        // Depth counts once the hunter is close enough to start digging
        double reach = player.getLocation().distance(source);
        if (reach <= configuration.getPinpointRange()) {
            clickPlayer.updatePinpoint(player, reach);
            signalBar.pinpoint(player, reach);
        } else {
            clickPlayer.updateRate(player, distance);
            signalBar.update(player, source, distance);
        }

        if (reach <= BuriedChest.REVEAL_DISTANCE) {
            buriedChest.show(player, source);
            revealed.add(player.getUniqueId());
        }
        return true;
    }

    // ====================================
    // The hunter clicked a chest, which only shows while they hold a detector.
    // It must still be the cache they hunt; one shown before a move, a
    // character switch or a mode switch opens nothing.
    // ====================================
    @Override
    public void open(Player player, Location block) {
        Optional<UUID> hunter = huntOf(player);
        EquipmentSlot geigerSlot = findGeigerSlot(player);
        if (hunter.isEmpty() || geigerSlot == null) {
            return;
        }
        Location source = sourceHandler.getSource(hunter.get());
        if (source == null || !source.toBlockLocation().equals(block)) {
            return;
        }
        sourceHandler.open(player, hunter.get(), geigerSlot);
    }

    // ====================================
    // The ground over the cache cannot be dug, so bury it somewhere else
    // ====================================
    @Override
    public void groundProtected(Player player) {
        Optional<UUID> hunter = huntOf(player);
        if (hunter.isEmpty()) {
            return;
        }
        player.sendMessage(configuration.getMessages().get("player.cache-protected"));
        buriedChest.hide(player);
        placeSource(hunter.get(), player);
    }
    
    // ===== DISTANCE CALCULATION =====

    // Calculate horizontal distance between two locations (ignores Y axis)
    private double calculateHorizontalDistance(Location from, Location to) {
        double deltaX = from.getX() - to.getX();
        double deltaZ = from.getZ() - to.getZ();
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }
}
