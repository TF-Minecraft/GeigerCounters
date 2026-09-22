package net.tfminecraft.geigercounters.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

// ====================================
// Keeps an existing config.yml in step with the one shipped in the jar.
//
// saveDefaultConfig() only writes the file when it is missing, so an install
// that upgrades never sees keys added by a new version. The plugin still runs
// (every getter passes a default), but the new settings are invisible - an
// admin cannot tune what is not in the file.
//
// So: every key present in the packaged config but missing from the live one
// is copied over, comments included, and the file is stamped with the config
// version it now matches. Values the admin already set are never touched, and
// keys the plugin no longer knows about are left alone rather than deleted.
// ====================================
public class ConfigMigrator {

    // Bump when config.yml gains keys that existing installs should receive
    // v2: sound + limits sections
    // v3: messages moved out to messages.yml
    // v4: messages.yml split into player/admin sections
    // v5: drops.tiers -> drops.lists.<name>.tiers, selected by drops.active-list
    public static final int CURRENT_VERSION = 5;

    public static final String MESSAGES_FILE = "messages.yml";

    private static final String MESSAGES_SECTION = "messages";

    // Keys that sat at the root of messages.yml before the player/admin split
    private static final String[] LEGACY_ROOT_MESSAGES = {"found-source", "dead-geiger", "limit-reached"};

    public static final String VERSION_PATH = "config-version";

    private final JavaPlugin plugin;

    public ConfigMigrator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ====================================
    // Add any missing keys to the live config, then stamp the version.
    // Safe to call on every startup - it does nothing when already current.
    // ====================================
    public void migrate() {
        migrateMessages();

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            // Fresh install: saveDefaultConfig() already wrote the current file
            return;
        }

        YamlConfiguration packaged = loadPackagedConfig();
        if (packaged == null) {
            return;
        }

        FileConfiguration live = plugin.getConfig();
        int liveVersion = live.getInt(VERSION_PATH, 1);

        if (liveVersion > CURRENT_VERSION) {
            plugin.getLogger().warning("config.yml reports version " + liveVersion + " but this build expects "
                + CURRENT_VERSION + ". Leaving it untouched - downgrading the plugin does not downgrade the config.");
            return;
        }

        boolean movedDropList = migrateDropLists(live);

        List<String> addedKeys = copyMissingKeys(packaged, live);
        boolean versionChanged = liveVersion != CURRENT_VERSION;

        if (addedKeys.isEmpty() && !versionChanged && !movedDropList) {
            return;
        }

        // Keep a copy of what the admin had before the file is rewritten
        backup(configFile, liveVersion);

        live.set(VERSION_PATH, CURRENT_VERSION);
        copyComments(packaged, live, VERSION_PATH);

        plugin.saveConfig();

