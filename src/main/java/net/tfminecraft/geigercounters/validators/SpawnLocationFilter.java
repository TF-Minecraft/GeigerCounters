package net.tfminecraft.geigercounters.validators;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.hooks.WorldGuardHook;

// ====================================
// Decides whether a candidate source location is allowed to be used
// Rejects water/lava, void (no ground), blacklisted blocks, locations too
// close to world spawn, and locations inside blacklisted WorldGuard regions
// ====================================
public class SpawnLocationFilter {

    private static final String WORLDGUARD_PLUGIN = "WorldGuard";

    // Why a candidate location was rejected (null result = accepted)
    public enum Rejection {
        VOID("no ground in column"),
        LIQUID("ground is water or lava"),
        BLOCKED_BLOCK("ground block is blacklisted"),
        TOO_CLOSE_TO_SPAWN("too close to world spawn"),
        WORLDGUARD_REGION("inside a blacklisted WorldGuard region");

        private final String description;

        Rejection(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private final JavaPlugin plugin;
    private final GeigerConfiguration config;

    // Null when WorldGuard is not installed. Resolved lazily so the hook's
    // WorldGuard classes are never loaded on servers without the plugin.
    private WorldGuardHook worldGuard;
    private boolean worldGuardResolved;

    public SpawnLocationFilter(JavaPlugin plugin, GeigerConfiguration config) {
        this.plugin = plugin;
        this.config = config;
    }

    // ====================================
    // Returns null if the location is acceptable, otherwise the reason
    // ====================================
    public Rejection check(Location location) {
        GeigerConfiguration.SpawnFilterConfig filters = config.getSpawnFilters();
        World world = location.getWorld();
        if (world == null) {
            return Rejection.VOID;
        }

        Rejection groundRejection = checkGround(location, world, filters);
        if (groundRejection != null) {
            return groundRejection;
        }

        if (isTooCloseToSpawn(location, world, filters)) {
            return Rejection.TOO_CLOSE_TO_SPAWN;
        }

        if (isInBlacklistedRegion(location, filters)) {
            return Rejection.WORLDGUARD_REGION;
        }

        return null;
    }

    // ====================================
    // Ground checks: void, liquid, and blacklisted materials
    // The source stands at <location>, so the ground is the block below it
    // ====================================
    private Rejection checkGround(Location location, World world,
                                  GeigerConfiguration.SpawnFilterConfig filters) {
        int groundY = location.getBlockY() - 1;

        if (filters.isRejectVoid() && groundY < world.getMinHeight()) {
            return Rejection.VOID;
        }

        // Clamp so we never query outside the world's build limits
        if (groundY < world.getMinHeight() || groundY >= world.getMaxHeight()) {
            return filters.isRejectVoid() ? Rejection.VOID : null;
        }

        Block ground = world.getBlockAt(location.getBlockX(), groundY, location.getBlockZ());
        Material groundType = ground.getType();

        if (filters.isRejectVoid() && groundType.isAir()) {
            return Rejection.VOID;
        }

        if (filters.isRejectLiquid() && (ground.isLiquid() || isWaterlogged(ground))) {
            return Rejection.LIQUID;
        }

        if (filters.getBlockedBlocks().contains(groundType)) {
            return Rejection.BLOCKED_BLOCK;
        }

        return null;
    }

    // Catches sources sitting on seagrass, kelp, waterlogged stairs, etc.
    private boolean isWaterlogged(Block block) {
        return block.getBlockData() instanceof org.bukkit.block.data.Waterlogged waterlogged
            && waterlogged.isWaterlogged();
    }

    private boolean isTooCloseToSpawn(Location location, World world,
                                      GeigerConfiguration.SpawnFilterConfig filters) {
        double minDistance = filters.getMinDistanceFromSpawn();
        if (minDistance <= 0) {
            return false;
        }

        Location spawn = world.getSpawnLocation();
        double deltaX = location.getX() - spawn.getX();
        double deltaZ = location.getZ() - spawn.getZ();
        return (deltaX * deltaX + deltaZ * deltaZ) < (minDistance * minDistance);
    }

    private boolean isInBlacklistedRegion(Location location,
                                          GeigerConfiguration.SpawnFilterConfig filters) {
        if (!filters.isWorldGuardEnabled() || filters.getBlacklistedRegions().isEmpty()) {
            return false;
        }

        WorldGuardHook hook = getWorldGuard();
        if (hook == null) {
            return false;
        }

        try {
            return hook.isInAnyRegion(location, filters.getBlacklistedRegions());
        } catch (Exception e) {
            plugin.getLogger().warning("WorldGuard region check failed: " + e.getMessage());
            return false;
        }
    }

    private WorldGuardHook getWorldGuard() {
        if (worldGuardResolved) {
            return worldGuard;
        }
        worldGuardResolved = true;

        if (Bukkit.getPluginManager().getPlugin(WORLDGUARD_PLUGIN) == null) {
            plugin.getLogger().info("WorldGuard not installed - region blacklist disabled.");
            return null;
        }

        try {
            worldGuard = new WorldGuardHook();
            plugin.getLogger().info("WorldGuard detected - region blacklist enabled.");
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to hook into WorldGuard: " + t.getMessage());
            worldGuard = null;
        }
        return worldGuard;
    }
}
