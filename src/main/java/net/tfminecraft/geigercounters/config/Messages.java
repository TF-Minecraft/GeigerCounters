package net.tfminecraft.geigercounters.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import net.tfminecraft.geigercounters.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

// ====================================
// Every string the plugin sends to chat, read from messages.yml.
//
// The packaged copy is installed as the defaults, so a key an admin deleted
// (or one added by a newer build before migration runs) still resolves to
// shipped text rather than showing a raw path to whoever ran the command.
// ====================================
public class Messages {

    private final JavaPlugin plugin;

    private YamlConfiguration messages;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    // ====================================
    // Re-read messages.yml from disk, so /geiger reload picks up text edits
    // ====================================
    public void reload() {
        File file = new File(plugin.getDataFolder(), ConfigMigrator.MESSAGES_FILE);
        messages = YamlConfiguration.loadConfiguration(file);

        YamlConfiguration packaged = loadPackaged();
        if (packaged != null) {
            messages.setDefaults(packaged);
        }
    }

    // ====================================
    // Look up a message and fill in its placeholders.
    // Pairs are given inline: get("admin.move-success", "%x%", x, "%z%", z)
    // ====================================
    public String get(String path, Object... placeholderPairs) {
        String raw = messages.getString(path);
        if (raw == null) {
            // Only reachable if the key is missing from both the live file and
            // the packaged defaults, which means a typo in a call site
            plugin.getLogger().warning("Missing message '" + path + "' in " + ConfigMigrator.MESSAGES_FILE);
            return path;
        }

        if (placeholderPairs.length % 2 != 0) {
            plugin.getLogger().warning("Message '" + path + "' was given an odd number of placeholder arguments.");
            return Utils.colorize(raw);
        }

        for (int index = 0; index < placeholderPairs.length; index += 2) {
            String token = String.valueOf(placeholderPairs[index]);
            String value = String.valueOf(placeholderPairs[index + 1]);
            raw = raw.replace(token, value);
        }

        return Utils.colorize(raw);
    }

    // Coordinates read better as whole-ish numbers than raw doubles
    public static String coordinate(double value) {
        return String.format("%.1f", value);
    }

    private YamlConfiguration loadPackaged() {
        try (InputStream stream = plugin.getResource(ConfigMigrator.MESSAGES_FILE)) {
            if (stream == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to read the packaged " + ConfigMigrator.MESSAGES_FILE + ": " + e.getMessage());
            return null;
        }
    }
}
