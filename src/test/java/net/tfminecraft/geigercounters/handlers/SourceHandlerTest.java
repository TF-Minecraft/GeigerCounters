package net.tfminecraft.geigercounters.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.tfminecraft.geigercounters.config.*;
import net.tfminecraft.geigercounters.events.GeigerSourceCollectEvent;
import net.tfminecraft.geigercounters.managers.DropLimitManager;
import net.tfminecraft.geigercounters.models.*;
import net.tfminecraft.geigercounters.validators.SpawnLocationFilter;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;

class SourceHandlerTest {
  JavaPlugin plugin;
  GeigerConfiguration config;
  ItemAPI api;
  SpawnLocationFilter filter;
  DropLimitManager limits;
  World world;
  SourceHandler handler;
  Player player;
  PlayerInventory inventory;
  Messages messages;

  @BeforeEach
  void setup() {
    plugin = mock(JavaPlugin.class);
    when(plugin.isEnabled()).thenReturn(true);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    config = mock(GeigerConfiguration.class);
    api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    filter = mock(SpawnLocationFilter.class);
    limits = mock(DropLimitManager.class);
    world = mock(World.class);
    when(config.getWorld()).thenReturn(world);
    when(config.getSpawnFilters()).thenReturn(new GeigerConfiguration.SpawnFilterConfig());
    when(config.getMinX()).thenReturn(-0.5);
    when(config.getMaxX()).thenReturn(-0.5);
    when(config.getMinZ()).thenReturn(-16.5);
    when(config.getMaxZ()).thenReturn(-16.5);
    when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(70);
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true)))
        .thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
    when(config.getCollectionDistance()).thenReturn(20.0);
    messages = mock(Messages.class);
    when(config.getMessages()).thenReturn(messages);
    when(messages.get(anyString(), any(Object[].class))).thenReturn("message");
    player = mock(Player.class);
    inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getName()).thenReturn("Hunter");
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(new Location(world, -0.5, 71, -16.5));
    when(limits.canCollect(player)).thenReturn(true);
    handler = new SourceHandler(plugin, config, api, filter, limits);
  }

  void place() {
    handler.moveSourceToLocation(-0.5, -16.5).join();
  }

  @Test
  void randomPlacementFloorsNegativeBlockCoordinatesAndSnapsToSurface() {
    Location loc = handler.moveSourceToRandomLocation().join();
    assertEquals(-0.5, loc.getX());
    assertEquals(-16.5, loc.getZ());
    assertEquals(71, loc.getY());
    assertSame(world, loc.getWorld());
    verify(world).getChunkAtAsync(-1, -2, true);
    verify(world).getHighestBlockYAt(-1, -17);
    assertSame(loc, handler.getSourceLocation());
  }

  @Test
  void concurrentRandomMovesShareSearchAndHideOldSource() {
    place();
    CompletableFuture<Chunk> future = new CompletableFuture<>();
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(future);
    var first = handler.moveSourceToRandomLocation();
    assertSame(first, handler.moveSourceToRandomLocation());
    assertNull(handler.getSourceLocation());
    assertFalse(first.isDone());
    future.complete(mock(Chunk.class));
    assertNotNull(first.join());
  }

  @Test
  void rejectedRandomCandidatesRetryThenAccept() {
    when(filter.check(any())).thenReturn(SpawnLocationFilter.Rejection.LIQUID, null);
    assertNotNull(handler.moveSourceToRandomLocation().join());
    verify(filter, times(2)).check(any());
  }

  @Test
  void exhaustedAttemptsUseLastCandidateAsDocumented() {
    when(filter.check(any())).thenReturn(SpawnLocationFilter.Rejection.VOID);
    assertNotNull(handler.moveSourceToRandomLocation().join());
    verify(filter, times(config.getSpawnFilters().getMaxAttempts())).check(any());
  }

  @Test
  void disabledPluginCompletesBothKindsOfMovementWithoutSource() {
    when(plugin.isEnabled()).thenReturn(false);
    assertNull(handler.moveSourceToRandomLocation().join());
    assertNull(handler.moveSourceToLocation(1, 2).join());
    assertNull(handler.getSourceLocation());
    verifyNoInteractions(filter);
  }

  @Test
  void explicitPlacementBypassesFilters() {
    when(filter.check(any())).thenReturn(SpawnLocationFilter.Rejection.BLOCKED_BLOCK);
    Location loc = handler.moveSourceToLocation(34, 65).join();
    assertEquals(34, loc.getX());
    assertEquals(65, loc.getZ());
  }

  @Test
  void chunkFailureStillAttemptsDocumentedSurfaceFallback() {
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("load failed")));
    assertNotNull(handler.moveSourceToLocation(1, 2).join());
    assertNotNull(handler.moveSourceToRandomLocation().join());
  }

  @Test
  void distanceAndRelocationGuardsDoNotConsumeLimits() {
    handler.tryCollectSource(player, 1, EquipmentSlot.HAND);
    place();
    handler.tryCollectSource(player, 20.01, EquipmentSlot.HAND);
    verifyNoInteractions(limits);
  }

  @Test
  void currentSourceWorldAndDistanceOverrideStaleCallerDistance() {
    place();
    when(player.getLocation()).thenReturn(new Location(mock(World.class), -0.5, 71, -16.5));
    handler.tryCollectSource(player, 0, EquipmentSlot.HAND);
    when(player.getLocation()).thenReturn(new Location(world, 1000, 71, 1000));
    handler.tryCollectSource(player, 0, EquipmentSlot.HAND);
    verifyNoInteractions(limits);
  }

  @Test
  void pendingManualMoveHidesPreviousSourceAndSupersedesRandomMove() {
    place();
    CompletableFuture<Chunk> randomChunk = new CompletableFuture<>(),
        manualChunk = new CompletableFuture<>();
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(randomChunk, manualChunk);
    var random = handler.moveSourceToRandomLocation();
    var manual = handler.moveSourceToLocation(30, 40);
    assertNull(handler.getSourceLocation());
    manualChunk.complete(mock(Chunk.class));
    randomChunk.complete(mock(Chunk.class));
    assertNull(random.join());
    assertSame(manual.join(), handler.getSourceLocation());
    assertEquals(30, handler.getSourceLocation().getX());
  }

  @Test
  void limitedPlayerReceivesOneWarningWithoutMovingSource() {
    place();
    Location old = handler.getSourceLocation();
    when(limits.canCollect(player)).thenReturn(false);
    when(config.getLimitDrops()).thenReturn(3);
    when(config.getLimitWindowMillis()).thenReturn(60000L);
    handler.tryCollectSource(player, 10, EquipmentSlot.HAND);
    handler.tryCollectSource(player, 10, EquipmentSlot.HAND);
    verify(player, times(1)).sendMessage("message");
    assertSame(old, handler.getSourceLocation());
    verify(limits, never()).recordCollection(any());
  }

  void collect(EquipmentSlot slot) {
    PluginManager plugins = mock(PluginManager.class);
    try (var b = mockStatic(Bukkit.class)) {
      b.when(Bukkit::getPluginManager).thenReturn(plugins);
      handler.tryCollectSource(player, 1, slot);
      verify(plugins).callEvent(any(GeigerSourceCollectEvent.class));
    }
  }

  @Test
  void collectionConsumesCounterRecordsLimitAndPublishesOriginalLocation() {
    place();
    Location old = handler.getSourceLocation();
    PluginManager plugins = mock(PluginManager.class);
    try (var b = mockStatic(Bukkit.class)) {
      b.when(Bukkit::getPluginManager).thenReturn(plugins);
      handler.tryCollectSource(player, 20, EquipmentSlot.HAND);
      verify(plugins)
          .callEvent(
              argThat(
                  event ->
                      event instanceof GeigerSourceCollectEvent collected
                          && collected.getPlayer() == player
                          && collected.getLocation().equals(old)));
    }
    verify(limits).recordCollection(player);
    verify(inventory).setItemInMainHand(null);
    verify(inventory).addItem(any(ItemStack.class));
    verify(player).playSound(any(Location.class), eq(Sound.ENTITY_ITEM_BREAK), eq(1f), eq(1f));
  }

  @Test
  void offhandCollectionAlsoGrantsClonedRewardWithConfiguredAmount() {
    TierReward tier = new TierReward("rare", 1);
    tier.addItem(new ItemReward("m.TEST", 4));
    when(config.getTierRewards()).thenReturn(List.of(tier));
    ItemStack original = mock(ItemStack.class), copy = mock(ItemStack.class);
    when(api.getCreator().getItemFromPath("m.test")).thenReturn(original);
    when(original.clone()).thenReturn(copy);
    place();
    collect(EquipmentSlot.OFF_HAND);
    verify(inventory).setItemInOffHand(null);
    verify(copy).setAmount(4);
    verify(inventory).addItem(copy);
    verify(original, never()).setAmount(anyInt());
  }

  @Test
  void missingDeadCounterAndInvalidRewardAreLoggedWithoutAbortingCollection() {
    TierReward tier = new TierReward("rare", 1);
    tier.addItem(new ItemReward("missing", 1));
    when(config.getTierRewards()).thenReturn(List.of(tier));
    when(api.getCreator().getItemFromPath(anyString())).thenReturn(null);
    place();
    collect(EquipmentSlot.HAND);
    verify(limits).recordCollection(player);
    verify(inventory, never()).addItem(any(ItemStack.class));
  }

  @Test
  void emptyRewardTierDoesNotAttemptRewardCreation() {
    when(config.getTierRewards()).thenReturn(List.of(new TierReward("empty", 1)));
    place();
    collect(EquipmentSlot.HAND);
    verify(inventory, times(1)).addItem(any(ItemStack.class));
  }

  @Test
  void olderManualMovementCannotOverwriteNewerCompletedMovement() {
    CompletableFuture<Chunk> older = new CompletableFuture<>(), newer = new CompletableFuture<>();
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(older, newer);
    handler.moveSourceToLocation(1, 2);
    handler.moveSourceToLocation(30, 40);
    newer.complete(mock(Chunk.class));
    older.complete(mock(Chunk.class));
    assertEquals(30, handler.getSourceLocation().getX());
    assertEquals(40, handler.getSourceLocation().getZ());
  }

  @Test
  void limitWarningCanBeSentAgainAfterItsCooldown() throws Exception {
    place();
    when(limits.canCollect(player)).thenReturn(false);
    handler.tryCollectSource(player, 0, EquipmentSlot.HAND);
    var field = SourceHandler.class.getDeclaredField("lastLimitMessage");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<UUID, Long> sent = (Map<UUID, Long>) field.get(handler);
    sent.put(player.getUniqueId(), System.currentTimeMillis() - 16000);
    handler.tryCollectSource(player, 0, EquipmentSlot.HAND);
    verify(player, times(2)).sendMessage("message");
  }

  @Test
  void weightedDrawSelectsEachTierAndPreservesLegacyItemSuffixParsing() throws Exception {
    List<TierReward> tiers = new ArrayList<>();
    for (String name : List.of("first", "second", "third")) {
      TierReward tier = new TierReward(name, 1);
      tier.addItem(new ItemReward("m." + name + ":legacy", 2));
      tiers.add(tier);
    }
    when(config.getTierRewards()).thenReturn(tiers);
    Random random = mock(Random.class);
    var field = SourceHandler.class.getDeclaredField("random");
    field.setAccessible(true);
    field.set(handler, random);
    double[] draws = {0.1, 0.5, 0.9};
    for (int index = 0; index < draws.length; index++) {
      // Two source coordinates are sampled before the reward draw.
      when(random.nextDouble()).thenReturn(draws[index]);
      place();
      collect(EquipmentSlot.HAND);
      verify(api.getCreator()).getItemFromPath("m." + tiers.get(index).getTierName());
    }
  }
}
