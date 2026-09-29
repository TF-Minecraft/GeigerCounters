package net.tfminecraft.geigercounters.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigMigratorTest {
  @TempDir Path directory;
  JavaPlugin plugin;
  YamlConfiguration live;
  ConfigMigrator migrator;
  String packagedConfig = "config-version: 5\nsource:\n  world: world\n";
  String packagedMessages = "player:\n  found-source: shipped\nadmin:\n  help: help\n";

  @BeforeEach
  void setUp() throws Exception {
    plugin = mock(JavaPlugin.class);
    live = new YamlConfiguration();
    when(plugin.getDataFolder()).thenReturn(directory.toFile());
    when(plugin.getConfig()).thenReturn(live);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    when(plugin.getResource("config.yml")).thenAnswer(invocation -> stream(packagedConfig));
    when(plugin.getResource("messages.yml")).thenAnswer(invocation -> stream(packagedMessages));
    doAnswer(
            invocation -> {
              live.save(directory.resolve("config.yml").toFile());
              return null;
            })
        .when(plugin)
        .saveConfig();
    doAnswer(
            invocation -> {
              Files.writeString(directory.resolve("messages.yml"), packagedMessages);
              return null;
            })
        .when(plugin)
        .saveResource("messages.yml", false);
    migrator = new ConfigMigrator(plugin);
  }

  static ByteArrayInputStream stream(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  void saveLive() throws Exception {
    live.save(directory.resolve("config.yml").toFile());
  }

  YamlConfiguration messages() {
    return YamlConfiguration.loadConfiguration(directory.resolve("messages.yml").toFile());
  }

  @Test
  void freshInstallCreatesMessagesWithoutAttemptingConfigMigration() {
    migrator.migrate();
    verify(plugin).saveResource("messages.yml", false);
    verify(plugin, never()).saveConfig();
    assertEquals("shipped", messages().getString("player.found-source"));
  }

  @Test
  void upgradePreservesValuesUnknownKeysAndCommentsAndCreatesExactBackup() throws Exception {
    packagedConfig =
        "# Version\n"
            + "config-version: 5 # version-inline\n"
            + "# World section\n"
            + "source:\n"
            + "  # Server world\n"
            + "  world: world # world-inline\n"
            + "  enabled: true\n"
            + "list: [default]\n";
    live.set("source.world", "custom");
    live.set("list", List.of("custom-item"));
    live.set("retired-setting", 42);
    live.setComments("source.world", List.of("User comment"));
    live.setInlineComments("source.world", List.of("User inline"));
    saveLive();
    String previous = Files.readString(directory.resolve("config.yml"));
    migrator.migrate();
    assertEquals(previous, Files.readString(directory.resolve("config-v1.yml.bak")));
    assertEquals(5, live.getInt("config-version"));
    assertEquals("custom", live.getString("source.world"));
    assertEquals(List.of("custom-item"), live.getStringList("list"));
    assertEquals(42, live.getInt("retired-setting"));
    assertTrue(live.getBoolean("source.enabled"));
    assertEquals(List.of("User comment"), live.getComments("source.world"));
    assertEquals(List.of("User inline"), live.getInlineComments("source.world"));
    assertEquals(List.of("Version"), live.getComments("config-version"));
    assertEquals(List.of("version-inline"), live.getInlineComments("config-version"));
    assertEquals(List.of("World section"), live.getComments("source"));
  }

  @Test
  void currentConfigIsIdempotentAndVersionOnlyUpgradeStillSaves() throws Exception {
    live.loadFromString(packagedConfig);
    live.set("config-version", 4);
    saveLive();
    migrator.migrate();
    assertTrue(Files.exists(directory.resolve("config-v4.yml.bak")));
    verify(plugin).saveConfig();
    clearInvocations(plugin);
    migrator.migrate();
    verify(plugin, never()).saveConfig();
  }

  @Test
  void futureVersionLeavesConfigUnmodified() throws Exception {
    live.set("config-version", 999);
    live.set("future-setting", "keep");
    saveLive();
    String previous = Files.readString(directory.resolve("config.yml"));
    migrator.migrate();
    assertEquals(previous, Files.readString(directory.resolve("config.yml")));
    verify(plugin, never()).saveConfig();
  }

  @Test
  void legacyDropListsMoveWithoutOverwritingCustomItems() throws Exception {
    packagedConfig +=
        "drops:\n"
            + "  active-list: default\n"
            + "  lists:\n"
            + "    default:\n"
            + "      tiers:\n"
            + "        common: [shipped:1]\n";
    live.set("drops.tiers.common", List.of("m.CUSTOM:4"));
    live.set("drops.tiers.rare", List.of("m.RARE:1"));
    saveLive();
    migrator.migrate();
    assertEquals(List.of("m.CUSTOM:4"), live.getStringList("drops.lists.default.tiers.common"));
    assertEquals(List.of("m.RARE:1"), live.getStringList("drops.lists.default.tiers.rare"));
    assertEquals("default", live.getString("drops.active-list"));
    assertFalse(live.contains("drops.tiers"));
  }

  @Test
  void existingNamedListsPreventDestructiveLegacyDropMigration() throws Exception {
    live.set("drops.tiers.common", List.of("old"));
    live.set("drops.lists.hunt.tiers.common", List.of("new"));
    saveLive();
    migrator.migrate();
    assertEquals(List.of("old"), live.getStringList("drops.tiers.common"));
    assertEquals(List.of("new"), live.getStringList("drops.lists.hunt.tiers.common"));
  }

  @Test
  void rootMessagesMoveToPlayerAndExplicitNestedMessagesWin() throws Exception {
    Files.writeString(
        directory.resolve("messages.yml"),
        "found-source: custom\ndead-geiger: old\nplayer:\n  dead-geiger: new\n");
    migrator.migrate();
    YamlConfiguration messages = messages();
    assertEquals("custom", messages.getString("player.found-source"));
    assertEquals("new", messages.getString("player.dead-geiger"));
    assertEquals("help", messages.getString("admin.help"));
    assertFalse(messages.contains("found-source"));
    assertFalse(messages.contains("dead-geiger"));
  }

  @Test
  void legacyConfigMessagesRetainCustomizationWhenMessagesFileIsNew() throws Exception {
    live.set("messages.found-source", "My custom discovery text");
    live.set("messages.extra.notice", "Nested custom notice");
    saveLive();
    migrator.migrate();
    assertEquals("My custom discovery text", messages().getString("player.found-source"));
    assertEquals("Nested custom notice", messages().getString("extra.notice"));
    assertFalse(live.contains("messages"));
  }

  @Test
  void legacyConfigMessagesMoveIntoExistingEmptyMessagesFile() throws Exception {
    Files.writeString(directory.resolve("messages.yml"), "");
    live.set("messages.found-source", "custom");
    live.set("messages.extra.notice", "extra");
    saveLive();
    migrator.migrate();
    assertEquals("custom", messages().getString("player.found-source"));
    assertEquals("extra", messages().getString("extra.notice"));
    assertFalse(live.contains("messages"));
  }

  @Test
  void missingPackagedResourcesLeaveExistingFilesUntouched() throws Exception {
    Files.writeString(directory.resolve("messages.yml"), "player:\n  found-source: keep\n");
    saveLive();
    when(plugin.getResource(anyString())).thenReturn(null);
    migrator.migrate();
    assertEquals("keep", messages().getString("player.found-source"));
    verify(plugin, never()).saveConfig();
  }

  @Test
  void resourceCloseFailureIsLoggedAndDoesNotCrashMigration() throws Exception {
    saveLive();
    when(plugin.getResource(anyString()))
        .thenAnswer(
            invocation ->
                new ByteArrayInputStream(new byte[0]) {
                  @Override
                  public void close() throws IOException {
                    throw new IOException("unreadable resource");
                  }
                });
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());
      assertDoesNotThrow(migrator::migrate);
    }
    verify(plugin, never()).saveConfig();
  }

  @Test
  void backupFailureIsLoggedAndMigrationStillCompletes() throws Exception {
    saveLive();
    Files.createDirectory(directory.resolve("config-v1.yml.bak"));
    Files.writeString(
        directory.resolve("config-v1.yml.bak/occupied"), "prevent replacing directory");
    assertDoesNotThrow(migrator::migrate);
    assertEquals(5, live.getInt("config-version"));
    verify(plugin).saveConfig();
  }

  @Test
  void unwritableMessagesDestinationDoesNotCrashMigration() throws Exception {
    Files.createDirectory(directory.resolve("messages.yml"));
    Files.writeString(directory.resolve("messages.yml/occupied"), "prevent file replacement");
    assertDoesNotThrow(migrator::migrate);
    assertTrue(Files.isDirectory(directory.resolve("messages.yml")));
  }

  @Test
  void currentVersionStillMigratesLegacyDropListWithoutNewDefaultKeys() throws Exception {
    live.loadFromString(packagedConfig);
    live.set("drops.tiers.common", List.of("m.OLD:1"));
    saveLive();
    migrator.migrate();
    verify(plugin).saveConfig();
    assertEquals(List.of("m.OLD:1"), live.getStringList("drops.lists.default.tiers.common"));
    assertFalse(live.contains("drops.tiers"));
  }

  @Test
  void emptyPackagedSectionsDoNotCreateUnusedLiveSections() throws Exception {
    packagedConfig += "empty: {}\n";
    saveLive();
    migrator.migrate();
    assertFalse(live.contains("empty"));
    assertEquals(5, live.getInt("config-version"));
  }
}
