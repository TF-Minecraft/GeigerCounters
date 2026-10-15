package net.tfminecraft.geigercounters.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SourceHandlerTest {
  @TempDir Path directory;
  JavaPlugin plugin;
  GeigerConfiguration config;
  ItemAPI api;
  SpawnLocationFilter filter;
  DropLimitManager limits;
  CacheMenu menu;
  World world;
  Block ground;
  SourceHandler handler;
  Player player;
  PlayerInventory inventory;
  Messages messages;
  UUID hunter = UUID.randomUUID();
  Location origin;

  @BeforeEach
  void setup() {
    plugin = mock(JavaPlugin.class);
    when(plugin.isEnabled()).thenReturn(true);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    when(plugin.getDataFolder()).thenReturn(directory.toFile());
    config = mock(GeigerConfiguration.class);
    api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    filter = mock(SpawnLocationFilter.class);
    limits = mock(DropLimitManager.class);
    menu = mock(CacheMenu.class);
    world = mock(World.class);
    ground = mock(Block.class);
    when(ground.getType()).thenReturn(Material.STONE);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(ground);
    when(world.getMinHeight()).thenReturn(-64);
    when(config.getMinBurialDepth()).thenReturn(3);
    when(config.getMaxBurialDepth()).thenReturn(3);
    when(world.getName()).thenReturn("world");
    when(config.getWorld()).thenReturn(world);
    when(config.getSpawnFilters()).thenReturn(new GeigerConfiguration.SpawnFilterConfig());
    when(config.getMinX()).thenReturn(-5000.0);
    when(config.getMaxX()).thenReturn(5000.0);
    when(config.getMinZ()).thenReturn(-5000.0);
    when(config.getMaxZ()).thenReturn(5000.0);
    when(world.getHighestBlockYAt(anyInt(), anyInt(), eq(HeightMap.MOTION_BLOCKING_NO_LEAVES))).thenReturn(70);
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true)))
        .thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
    messages = mock(Messages.class);
    when(config.getMessages()).thenReturn(messages);
    when(messages.get(anyString(), any(Object[].class))).thenReturn("message");
    player = mock(Player.class);
    inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    ItemStack singleCounter = mock(ItemStack.class);
    when(singleCounter.getAmount()).thenReturn(1);
    when(inventory.getItemInMainHand()).thenReturn(singleCounter);
    when(inventory.getItemInOffHand()).thenReturn(singleCounter);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getName()).thenReturn("Hunter");
    when(player.getWorld()).thenReturn(world);
    origin = new Location(world, -0.5, 71, -16.5);
    when(player.getLocation()).thenReturn(origin);
    when(limits.canCollect(player)).thenReturn(true);
    handler = new SourceHandler(plugin, config, api, filter, limits, menu);
  }

  void place() {
    handler.placeAt(hunter, -0.5, -16.5).join();
  }

  @Test
  void placementWithZeroRingFloorsNegativeBlockCoordinatesAndBuriesUnderTheGround() {
    Location loc = handler.placeNear(hunter, origin).join();
    // Blocks -1 and -17 sit on their chunks' edges, so they move one block in
    assertEquals(-1.5, loc.getX());
    assertEquals(-17.5, loc.getZ());
    assertEquals(67.5, loc.getY());
    assertSame(world, loc.getWorld());
    verify(world).getChunkAtAsync(-1, -2, true);
    verify(world).getHighestBlockYAt(-2, -18, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    verify(filter).check(new Location(world, -1.5, 71, -17.5));
    verify(filter).checkBuried(world.getBlockAt(loc));
    assertSame(loc, handler.getSource(hunter));
    assertFalse(handler.isPlacing(hunter));
    assertNull(handler.getSource(UUID.randomUUID()));
    assertTrue(Files.exists(directory.resolve("sources.yml")));
  }

  @Test
  void placementLandsInsideTheRingAroundTheOrigin() {
    when(config.getMinSourceDistance()).thenReturn(150.0);
    when(config.getMaxSourceDistance()).thenReturn(500.0);
    for (int run = 0; run < 200; run++) {
      Location loc = handler.placeNear(hunter, origin).join();
      double distance = Math.hypot(loc.getX() - origin.getX(), loc.getZ() - origin.getZ());
      // The cache is centred in a block kept off the chunk edge, up to two blocks off the spot
      assertTrue(distance >= 148 && distance <= 502, "distance " + distance);
    }
  }

  @Test
  void placementNearTheAreaEdgeStaysInsideTheAreaAndTheRing() {
    when(config.getMinSourceDistance()).thenReturn(150.0);
    when(config.getMaxSourceDistance()).thenReturn(500.0);
    when(config.getMinX()).thenReturn(-1.0);
    when(config.getMaxX()).thenReturn(1000.0);
    when(config.getMinZ()).thenReturn(-17.0);
    when(config.getMaxZ()).thenReturn(1000.0);
    for (int run = 0; run < 100; run++) {
      Location loc = handler.placeNear(hunter, origin).join();
      double distance = Math.hypot(loc.getX() - origin.getX(), loc.getZ() - origin.getZ());
      assertTrue(distance >= 148 && distance <= 502, "distance " + distance);
      assertTrue(loc.getX() >= -3 && loc.getX() <= 1002);
      assertTrue(loc.getZ() >= -19 && loc.getZ() <= 1002);
    }
  }

  @Test
  void hunterOutsideTheAreaGetsNothingAndKeepsTheirSource() {
    place();
    Location old = handler.getSource(hunter);
    when(config.getMinSourceDistance()).thenReturn(150.0);
    when(config.getMaxSourceDistance()).thenReturn(500.0);
    clearInvocations(world);
    assertNull(handler.placeNear(hunter, new Location(world, 9000, 64, 9000)).join());
    assertNull(handler.placeNear(hunter, new Location(world, 0, 64, 9000)).join());
    assertSame(old, handler.getSource(hunter));
    assertFalse(handler.isPlacing(hunter));
    verify(world, never()).getChunkAtAsync(anyInt(), anyInt(), anyBoolean());
  }

  @Test
  void runningOutOfSpotsInsideTheAreaUsesTheLastCandidate() throws Exception {
    when(config.getMinSourceDistance()).thenReturn(10.0);
    when(config.getMaxSourceDistance()).thenReturn(10.0);
    when(config.getMinX()).thenReturn(0.0);
    when(config.getMaxX()).thenReturn(20.0);
    when(config.getMinZ()).thenReturn(-5.0);
    when(config.getMaxZ()).thenReturn(5.0);
    Random random = mock(Random.class);
    // Angle 0 lands inside the area; every later angle of pi lands outside it
    when(random.nextDouble()).thenReturn(0.0, 0.0, 0.5);
    var field = SourceHandler.class.getDeclaredField("random");
    field.setAccessible(true);
    field.set(handler, random);
    when(filter.check(any())).thenReturn(SpawnLocationFilter.Rejection.LIQUID);
    Location loc = handler.placeNear(hunter, new Location(world, 0, 64, 0)).join();
    assertEquals(10.5, loc.getX(), 1e-9);
    assertEquals(1.5, loc.getZ(), 1e-9);
    verify(filter, times(1)).check(any());
  }

  @Test
  void concurrentPlacementsShareSearchAndHideOldSource() {
    place();
    CompletableFuture<Chunk> future = new CompletableFuture<>();
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(future);
    var first = handler.placeNear(hunter, origin);
    assertSame(first, handler.placeNear(hunter, origin));
    assertNull(handler.getSource(hunter));
    assertTrue(handler.isPlacing(hunter));
    assertFalse(first.isDone());
    future.complete(mock(Chunk.class));
    assertNotNull(first.join());
    assertFalse(handler.isPlacing(hunter));
  }

  @Test
  void huntersHaveSeparateSources() {
    UUID other = UUID.randomUUID();
    place();
    handler.placeAt(other, 30, 40).join();
    assertEquals(-1.5, handler.getSource(hunter).getX());
    assertEquals(30.5, handler.getSource(other).getX());
  }

  @Test
  void rejectedCandidatesRetryThenAccept() {
    when(filter.check(any())).thenReturn(SpawnLocationFilter.Rejection.LIQUID, null);
    assertNotNull(handler.placeNear(hunter, origin).join());
    verify(filter, times(2)).check(any());
  }

  @Test
  void spotsThatCannotHoldASealedCacheAreRetried() {
    when(filter.checkBuried(any())).thenReturn(SpawnLocationFilter.Rejection.NOT_BURIED, null);
    assertNotNull(handler.placeNear(hunter, origin).join());
    verify(filter, times(2)).check(any());
    verify(filter, times(2)).checkBuried(any());
  }

  @Test
  void trunksAreFollowedDownToTheGroundTheTreeStandsIn() {
    Block log = mock(Block.class);
    when(log.getType()).thenReturn(Material.OAK_LOG);
    when(world.getBlockAt(-2, 70, -18)).thenReturn(log);
    when(world.getBlockAt(-2, 69, -18)).thenReturn(log);
    Location loc = handler.placeNear(hunter, origin).join();
    assertEquals(65.5, loc.getY());
    verify(filter).check(new Location(world, -1.5, 69, -17.5));
  }

  @Test
  void aColumnOfTrunkIsOnlyFollowedSoFar() {
    Block log = mock(Block.class);
    when(log.getType()).thenReturn(Material.MUSHROOM_STEM);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(log);
    assertEquals(35.5, handler.placeNear(hunter, origin).join().getY());
  }

  @Test
  void burialDepthIsRolledBetweenTheLimitsAndNeverBelowTheWorld() throws Exception {
    when(config.getMaxBurialDepth()).thenReturn(6);
    Random random = mock(Random.class);
    when(random.nextInt(4)).thenReturn(3, 0);
    var field = SourceHandler.class.getDeclaredField("random");
    field.setAccessible(true);
    field.set(handler, random);
    assertEquals(64.5, handler.placeNear(hunter, origin).join().getY());
    when(world.getHighestBlockYAt(anyInt(), anyInt(), eq(HeightMap.MOTION_BLOCKING_NO_LEAVES))).thenReturn(-63);
    assertEquals(-63.5, handler.placeNear(hunter, origin).join().getY());
  }

  @Test
  void chunkEdgeColumnsMoveOneBlockInwards() {
    assertEquals(1, SourceHandler.insideChunk(0));
    assertEquals(14, SourceHandler.insideChunk(15));
    assertEquals(7, SourceHandler.insideChunk(7));
    assertEquals(-15, SourceHandler.insideChunk(-16));
    assertEquals(-2, SourceHandler.insideChunk(-1));
  }

  @Test
  void exhaustedAttemptsUseLastCandidateAsDocumented() {
    when(filter.check(any())).thenReturn(SpawnLocationFilter.Rejection.VOID);
    assertNotNull(handler.placeNear(hunter, origin).join());
    verify(filter, times(config.getSpawnFilters().getMaxAttempts())).check(any());
  }

  @Test
  void disabledPluginCompletesBothKindsOfPlacementWithoutSource() {
    when(plugin.isEnabled()).thenReturn(false);
    assertNull(handler.placeNear(hunter, origin).join());
    assertNull(handler.placeAt(hunter, 1, 2).join());
    assertNull(handler.getSource(hunter));
    verifyNoInteractions(filter);
  }

  @Test
  void explicitPlacementBypassesFilters() {
    when(filter.check(any())).thenReturn(SpawnLocationFilter.Rejection.BLOCKED_BLOCK);
    Location loc = handler.placeAt(hunter, 34, 65).join();
    assertEquals(34.5, loc.getX());
    assertEquals(65.5, loc.getZ());
    when(filter.check(any())).thenReturn(null);
    when(filter.checkBuried(any())).thenReturn(SpawnLocationFilter.Rejection.NOT_BURIED);
    assertNotNull(handler.placeAt(hunter, 34, 65).join());
    when(filter.checkBuried(any())).thenReturn(null);
    assertNotNull(handler.placeAt(hunter, 34, 65).join());
  }

  @Test
  void chunkFailureStillAttemptsDocumentedSurfaceFallback() {
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("load failed")));
    assertNotNull(handler.placeAt(hunter, 1, 2).join());
    assertNotNull(handler.placeNear(hunter, origin).join());
  }

  @Test
  void openingWithoutASourceDoesNotConsumeLimits() {
    handler.open(player, hunter, EquipmentSlot.HAND);
    verifyNoInteractions(limits);
    verifyNoInteractions(menu);
  }

  @Test
  void pendingManualMoveHidesPreviousSourceAndSupersedesPlacement() {
    place();
    CompletableFuture<Chunk> randomChunk = new CompletableFuture<>(),
        manualChunk = new CompletableFuture<>();
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(randomChunk, manualChunk);
    var random = handler.placeNear(hunter, origin);
    var manual = handler.placeAt(hunter, 30, 40);
    assertNull(handler.getSource(hunter));
    manualChunk.complete(mock(Chunk.class));
    randomChunk.complete(mock(Chunk.class));
    assertNull(random.join());
    assertSame(manual.join(), handler.getSource(hunter));
    assertEquals(30.5, handler.getSource(hunter).getX());
  }

  @Test
  void clearForgetsSourceAndCancelsPendingPlacement() throws Exception {
    handler.clear(hunter);
    assertFalse(Files.exists(directory.resolve("sources.yml")));
    place();
    handler.clear(hunter);
    assertNull(handler.getSource(hunter));
    assertFalse(Files.readString(directory.resolve("sources.yml")).contains(hunter.toString()));
    CompletableFuture<Chunk> chunk = new CompletableFuture<>();
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(chunk);
    var pending = handler.placeNear(hunter, origin);
    handler.clear(hunter);
    assertFalse(handler.isPlacing(hunter));
    chunk.complete(mock(Chunk.class));
    assertNull(pending.join());
    assertNull(handler.getSource(hunter));
  }

  @Test
  void sourcesSurviveARestart() {
    place();
    SourceHandler restarted = new SourceHandler(plugin, config, api, filter, limits, menu);
    try (var b = mockStatic(Bukkit.class)) {
      b.when(() -> Bukkit.getWorld("world")).thenReturn(world);
      restarted.load();
    }
    Location loaded = restarted.getSource(hunter);
    assertEquals(new Location(world, -1.5, 67.5, -17.5), loaded);
  }

  @Test
  void loadSkipsBadUnloadedAndSurfaceEntriesButKeepsOldHunts() throws Exception {
    handler.load();
    Files.writeString(directory.resolve("sources.yml"), "other: 1\n");
    handler.load();
    UUID old = UUID.randomUUID(), unloaded = UUID.randomUUID(), kept = UUID.randomUUID();
    UUID surface = UUID.randomUUID();
    long now = System.currentTimeMillis();
    Files.writeString(
        directory.resolve("sources.yml"),
        String.join(
            "\n",
            "sources:",
            "  not-a-uuid: {world: world, x: 1, y: 2, z: 3, placed: " + now + ", buried: true}",
            "  " + UUID.randomUUID() + ": scalar",
            "  " + old + ": {world: world, x: 1, y: 2, z: 3, placed: " + (now - 31L * 86_400_000L) + ", buried: true}",
            "  " + unloaded + ": {world: gone, x: 1, y: 2, z: 3, placed: " + now + ", buried: true}",
            "  " + kept + ": {world: world, x: 1, y: 2, z: 3, placed: " + now + ", buried: true}",
            "  " + surface + ": {world: world, x: 1, y: 2, z: 3, placed: " + now + "}",
            ""));
    try (var b = mockStatic(Bukkit.class)) {
      b.when(() -> Bukkit.getWorld("world")).thenReturn(world);
      handler.load();
    }
    assertEquals(new Location(world, 1, 2, 3), handler.getSource(old));
    assertNull(handler.getSource(unloaded));
    assertNull(handler.getSource(surface));
    assertEquals(new Location(world, 1, 2, 3), handler.getSource(kept));
  }

  @Test
  void saveFailureIsLoggedWithoutLosingTheSource() throws Exception {
    Files.createDirectory(directory.resolve("sources.yml"));
    place();
    assertNotNull(handler.getSource(hunter));
  }

  @Test
  void limitedPlayerReceivesOneWarningWithoutMovingSource() {
    place();
    Location old = handler.getSource(hunter);
    when(limits.canCollect(player)).thenReturn(false);
    when(config.getLimitDrops()).thenReturn(3);
    when(config.getLimitWindowMillis()).thenReturn(60000L);
    handler.open(player, hunter, EquipmentSlot.HAND);
    handler.open(player, hunter, EquipmentSlot.HAND);
    verify(player, times(1)).sendMessage("message");
    assertSame(old, handler.getSource(hunter));
    verify(limits, never()).recordCollection(any());
    verifyNoInteractions(menu);
  }

  void collect(EquipmentSlot slot) {
    PluginManager plugins = mock(PluginManager.class);
    try (var b = mockStatic(Bukkit.class)) {
      b.when(Bukkit::getPluginManager).thenReturn(plugins);
      handler.open(player, hunter, slot);
      verify(plugins).callEvent(any(GeigerSourceCollectEvent.class));
    }
  }

  @Test
  void collectionConsumesCounterRecordsLimitAndPublishesOriginalLocation() {
    place();
    Location old = handler.getSource(hunter);
    PluginManager plugins = mock(PluginManager.class);
    try (var b = mockStatic(Bukkit.class)) {
      b.when(Bukkit::getPluginManager).thenReturn(plugins);
      handler.open(player, hunter, EquipmentSlot.HAND);
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
    verify(player).playSound(old, Sound.BLOCK_CHEST_OPEN, 1f, 1f);
    verify(player).spawnParticle(Particle.WITCH, old, 30, 0.4, 0.4, 0.4, 0.0);
    verify(menu).open(player, null);
    assertNull(handler.getSource(hunter));
    assertFalse(handler.isPlacing(hunter));
  }

  @Test
  void collectionSendsTheFindAndTheSpentCounterAsOneLine() {
    when(messages.get("player.found-source")).thenReturn("Found!");
    when(messages.get("player.dead-geiger")).thenReturn("Spent.");
    place();
    collect(EquipmentSlot.HAND);
    verify(player).sendMessage("Found! \u00a7rSpent.");
    verify(player, times(1)).sendMessage(anyString());
  }

  @Test
  void theFirstHalfsFormattingDoesNotCarryIntoTheSecond() {
    when(messages.get("player.found-source")).thenReturn("\u00a75\u00a7oFound!");
    when(messages.get("player.dead-geiger")).thenReturn("\u00a7lSpent.");
    place();
    collect(EquipmentSlot.HAND);
    verify(player).sendMessage("\u00a75\u00a7oFound! \u00a7r\u00a7lSpent.");
  }

  @Test
  void aBlankHalfOfTheCollectionLineIsLeftOut() {
    when(messages.get("player.found-source")).thenReturn("Found!", " ", "");
    when(messages.get("player.dead-geiger")).thenReturn("", "Spent.", "");
    for (int run = 0; run < 3; run++) {
      place();
      collect(EquipmentSlot.HAND);
    }
    verify(player).sendMessage("Found!");
    verify(player).sendMessage("Spent.");
    verify(player, times(2)).sendMessage(anyString());
  }

  @Test
  void offhandCollectionPutsClonedRewardWithConfiguredAmountInTheCache() {
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
    verify(menu).open(player, copy);
    verify(inventory, never()).addItem(copy);
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
    verify(menu).open(player, null);
  }

  @Test
  void emptyRewardTierDoesNotAttemptRewardCreation() {
    when(config.getTierRewards()).thenReturn(List.of(new TierReward("empty", 1)));
    place();
    collect(EquipmentSlot.HAND);
    verify(inventory, times(1)).addItem(any(ItemStack.class));
    verify(menu).open(player, null);
  }

  @Test
  void olderManualMovementCannotOverwriteNewerCompletedMovement() {
    CompletableFuture<Chunk> older = new CompletableFuture<>(), newer = new CompletableFuture<>();
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(older, newer);
    handler.placeAt(hunter, 1, 2);
    handler.placeAt(hunter, 30, 40);
    newer.complete(mock(Chunk.class));
    older.complete(mock(Chunk.class));
    assertEquals(30.5, handler.getSource(hunter).getX());
    assertEquals(40.5, handler.getSource(hunter).getZ());
  }

  @Test
  void limitWarningCanBeSentAgainAfterItsCooldown() throws Exception {
    place();
    when(limits.canCollect(player)).thenReturn(false);
    handler.open(player, hunter, EquipmentSlot.HAND);
    var field = SourceHandler.class.getDeclaredField("lastLimitMessage");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<UUID, Long> sent = (Map<UUID, Long>) field.get(handler);
    sent.put(player.getUniqueId(), System.currentTimeMillis() - 16000);
    handler.open(player, hunter, EquipmentSlot.HAND);
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
      // Placement samples an angle and a radius before the reward draw.
      when(random.nextDouble()).thenReturn(draws[index]);
      place();
      collect(EquipmentSlot.HAND);
      verify(api.getCreator()).getItemFromPath("m." + tiers.get(index).getTierName());
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = EquipmentSlot.class,
      names = {"HAND", "OFF_HAND"})
  void collectionConsumesExactlyOneCounterFromEitherHandStack(EquipmentSlot slot) {
    ItemStack active = mock(ItemStack.class), remaining = mock(ItemStack.class);
    when(active.getAmount()).thenReturn(5);
    when(active.clone()).thenReturn(remaining);
    if (slot == EquipmentSlot.HAND) {
      when(inventory.getItemInMainHand()).thenReturn(active);
    } else {
      when(inventory.getItemInOffHand()).thenReturn(active);
    }
    ItemStack deadTemplate = mock(ItemStack.class), dead = mock(ItemStack.class);
    when(api.getCreator().getItemFromPath("m.TOOLS.DEAD_GEIGER_COUNTER")).thenReturn(deadTemplate);
    when(deadTemplate.clone()).thenReturn(dead);
    place();
    collect(slot);
    verify(remaining).setAmount(4);
    if (slot == EquipmentSlot.HAND) {
      verify(inventory).setItemInMainHand(remaining);
      verify(inventory, never()).setItemInOffHand(any());
    } else {
      verify(inventory).setItemInOffHand(remaining);
      verify(inventory, never()).setItemInMainHand(any());
    }
    verify(dead).setAmount(1);
    verify(inventory).addItem(dead);
    verify(active, never()).setAmount(anyInt());
    verify(deadTemplate, never()).setAmount(anyInt());
    verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
  }

  @Test
  void fullInventoryDropsDeadCounterWhileTheRewardWaitsInTheCache() {
    ItemStack deadTemplate = mock(ItemStack.class), dead = mock(ItemStack.class);
    when(api.getCreator().getItemFromPath("m.TOOLS.DEAD_GEIGER_COUNTER")).thenReturn(deadTemplate);
    when(deadTemplate.clone()).thenReturn(dead);
    ItemStack rewardTemplate = mock(ItemStack.class), reward = mock(ItemStack.class);
    when(api.getCreator().getItemFromPath("m.reward")).thenReturn(rewardTemplate);
    when(rewardTemplate.clone()).thenReturn(reward);
    TierReward tier = new TierReward("rare", 1);
    tier.addItem(new ItemReward("m.reward", 12));
    when(config.getTierRewards()).thenReturn(List.of(tier));
    when(inventory.addItem(dead)).thenReturn(new HashMap<>(Map.of(0, dead)));
    place();
    collect(EquipmentSlot.OFF_HAND);
    verify(dead).setAmount(1);
    verify(reward).setAmount(12);
    verify(inventory).setItemInOffHand(null);
    verify(world).dropItemNaturally(player.getLocation(), dead);
    verify(world, times(1)).dropItemNaturally(any(Location.class), any(ItemStack.class));
    verify(menu).open(player, reward);
  }

  @Test
  void partiallyFilledInventoryDropsOnlyTheReturnedRemainder() {
    ItemStack reward = mock(ItemStack.class);
    ItemStack remainder = mock(ItemStack.class);
    when(remainder.getAmount()).thenReturn(3);
    when(inventory.addItem(reward)).thenReturn(new HashMap<>(Map.of(0, remainder)));
    SourceHandler.giveItemOrDropLeftovers(player, reward);
    verify(world).dropItemNaturally(player.getLocation(), remainder);
    verify(world, times(1)).dropItemNaturally(any(Location.class), any(ItemStack.class));
    verify(remainder, never()).setAmount(anyInt());
    assertEquals(3, remainder.getAmount());
  }
}
