package net.tfminecraft.geigercounters.handlers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// ====================================
// The cache as its hunter sees it: a chest sent to their client alone.
//
// The server still holds the real ground block, so nobody else sees or can
// open it. The chest is sent while the hunter holds a live detector within
// REVEAL_DISTANCE, and resent every few checks, since a chunk reload or
// someone else digging there replaces it on their client.
// ====================================
public class BuriedChest implements Listener {

    // Within this many blocks the chest is sent to the hunter's client
    public static final double REVEAL_DISTANCE = 32.0;

    // Player checks between resends
    static final int RESEND_CHECKS = 4;

    // A cancelled dig this close to the cache's column, from just below the
    // cache up to the surface over it, means the ground is protected
    private static final double PROTECTED_DIG_DISTANCE = 3.0;
    private static final int PROTECTED_DIG_BELOW = 3;
    private static final int PROTECTED_DIG_ABOVE = 16;

    private static final BlockFace[] FACINGS = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    // ====================================
    // What the hunt does when the hunter reaches their cache
    // ====================================
    public interface Actions {
        // The hunter clicked their cache
        void open(Player player);

        // Something stopped the hunter digging next to their cache
        void groundProtected(Player player);
    }

    private final Actions actions;

    // Player -> the cache block their client is showing as a chest
    private final Map<UUID, Shown> shown = new HashMap<>();

    private int check;

    public BuriedChest(Actions actions) {
        this.actions = actions;
    }

    // ====================================
    // Show the player their cache at <cache>, resending when it is due
    // ====================================
    public void show(Player player, Location cache) {
        Location block = cache.toBlockLocation();
        Shown current = shown.get(player.getUniqueId());
        if (current != null && current.block.equals(block) && check - current.sentAt < RESEND_CHECKS) {
            return;
        }
        if (current != null && !current.block.equals(block)) {
            revert(player, current.block);
        }

        player.sendBlockChange(block, chestAt(block));
        shown.put(player.getUniqueId(), new Shown(block, check));
    }

    // ====================================
    // Put the real block back for every player not in <showing>. Called once
    // per player check, after every player has been shown their cache.
    // ====================================
    public void retain(Set<UUID> showing) {
        check++;
        Iterator<Map.Entry<UUID, Shown>> iterator = shown.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Shown> entry = iterator.next();
            if (showing.contains(entry.getKey())) {
                continue;
            }
            iterator.remove();
            revert(Bukkit.getPlayer(entry.getKey()), entry.getValue().block);
        }
    }

    public void clear() {
        retain(Set.of());
    }

    // Clicking the chest opens it. Cancelled first so nothing else acts on the
    // real block underneath, and a left click never starts breaking it.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null || !isShownAt(event.getPlayer(), clicked)) {
            return;
        }
        event.setCancelled(true);
        actions.open(event.getPlayer());
    }

    // Breaking the cache block outright (creative mode) would dig up the real
    // block; the chest comes back on the next check
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreakCache(BlockBreakEvent event) {
        if (!isShownAt(event.getPlayer(), event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        shown.get(event.getPlayer().getUniqueId()).sentAt = check - RESEND_CHECKS;
    }

    // Another plugin refusing the hunter's dig beside their cache (a protected
    // region or claim) means they can never reach it, so it moves elsewhere
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProtectedDig(BlockBreakEvent event) {
        Shown current = shown.get(event.getPlayer().getUniqueId());
        if (!event.isCancelled() || current == null) {
            return;
        }
        Location dug = event.getBlock().getLocation();
        if (dug.equals(current.block) || !dug.getWorld().equals(current.block.getWorld())) {
            return;
        }
        int height = dug.getBlockY() - current.block.getBlockY();
        double across = Math.hypot(dug.getX() - current.block.getX(), dug.getZ() - current.block.getZ());
        if (across > PROTECTED_DIG_DISTANCE || height < -PROTECTED_DIG_BELOW || height > PROTECTED_DIG_ABOVE) {
            return;
        }
        actions.groundProtected(event.getPlayer());
    }

    private boolean isShownAt(Player player, Block block) {
        Shown current = shown.get(player.getUniqueId());
        return current != null && current.block.equals(block.getLocation());
    }

    // Facing fixed by position, so resends never turn the chest
    private static BlockData chestAt(Location block) {
        BlockFace facing = FACINGS[Math.floorMod(block.getBlockX() * 31 + block.getBlockZ(), FACINGS.length)];
        return Bukkit.createBlockData(Material.CHEST, data -> ((Directional) data).setFacing(facing));
    }

    // Only when the player can still see that block; a chunk sent later
    // carries the real block anyway, and reading an unloaded one would load it
    private static void revert(Player player, Location block) {
        World world = block.getWorld();
        if (player == null || !world.equals(player.getWorld())
            || !world.isChunkLoaded(block.getBlockX() >> 4, block.getBlockZ() >> 4)) {
            return;
        }
        player.sendBlockChange(block, block.getBlock().getBlockData());
    }

    private static final class Shown {
        final Location block;
        int sentAt;

        Shown(Location block, int sentAt) {
            this.block = block;
            this.sentAt = sentAt;
        }
    }
}
