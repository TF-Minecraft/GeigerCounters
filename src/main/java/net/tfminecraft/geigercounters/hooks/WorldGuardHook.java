package net.tfminecraft.geigercounters.hooks;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;

import java.util.Set;

// ====================================
// Thin wrapper around the WorldGuard region API
//
// This class references WorldGuard types directly, so it must only ever be
// loaded when the WorldGuard plugin is actually installed. SpawnLocationFilter
// guards construction behind a plugin presence check.
// ====================================
public class WorldGuardHook {

    // ====================================
    // Check if a location sits inside any of the given region IDs
    // Region IDs are compared lowercase (WorldGuard stores them lowercase)
    // ====================================
    public boolean isInAnyRegion(Location location, Set<String> regionIds) {
        if (regionIds.isEmpty() || location.getWorld() == null) {
            return false;
        }

        RegionManager regions = WorldGuard.getInstance()
            .getPlatform()
            .getRegionContainer()
            .get(BukkitAdapter.adapt(location.getWorld()));

        if (regions == null) {
            return false;
        }

        BlockVector3 point = BlockVector3.at(
            location.getBlockX(), location.getBlockY(), location.getBlockZ());

        for (ProtectedRegion region : regions.getApplicableRegions(point)) {
            if (regionIds.contains(region.getId().toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
