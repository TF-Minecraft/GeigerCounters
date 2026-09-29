package net.tfminecraft.geigercounters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DropLimitManagerTest {
  @TempDir Path directory;
  JavaPlugin plugin;
  GeigerConfiguration config;
  Player player;
  UUID playerId;
  DropLimitManager manager;
  final long window = TimeUnit.HOURS.toMillis(12);

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    config = mock(GeigerConfiguration.class);
    player = mock(Player.class);
    playerId = UUID.randomUUID();
    when(plugin.getDataFolder()).thenReturn(directory.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    when(config.isLimitEnabled()).thenReturn(true);
    when(config.getLimitDrops()).thenReturn(3);
    when(config.getLimitWindowMillis()).thenReturn(window);
    when(player.getUniqueId()).thenReturn(playerId);
    manager = new DropLimitManager(plugin, config);
  }

  Path dataPath() {
    return directory.resolve("drop-limits.yml");
  }

  void writeHistory(List<Long> timestamps) throws Exception {
    YamlConfiguration data = new YamlConfiguration();
    data.set("collections." + playerId, timestamps);
    data.save(dataPath().toFile());
    manager.load();
  }

  @Test
  void missingOrNonSectionHistoryLeavesAllSlotsAvailable() throws Exception {
    manager.load();
    assertTrue(manager.canCollect(player));
    assertEquals(3, manager.getRemaining(playerId));
    assertEquals(0, manager.getMillisUntilNextDrop(playerId));
    Files.writeString(dataPath(), "collections: invalid\n");
    manager.load();
    assertEquals(3, manager.getRemaining(playerId));
  }

  @Test
  void recordsCollectionsPersistsThemAndSurvivesRestart() {
    manager.recordCollection(player);
    manager.recordCollection(player);
    manager.recordCollection(player);
    assertFalse(manager.canCollect(player));
    assertEquals(0, manager.getRemaining(playerId));
    long wait = manager.getMillisUntilNextDrop(playerId);
    assertTrue(
        wait > window - 60000 && wait <= window, "Next slot should open about twelve hours later");
    DropLimitManager restarted = new DropLimitManager(plugin, config);
    restarted.load();
    assertEquals(0, restarted.getRemaining(playerId));
    assertFalse(restarted.canCollect(player));
    YamlConfiguration data = YamlConfiguration.loadConfiguration(dataPath().toFile());
    assertEquals(3, data.getLongList("collections." + playerId).size());
  }

  @Test
  void disabledLimitsAndBypassPermissionDoNotAccumulateCollections() {
    when(config.isLimitEnabled()).thenReturn(false);
    assertTrue(manager.canCollect(player));
    manager.recordCollection(player);
    assertEquals(3, manager.getRemaining(playerId));
    assertFalse(Files.exists(dataPath()));
    when(config.isLimitEnabled()).thenReturn(true);
    when(player.hasPermission("geiger.limit.bypass")).thenReturn(true);
    assertTrue(manager.canCollect(player));
    manager.recordCollection(player);
    assertEquals(3, manager.getRemaining(playerId));
    assertFalse(Files.exists(dataPath()));
  }

  @Test
  void unsortedHistoryIsSortedAndExpiredEntriesArePrunedBeforeCounting() throws Exception {
    long now = System.currentTimeMillis();
    writeHistory(List.of(now - 1000, now - window - 1000, now - 2000));
    assertEquals(1, manager.getRemaining(playerId));
    assertEquals(0, manager.getMillisUntilNextDrop(playerId));
    manager.recordCollection(player);
    assertEquals(0, manager.getRemaining(playerId));
    List<Long> stamps =
        YamlConfiguration.loadConfiguration(dataPath().toFile())
            .getLongList("collections." + playerId);
    assertEquals(3, stamps.size());
    assertEquals(now - 2000, stamps.getFirst());
    assertTrue(stamps.getLast() >= now);
  }

  @Test
  void expiredPlayersDisappearFromDiskAndRemainingNeverBecomesNegative() throws Exception {
    long now = System.currentTimeMillis();
    UUID expired = UUID.randomUUID();
    YamlConfiguration data = new YamlConfiguration();
    data.set("collections." + expired, List.of(now - window - 1000));
    data.set("collections." + playerId, List.of(now, now, now, now));
    data.save(dataPath().toFile());
    manager.load();
    assertEquals(0, manager.getRemaining(playerId));
    assertEquals(3, manager.getRemaining(expired));
    manager.save();
    YamlConfiguration saved = YamlConfiguration.loadConfiguration(dataPath().toFile());
    assertFalse(saved.contains("collections." + expired));
    assertEquals(4, saved.getLongList("collections." + playerId).size());
  }

  @Test
  void resetOnlyPersistsChangesAndFreesAllSlotsAcrossRestarts() throws Exception {
    manager.reset(playerId);
    assertFalse(Files.exists(dataPath()));
    manager.recordCollection(player);
    manager.reset(playerId);
    assertEquals(3, manager.getRemaining(playerId));
    manager.load();
    assertEquals(3, manager.getRemaining(playerId));
    assertEquals(0, manager.getMillisUntilNextDrop(playerId));
  }

  @Test
  void invalidUuidAndEmptyHistoriesAreIgnoredAndReloadClearsStaleMemory() throws Exception {
    YamlConfiguration data = new YamlConfiguration();
    data.set("collections.not-a-uuid", List.of(System.currentTimeMillis()));
    data.set("collections." + UUID.randomUUID(), List.of());
    data.set("collections." + playerId, List.of(System.currentTimeMillis()));
    data.save(dataPath().toFile());
    manager.load();
    assertEquals(2, manager.getRemaining(playerId));
    Files.delete(dataPath());
    manager.load();
    assertEquals(3, manager.getRemaining(playerId));
  }

  @Test
  void saveFailureDoesNotThrowOrLoseCurrentInMemoryHistory() throws Exception {
    Files.createDirectory(dataPath());
    Files.writeString(dataPath().resolve("occupied"), "cannot overwrite directory");
    assertDoesNotThrow(() -> manager.recordCollection(player));
    assertEquals(2, manager.getRemaining(playerId));
  }

  @Test
  void disabledZeroDropConfigurationReportsNoWaitEvenWithoutHistory() {
    when(config.isLimitEnabled()).thenReturn(false);
    when(config.getLimitDrops()).thenReturn(0);
    assertTrue(manager.canCollect(player));
    assertEquals(0, manager.getMillisUntilNextDrop(playerId));
  }

  @Test
  void loweringLimitWaitsUntilEnoughCollectionsHaveExpired() throws Exception {
    long now = System.currentTimeMillis();
    writeHistory(
        List.of(
            now - TimeUnit.HOURS.toMillis(3),
            now - TimeUnit.HOURS.toMillis(2),
            now - TimeUnit.HOURS.toMillis(1)));
    when(config.getLimitDrops()).thenReturn(1);
    long wait = manager.getMillisUntilNextDrop(playerId);
    assertTrue(wait > TimeUnit.HOURS.toMillis(11) - 60000 && wait <= TimeUnit.HOURS.toMillis(11));
  }

  @Test
  void disablingLimitsImmediatelyRemovesWaitEvenWithRecordedHistory() {
    manager.recordCollection(player);
    when(config.isLimitEnabled()).thenReturn(false);
    when(config.getLimitDrops()).thenReturn(0);
    assertEquals(0, manager.getMillisUntilNextDrop(playerId));
  }
}