        if (addedKeys.isEmpty()) {
            plugin.getLogger().info("config.yml stamped as version " + CURRENT_VERSION + " (no new keys).");
        } else {
            plugin.getLogger().info("config.yml updated to version " + CURRENT_VERSION + ", added "
                + addedKeys.size() + " new key(s): " + String.join(", ", addedKeys));
        }
    }

    // ====================================
    // Give messages their own file.
    //
    // messages.yml is created from the jar when absent, then any text the
    // admin had customised in config.yml is lifted across and the old section
    // is deleted, so there is exactly one place to edit text. Their wording
    // wins over the packaged defaults - that is the whole point of migrating
    // rather than just shipping a new file.
    // ====================================
    private void migrateMessages() {
        File messagesFile = new File(plugin.getDataFolder(), MESSAGES_FILE);
        if (!messagesFile.exists()) {
            plugin.saveResource(MESSAGES_FILE, false);
        }

        YamlConfiguration messages = YamlConfiguration.loadConfiguration(messagesFile);
        YamlConfiguration packagedMessages = loadPackagedYaml(MESSAGES_FILE);

        boolean changed = false;

        // Lift anything the admin wrote in the old config.yml section.
        // Those keys were flat, so they land at the root and get nested below.
        FileConfiguration live = plugin.getConfig();
        ConfigurationSection oldSection = live.getConfigurationSection(MESSAGES_SECTION);
        if (oldSection != null) {
            List<String> moved = new ArrayList<>();
            for (String key : oldSection.getKeys(true)) {
                if (oldSection.isConfigurationSection(key)) {
                    continue;
                }
                messages.set(key, oldSection.get(key));
                moved.add(key);
            }

            live.set(MESSAGES_SECTION, null);
            plugin.saveConfig();
            changed = true;

            plugin.getLogger().info("Moved " + moved.size() + " message(s) from config.yml to " + MESSAGES_FILE
                + ": " + String.join(", ", moved));
        }

        // Old layout kept these three at the root; they now live under player.
        // Done before the default merge, or the merge would write shipped text
        // to player.* while the admin's wording sat orphaned at the root.
        for (String key : LEGACY_ROOT_MESSAGES) {
            if (!messages.contains(key, true)) {
                continue;
            }

            String nested = "player." + key;
            if (!messages.contains(nested, true)) {
                messages.set(nested, messages.get(key));
                plugin.getLogger().info("Moved message '" + key + "' under player. in " + MESSAGES_FILE + ".");
            }

            messages.set(key, null);
            changed = true;
        }

        // Fill in messages added by later versions
        if (packagedMessages != null) {
            for (String key : packagedMessages.getKeys(true)) {
                if (packagedMessages.isConfigurationSection(key) || messages.contains(key, true)) {
                    continue;
                }
                messages.set(key, packagedMessages.get(key));
                copyComments(packagedMessages, messages, key);
                changed = true;
                plugin.getLogger().info("Added new message '" + key + "' to " + MESSAGES_FILE + ".");
            }
        }

        if (!changed) {
            return;
        }

        try {
            messages.save(messagesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save " + MESSAGES_FILE + ": " + e.getMessage());
        }
    }

    // ====================================
    // Move the single drops.tiers list into drops.lists.default.
    //
    // Runs before the default merge: lists count as leaves there, so once the
    // admin's tiers sit under default the packaged default list is skipped
    // and their items survive the upgrade.
    // ====================================
    private boolean migrateDropLists(FileConfiguration live) {
        ConfigurationSection oldTiers = live.getConfigurationSection("drops.tiers");
        if (oldTiers == null || live.isConfigurationSection("drops.lists")) {
            return false;
        }

        for (String tier : oldTiers.getKeys(false)) {
            live.set("drops.lists.default.tiers." + tier, oldTiers.get(tier));
        }
        live.set("drops.active-list", "default");
        live.set("drops.tiers", null);

        plugin.getLogger().info("Moved drops.tiers to drops.lists.default and set drops.active-list to 'default'.");
        return true;
    }

    // ====================================
    // Copy every leaf key the packaged config has and the live one lacks.
    //
    // Sections are skipped - setting a leaf creates its parents anyway, and
    // treating a section as a value would overwrite the admin's children.
    // Lists count as leaves: a list the admin edited already exists, so it is
    // never reached, and one they deleted outright is restored on purpose.
    // ====================================
    private List<String> copyMissingKeys(YamlConfiguration packaged, FileConfiguration live) {
        List<String> added = new ArrayList<>();

        for (String key : packaged.getKeys(true)) {
            if (packaged.isConfigurationSection(key) || live.contains(key, true)) {
                continue;
            }

            live.set(key, packaged.get(key));
            copyComments(packaged, live, key);
            added.add(key);
        }

        // Comments for a brand new section sit on the section itself, not on
        // its first child, so those need a second pass
        for (String key : packaged.getKeys(true)) {
            if (packaged.isConfigurationSection(key) && live.isConfigurationSection(key)) {
                copyComments(packaged, live, key);
            }
        }

        return added;
    }

    // Only fills in comments the live file does not already carry
    private void copyComments(FileConfiguration packaged, FileConfiguration live, String key) {
        List<String> comments = packaged.getComments(key);
        if (!comments.isEmpty() && live.getComments(key).isEmpty()) {
            live.setComments(key, comments);
        }

        List<String> inline = packaged.getInlineComments(key);
        if (!inline.isEmpty() && live.getInlineComments(key).isEmpty()) {
            live.setInlineComments(key, inline);
        }
    }

    private YamlConfiguration loadPackagedConfig() {
        return loadPackagedYaml("config.yml");
    }

    private YamlConfiguration loadPackagedYaml(String resource) {
        try (InputStream stream = plugin.getResource(resource)) {
            if (stream == null) {
                plugin.getLogger().warning("No " + resource + " packaged in the jar - skipping its migration.");
                return null;
            }

            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read the packaged " + resource + ": " + e.getMessage());
            return null;
        }
    }

    // ====================================
    // Snapshot the file before rewriting it, so a bad merge is recoverable
    // ====================================
    private void backup(File configFile, int fromVersion) {
        File backup = new File(plugin.getDataFolder(), "config-v" + fromVersion + ".yml.bak");

        try {
            Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Backed up the previous config.yml to " + backup.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to back up config.yml before migrating: " + e.getMessage());
        }
    }
}
