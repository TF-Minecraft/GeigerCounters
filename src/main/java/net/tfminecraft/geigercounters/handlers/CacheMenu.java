package net.tfminecraft.geigercounters.handlers;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;

import java.util.ArrayList;
import java.util.List;

// ====================================
// The chest window shown when a hunter opens their cache.
//
// The window is not backed by any block, so whatever is still in it when it
// closes (or the server stops) goes to the player, never lost. A player who
// dies with it open loses it with the rest of their things, grave included.
// ====================================
public class CacheMenu implements Listener {

    private static final int SIZE = 27;

    // Middle slot of the middle row
    private static final int REWARD_SLOT = 13;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character(LegacyComponentSerializer.SECTION_CHAR)
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build();

    private final GeigerConfiguration config;

    public CacheMenu(GeigerConfiguration config) {
        this.config = config;
    }

    // ====================================
    // Show the cache holding <reward>, which may be null for an empty cache
    // ====================================
    public void open(Player player, ItemStack reward) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
            LEGACY.deserialize(config.getMessages().get("player.cache-title")));
        holder.inventory = inventory;
        if (reward != null) {
            inventory.setItem(REWARD_SLOT, reward);
        }
        // Another plugin may refuse the window; the reward is theirs regardless
        if (player.openInventory(inventory) == null) {
            empty(player, inventory);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder && event.getPlayer() instanceof Player player) {
            empty(player, event.getInventory());
        }
    }

    // The window is still open when the death drops are gathered, and closing
    // it afterwards would return its contents to an inventory about to be
    // cleared. Lowest priority, so they are in place before anything else acts:
    // put in the inventory for graves (RPCharacters snapshots it, then clears
    // the drops) and kept inventories, and in the drops for a plain death.
    // Whatever does not fit falls where the player died.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) {
            return;
        }
        for (ItemStack item : take(top)) {
            int amount = item.getAmount();
            ItemStack copy = item.clone();
            int left = 0;
            for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                left += leftover.getAmount();
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            if (!event.getKeepInventory() && left < amount) {
                copy.setAmount(amount - left);
                event.getDrops().add(copy);
            }
        }
    }

    // ====================================
    // Hand back every open cache before the plugin goes away
    // ====================================
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof Holder) {
                empty(player, top);
                player.closeInventory();
            }
        }
    }

    private static void empty(Player player, Inventory inventory) {
        for (ItemStack item : take(inventory)) {
            SourceHandler.giveItemOrDropLeftovers(player, item);
        }
    }

    // Everything in the window, which is left empty
    private static List<ItemStack> take(Inventory inventory) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.isEmpty()) {
                left.add(item);
            }
        }
        inventory.clear();
        return left;
    }

    // Marks a cache window; the inventory is set once Bukkit has made it
    static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
