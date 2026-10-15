package net.tfminecraft.geigercounters.validators;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.hooks.WorldGuardHook;

import java.util.EnumSet;
import java.util.Set;

// ====================================
// Decides whether a candidate source location is allowed to be used
// Rejects water/lava, void (no ground), blacklisted blocks, locations too
// close to world spawn, locations inside blacklisted WorldGuard regions, and
// caches that would not sit sealed inside natural ground
// ====================================
public class SpawnLocationFilter {

    private static final String WORLDGUARD_PLUGIN = "WorldGuard";

    // Why a candidate location was rejected (null result = accepted)
    public enum Rejection {
        VOID("no ground in column"),
        LIQUID("ground is water or lava"),
        BLOCKED_BLOCK("ground block is blacklisted"),
        TOO_CLOSE_TO_SPAWN("too close to world spawn"),
        WORLDGUARD_REGION("inside a blacklisted WorldGuard region"),
        NOT_BURIED("cache not sealed in natural ground");

        private final String description;

        Rejection(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    // Untouched terrain a cache may be buried in. Player builds are left out,
    // so a cache never lands under, or inside the walls of, someone's base.
    private static final Set<Material> NATURAL_GROUND = EnumSet.of(
        Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.DEEPSLATE,
        Material.TUFF, Material.CALCITE, Material.DRIPSTONE_BLOCK,
        Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.GRASS_BLOCK, Material.PODZOL,
        Material.MYCELIUM, Material.MUD, Material.MOSS_BLOCK, Material.CLAY, Material.GRAVEL,
        Material.SAND, Material.RED_SAND, Material.SANDSTONE, Material.RED_SANDSTONE, Material.SNOW_BLOCK,
        Material.TERRACOTTA, Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
        Material.LIGHT_GRAY_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.RED_TERRACOTTA,
        Material.COAL_ORE, Material.IRON_ORE, Material.COPPER_ORE, Material.GOLD_ORE, Material.REDSTONE_ORE,
        Material.LAPIS_ORE, Material.DIAMOND_ORE, Material.EMERALD_ORE,
        Material.DEEPSLATE_COAL_ORE, Material.DEEPSLATE_IRON_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.DEEPSLATE_GOLD_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.DEEPSLATE_LAPIS_ORE,
        Material.DEEPSLATE_DIAMOND_ORE, Material.DEEPSLATE_EMERALD_ORE);

    private static final BlockFace[] SIDES = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

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
    // Returns null if a cache at <cache> would sit in natural ground with
    // natural ground on all six sides, so nobody sees it without digging
    // ====================================
    public Rejection checkBuried(Block cache) {
        if (!isNaturalGround(cache)) {
            return Rejection.NOT_BURIED;
        }
        for (BlockFace side : SIDES) {
            if (!isNaturalGround(cache.getRelative(side))) {
                return Rejection.NOT_BURIED;
            }
        }
        return null;
    }

    public static boolean isNaturalGround(Block block) {
        return NATURAL_GROUND.contains(block.getType());
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
