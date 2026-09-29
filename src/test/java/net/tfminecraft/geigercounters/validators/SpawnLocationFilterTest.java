package net.tfminecraft.geigercounters.validators;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.hooks.WorldGuardHook;
import net.tfminecraft.geigercounters.metrics.UsageStats;
import net.tfminecraft.geigercounters.models.*;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpawnLocationFilterTest {
  JavaPlugin plugin;
  GeigerConfiguration config;
  GeigerConfiguration.SpawnFilterConfig settings;
  World world;
  Block ground;
  SpawnLocationFilter filter;

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    config = mock(GeigerConfiguration.class);
    settings = mock(GeigerConfiguration.SpawnFilterConfig.class);
    world = mock(World.class);
    ground = mock(Block.class);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    when(config.getSpawnFilters()).thenReturn(settings);
    when(settings.isRejectVoid()).thenReturn(true);
    when(settings.isRejectLiquid()).thenReturn(true);
    when(world.getMinHeight()).thenReturn(-64);
    when(world.getMaxHeight()).thenReturn(320);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(ground);
    when(world.getSpawnLocation()).thenReturn(new Location(world, 0, 80, 0));
    when(ground.getType()).thenReturn(Material.STONE);
    when(ground.getBlockData()).thenReturn(mock(BlockData.class));
    filter = new SpawnLocationFilter(plugin, config);
  }

  Location location(double x, double y, double z) {
    return new Location(world, x, y, z);
  }

  @Test
  void missingWorldAndOutOfBoundsGroundAreRejectedWithoutBlockQueries() {
    assertEquals(SpawnLocationFilter.Rejection.VOID, filter.check(new Location(null, 0, 70, 0)));
    assertEquals(SpawnLocationFilter.Rejection.VOID, filter.check(location(0, -64, 0)));
    assertEquals(SpawnLocationFilter.Rejection.VOID, filter.check(location(0, 321, 0)));
    verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    when(settings.isRejectVoid()).thenReturn(false);
    assertNull(filter.check(location(0, -64, 0)));
    assertNull(filter.check(location(0, 321, 0)));
    verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
  }

  @Test
  void airLiquidWaterloggedAndBlacklistedGroundHaveDistinctRejections() {
    when(ground.getType()).thenReturn(Material.AIR);
    assertEquals(SpawnLocationFilter.Rejection.VOID, filter.check(location(0, 70, 0)));
    when(settings.isRejectVoid()).thenReturn(false);
    assertNull(filter.check(location(0, 70, 0)));
    when(ground.getType()).thenReturn(Material.WATER);
    when(ground.isLiquid()).thenReturn(true);
    assertEquals(SpawnLocationFilter.Rejection.LIQUID, filter.check(location(0, 70, 0)));
    when(ground.isLiquid()).thenReturn(false);
    when(ground.getType()).thenReturn(Material.OAK_STAIRS);
    Waterlogged data = mock(Waterlogged.class);
    when(ground.getBlockData()).thenReturn(data);
    when(data.isWaterlogged()).thenReturn(true);
    assertEquals(SpawnLocationFilter.Rejection.LIQUID, filter.check(location(0, 70, 0)));
    when(data.isWaterlogged()).thenReturn(false);
    assertNull(filter.check(location(0, 70, 0)));
    when(settings.isRejectLiquid()).thenReturn(false);
    when(settings.getBlockedBlocks()).thenReturn(Set.of(Material.OAK_STAIRS));
    assertEquals(SpawnLocationFilter.Rejection.BLOCKED_BLOCK, filter.check(location(0, 70, 0)));
  }

  @Test
  void spawnDistanceUsesHorizontalCoordinatesAndAllowsExactBoundary() {
    when(settings.getMinDistanceFromSpawn()).thenReturn(5.0);
    assertEquals(
        SpawnLocationFilter.Rejection.TOO_CLOSE_TO_SPAWN, filter.check(location(0, 200, 0)));
    assertNull(filter.check(location(3, 200, 4)));
    assertNull(filter.check(location(4, 200, 4)));
  }

  @Test
  void disabledOrEmptyWorldGuardBlacklistDoesNotResolveOptionalPlugin() {
    try (var bukkit = mockStatic(Bukkit.class)) {
      assertNull(filter.check(location(0, 70, 0)));
      when(settings.isWorldGuardEnabled()).thenReturn(true);
      assertNull(filter.check(location(0, 70, 0)));
      bukkit.verifyNoInteractions();
    }
  }

  void enableRegions() {
    when(settings.isWorldGuardEnabled()).thenReturn(true);
    when(settings.getBlacklistedRegions()).thenReturn(Set.of("spawn"));
  }

  @Test
  void missingWorldGuardIsResolvedOnlyOnceAndAllowsLocations() {
    enableRegions();
    PluginManager plugins = mock(PluginManager.class);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
      assertNull(filter.check(location(0, 70, 0)));
      assertNull(filter.check(location(0, 70, 0)));
      verify(plugins).getPlugin("WorldGuard");
    }
  }

  @Test
  void installedWorldGuardCanRejectAllowOrFailWithoutBreakingChecks() {
    enableRegions();
    PluginManager plugins = mock(PluginManager.class);
    when(plugins.getPlugin("WorldGuard")).thenReturn(mock(Plugin.class));
    try (var bukkit = mockStatic(Bukkit.class);
        var hooks =
            mockConstruction(
                WorldGuardHook.class,
                (hook, context) ->
                    when(hook.isInAnyRegion(any(), any()))
                        .thenReturn(true, false)
                        .thenThrow(new IllegalStateException("integration failed")))) {
      bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
      assertEquals(
          SpawnLocationFilter.Rejection.WORLDGUARD_REGION, filter.check(location(0, 70, 0)));
      assertNull(filter.check(location(0, 70, 0)));
      assertNull(filter.check(location(0, 70, 0)));
      assertEquals(1, hooks.constructed().size());
    }
  }

  @Test
  void incompatibleWorldGuardHookDoesNotDisableAllPlacement() {
    enableRegions();
    PluginManager plugins = mock(PluginManager.class);
    when(plugins.getPlugin("WorldGuard")).thenReturn(mock(Plugin.class));
    try (var bukkit = mockStatic(Bukkit.class);
        var hooks =
            mockConstruction(
                WorldGuardHook.class,
                (hook, context) -> {
                  throw new NoClassDefFoundError("incompatible WorldGuard");
                })) {
      bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
      assertNull(filter.check(location(0, 70, 0)));
    }
  }

  @Test
  void worldGuardHookHandlesMissingWorldEmptyIdsMissingManagerAndRegionMatches() {
    WorldGuardHook hook = new WorldGuardHook();
    assertFalse(hook.isInAnyRegion(location(0, 70, 0), Set.of()));
    assertFalse(hook.isInAnyRegion(new Location(null, 0, 70, 0), Set.of("spawn")));
    WorldGuard guard = mock(WorldGuard.class, RETURNS_DEEP_STUBS);
    try (var guards = mockStatic(WorldGuard.class);
        var adapters = mockConstruction(BukkitWorld.class)) {
      guards.when(WorldGuard::getInstance).thenReturn(guard);
      when(guard.getPlatform().getRegionContainer().get(any(BukkitWorld.class))).thenReturn(null);
      assertFalse(hook.isInAnyRegion(location(0, 70, 0), Set.of("spawn")));
      RegionManager regions = mock(RegionManager.class);
      when(guard.getPlatform().getRegionContainer().get(any(BukkitWorld.class)))
          .thenReturn(regions);
      ApplicableRegionSet applicable = mock(ApplicableRegionSet.class);
      when(regions.getApplicableRegions(any(BlockVector3.class))).thenReturn(applicable);
      ProtectedRegion unrelated = mock(ProtectedRegion.class),
          matched = mock(ProtectedRegion.class);
      when(unrelated.getId()).thenReturn("other");
      when(matched.getId()).thenReturn("spawn");
      when(applicable.iterator()).thenAnswer(invocation -> List.of(unrelated).iterator());
      assertFalse(hook.isInAnyRegion(location(-0.5, 70, -16.5), Set.of("spawn")));
      verify(regions).getApplicableRegions(BlockVector3.at(-1, 70, -17));
      when(applicable.iterator()).thenAnswer(invocation -> List.of(unrelated, matched).iterator());
      assertTrue(hook.isInAnyRegion(location(0, 70, 0), Set.of("spawn")));
    }
  }

  @Test
  void counterValidationRejectsMissingMetadataCachesTemplateAndRetriesFailedLookup() {
    ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    GeigerValidator validator = new GeigerValidator(plugin, api);
    ItemStack candidate = mock(ItemStack.class);
    assertFalse(validator.isGeigerCounter(null));
    assertFalse(validator.isGeigerCounter(candidate));
    when(candidate.hasItemMeta()).thenReturn(true);
    when(api.getCreator().getItemFromPath("m.TOOLS.GEIGER_COUNTER"))
        .thenThrow(new IllegalArgumentException("missing template"));
    assertFalse(validator.isGeigerCounter(candidate));
    ItemStack original = mock(ItemStack.class), template = mock(ItemStack.class);
    var creator = api.getCreator();
    doReturn(original).when(creator).getItemFromPath("m.TOOLS.GEIGER_COUNTER");
    when(original.clone()).thenReturn(template);
    when(candidate.isSimilar(template)).thenReturn(true, false);
    assertTrue(validator.isGeigerCounter(candidate));
    assertFalse(validator.isGeigerCounter(candidate));
    verify(original).clone();
    verify(api.getCreator(), times(2)).getItemFromPath("m.TOOLS.GEIGER_COUNTER");
  }

  @Test
  void tierStateAndUsageCounterRemainConsistentAcrossCollectionsAndDrains() {
    TierReward tier = new TierReward("rare", 4);
    assertTrue(tier.isEmpty());
    ItemReward item = new ItemReward("m.TEST", 2);
    tier.addItem(item);
    assertFalse(tier.isEmpty());
    assertEquals(List.of(item), tier.getItems());
    assertEquals("rare", tier.getTierName());
    assertEquals(4, tier.getWeight());
    assertEquals("m.TEST", item.getOutputItem());
    assertEquals(2, item.getOutputAmount());
    UsageStats stats = UsageStats.getInstance();
    stats.drainSourcesCollected();
    stats.recordSourceCollected();
    stats.recordSourceCollected();
    assertEquals(2, stats.drainSourcesCollected());
    assertEquals(0, stats.drainSourcesCollected());
    for (var rejection : SpawnLocationFilter.Rejection.values()) {
      assertFalse(rejection.getDescription().isBlank());
    }
  }
}
