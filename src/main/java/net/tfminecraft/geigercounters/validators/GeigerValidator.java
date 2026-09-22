package net.tfminecraft.geigercounters.validators;

import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

// ====================================
// Validates if items are Geiger Counters
// ====================================
public class GeigerValidator {
    
    private static final String GEIGER_COUNTER_PATH = "m.TOOLS.GEIGER_COUNTER";
    
    private final JavaPlugin plugin;
    private final ItemAPI api;

    // Cached template so we don't hit the TLibs item creator every check
    private ItemStack geigerTemplate;

    public GeigerValidator(JavaPlugin plugin, ItemAPI api) {
        this.plugin = plugin;
        this.api = api;
    }

    // ====================================
    // Check if an item is a valid Geiger Counter by matching against TLibs item path
    // ====================================
    public boolean isGeigerCounter(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        ItemStack template = getGeigerTemplate();
        return template != null && item.isSimilar(template);
    }

    private ItemStack getGeigerTemplate() {
        if (geigerTemplate == null) {
            try {
                geigerTemplate = api.getCreator().getItemFromPath(GEIGER_COUNTER_PATH).clone();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load Geiger Counter template: " + e.getMessage());
            }
        }
        return geigerTemplate;
    }
}
