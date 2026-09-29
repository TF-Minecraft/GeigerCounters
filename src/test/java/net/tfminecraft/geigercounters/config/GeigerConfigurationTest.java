package net.tfminecraft.geigercounters.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeigerConfigurationTest {
  @TempDir Path directory;
  JavaPlugin plugin;
  YamlConfiguration yaml;
  GeigerConfiguration configuration;
  World world;

  @BeforeEach
  void setUp() throws Exception {
    plugin = mock(JavaPlugin.class);
    yaml = new YamlConfiguration();
    world = mock(World.class);
    when(plugin.getConfig()).thenReturn(yaml);
    when(plugin.getDataFolder()).thenReturn(directory.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    when(plugin.getResource(anyString()))
        .thenAnswer(invocation -> getClass().getResourceAsStream("/" + invocation.getArgument(0)));
    Files.writeString(directory.resolve("messages.yml"), "player:\n  found-source: custom\n");
    yaml.set("source.world", "world");
    configuration = new GeigerConfiguration(plugin);
  }

  void load() {
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
      configuration.load();
    }
  }

  @Test
  void defaultsProvideUsableDetectionSoundLimitsColorsAndSpawnFilters() {
    load();
    assertSame(world, configuration.getWorld());
    assertEquals(20, configuration.getCollectionDistance());
    assertEquals(2500, configuration.getMaxDetectionDistance());
    assertEquals(200, configuration.getCloseRangeThreshold());
    assertEquals(100, configuration.getThreeRingsDistance());
    assertEquals(300, configuration.getTwoRingsDistance());
    assertTrue(configuration.isSoundEnabled());
    assertEquals("block.note_block.hat", configuration.getSoundName());
    assertEquals(0.35, configuration.getSoundVolume());
    assertEquals(1.7, configuration.getSoundPitch());
    assertEquals(0.15, configuration.getSoundPitchVariance());
    assertEquals(0.5, configuration.getSoundMinRate());
    assertEquals(18, configuration.getSoundMaxRate());
    assertEquals(2, configuration.getSoundCurve());
    assertTrue(configuration.isLimitEnabled());
    assertEquals(3, configuration.getLimitDrops());
    assertEquals(TimeUnit.HOURS.toMillis(12), configuration.getLimitWindowMillis());
    assertColor(configuration.getCloseRangeStartColor(), 255, 255, 255);
    assertColor(configuration.getCloseRangeEndColor(), 255, 0, 255);
    assertColor(configuration.getFarRangeStartColor(), 255, 0, 255);
    assertColor(configuration.getFarRangeEndColor(), 17, 0, 17);
    var filters = configuration.getSpawnFilters();
    assertEquals(50, filters.getMaxAttempts());
    assertTrue(filters.isRejectVoid());
    assertTrue(filters.isRejectLiquid());
    assertTrue(filters.isWorldGuardEnabled());
    assertEquals(0, filters.getMinDistanceFromSpawn());
    assertTrue(filters.getBlockedBlocks().isEmpty());
    assertTrue(filters.getBlacklistedRegions().isEmpty());
    assertTrue(configuration.getTierRewards().isEmpty());
    assertEquals("custom", configuration.getMessages().get("player.found-source"));
  }

  static void assertColor(GeigerConfiguration.ColorConfig color, int red, int green, int blue) {
    assertEquals(red, color.getRed());
    assertEquals(green, color.getGreen());
    assertEquals(blue, color.getBlue());
  }

  @Test
  void invertedBoundsAndSoundRatesAreNormalizedAndInvalidSettingsFallBack() {
    yaml.set("source.top-left.x", 40);
    yaml.set("source.bottom-right.x", -20);
    yaml.set("source.top-left.z", 80);
    yaml.set("source.bottom-right.z", -10);
    yaml.set("sound.min-rate", 12);
    yaml.set("sound.max-rate", 2);
    yaml.set("sound.curve", 0);
    yaml.set("sound.pitch-variance", -1);
    yaml.set("limits.time", "nonsense");
    yaml.set("limits.drops", 0);
    load();
    assertEquals(-20, configuration.getMinX());
    assertEquals(40, configuration.getMaxX());
    assertEquals(-10, configuration.getMinZ());
    assertEquals(80, configuration.getMaxZ());
    assertEquals(2, configuration.getSoundMinRate());
    assertEquals(12, configuration.getSoundMaxRate());
    assertEquals(2, configuration.getSoundCurve());
    assertEquals(0, configuration.getSoundPitchVariance());
    assertFalse(configuration.isLimitEnabled());
    assertEquals(TimeUnit.HOURS.toMillis(12), configuration.getLimitWindowMillis());
  }

  @Test
  void customSpawnFiltersResolveMaterialsAndNormalizeRegionNames() {
    yaml.set("source.spawn-filters.max-attempts", 0);
    yaml.set("source.spawn-filters.reject-liquid", false);
    yaml.set("source.spawn-filters.reject-void", false);
    yaml.set("source.spawn-filters.min-distance-from-spawn", 150);
    yaml.set("source.spawn-filters.blocked-blocks", List.of("STONE", "not_a_material"));
    yaml.set("source.spawn-filters.worldguard.enabled", false);
    yaml.set("source.spawn-filters.worldguard.blacklisted-regions", List.of("Spawn", "MARKET"));
    load();
    var filters = configuration.getSpawnFilters();
    assertEquals(1, filters.getMaxAttempts());
    assertFalse(filters.isRejectLiquid());
    assertFalse(filters.isRejectVoid());
    assertFalse(filters.isWorldGuardEnabled());
    assertEquals(150, filters.getMinDistanceFromSpawn());
    assertEquals(Set.of(Material.STONE), filters.getBlockedBlocks());
    assertEquals(Set.of("spawn", "market"), filters.getBlacklistedRegions());
  }

  @Test
  void supportedSoundNamesAndInvalidNamesHavePredictableResults() {
    for (String name :
        List.of(
            "BLOCK_NOTE_BLOCK_HAT",
            " Minecraft:block.note_block.hat ",
            "block.note_block.hat",
            " ",
            "unknown_enum")) {
      yaml.set("sound.sound", name);
      load();
      assertEquals("block.note_block.hat", configuration.getSoundName(), name);
    }
    yaml.set("sound.sound", "custom.pack.click");
    load();
    assertEquals("custom.pack.click", configuration.getSoundName());
  }

  @Test
  void disabledLimitsDoNotRequirePositiveDropCountAndReloadRefreshesMessages() throws Exception {
    yaml.set("limits.enabled", false);
    yaml.set("limits.drops", -5);
    yaml.set("limits.time", "45m");
    yaml.set("sound.enabled", false);
    yaml.set("sound.min-rate", -5);
    yaml.set("sound.max-rate", -2);
    load();
    Messages messages = configuration.getMessages();
    assertFalse(configuration.isLimitEnabled());
    assertFalse(configuration.isSoundEnabled());
    assertEquals(0, configuration.getSoundMinRate());
    assertEquals(0, configuration.getSoundMaxRate());
    assertEquals(TimeUnit.MINUTES.toMillis(45), configuration.getLimitWindowMillis());
    Files.writeString(directory.resolve("messages.yml"), "player:\n  found-source: changed\n");
    load();
    assertSame(messages, configuration.getMessages());
    assertEquals("changed", messages.get("player.found-source"));
  }

  @Test
  void rewardsChooseNamedListIgnoreDisabledEmptyAndMalformedTiers() {
    yaml.set("drops.tier-weights.common", 10);
    yaml.set("drops.tier-weights.rare", 3);
    yaml.set("drops.tier-weights.epic", 0);
    yaml.set(
        "drops.lists.hunt.tiers.common",
        List.of("m.APPLE:2", "broken", "m.BAD:many", "namespace:item:3"));
    yaml.set("drops.lists.hunt.tiers.rare", List.of());
    yaml.set("drops.lists.hunt.tiers.epic", List.of("m.EPIC:1"));
    yaml.set("drops.active-list", "hunt");
    load();
    assertEquals("hunt", configuration.getActiveDropList());
    assertEquals(List.of("hunt"), configuration.getDropListNames());
    assertEquals(1, configuration.getTierRewards().size());
    var tier = configuration.getTierRewards().getFirst();
    assertEquals("common", tier.getTierName());
    assertEquals(10, tier.getWeight());
    assertEquals(2, tier.getItems().size());
    assertEquals("m.APPLE", tier.getItems().getFirst().getOutputItem());
    assertEquals(2, tier.getItems().getFirst().getOutputAmount());
    assertEquals("namespace:item", tier.getItems().getLast().getOutputItem());
    assertEquals(3, tier.getItems().getLast().getOutputAmount());
    load();
    assertEquals(
        1, configuration.getTierRewards().size(), "Reload must replace rather than append rewards");
  }

  @Test
  void missingListFallsBackToFirstAvailableAndMissingSectionsProduceNoRewards() {
    yaml.createSection("drops.tier-weights");
    load();
    assertTrue(configuration.getTierRewards().isEmpty());
    yaml.createSection("drops.lists");
    load();
    assertTrue(configuration.getTierRewards().isEmpty());
    yaml.createSection("drops.lists.alternate");
    yaml.set("drops.active-list", "missing");
    load();
    assertEquals("alternate", configuration.getActiveDropList());
    assertTrue(configuration.getTierRewards().isEmpty());
  }
}
