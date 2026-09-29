package net.tfminecraft.geigercounters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.tfminecraft.geigercounters.config.*;
import net.tfminecraft.geigercounters.events.GeigerSourceCollectEvent;
import net.tfminecraft.geigercounters.managers.GeigerManager;
import net.tfminecraft.geigercounters.metrics.UsageStats;
import net.tfminecraft.geigercounters.models.TierReward;
import net.tfminecraft.geigercounters.utils.Utils;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.CustomChart;
import org.bukkit.*;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

public class LifecycleUtilitiesTest {
  @TempDir Path directory;

  @Test
  void pluginLifecycleRegistersCommandsMetricsAndShutsDownResources() throws Exception {
    GeigerManager manager = mock(GeigerManager.class);
    GeigerConfiguration config = mock(GeigerConfiguration.class);
    when(manager.getConfiguration()).thenReturn(config);
    when(config.getTierRewards()).thenReturn(List.of(new TierReward("common", 1)));
    when(config.getMaxX()).thenReturn(2000.0);
    when(config.getMaxZ()).thenReturn(3000.0);
    try (var bukkit = mockStatic(Bukkit.class);
        var managers = mockStatic(GeigerManager.class);
        var migrations = mockConstruction(ConfigMigrator.class);
        var metrics = mockConstruction(Metrics.class)) {
      bukkit.when(Bukkit::getUnsafe).thenReturn(mock(UnsafeValues.class));
      assertNotNull(new TestPluginLoader().definePluginSubclass().getConstructor().newInstance());
      geiger_counter plugin = mock(geiger_counter.class, CALLS_REAL_METHODS);
      doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
      doNothing().when(plugin).saveDefaultConfig();
      PluginCommand command = mock(PluginCommand.class);
      doReturn(command).when(plugin).getCommand("geiger");
      managers.when(() -> GeigerManager.getInstance(plugin)).thenReturn(manager);
      managers.when(GeigerManager::getInstance).thenReturn(manager);
      plugin.onEnable();
      verify(plugin).saveDefaultConfig();
      verify(migrations.constructed().getFirst()).migrate();
      verify(manager).initialize();
      verify(command).setExecutor(any());
      verify(command).setTabCompleter(any());
      Metrics created = metrics.constructed().getFirst();
      ArgumentCaptor<CustomChart> charts = ArgumentCaptor.forClass(CustomChart.class);
      verify(created, times(4)).addCustomChart(charts.capture());
      List<CustomChart> values = charts.getAllValues();
      assertTrue(
          values
              .get(0)
              .getRequestJsonObject((message, error) -> fail(message, error), true)
              .toString()
              .contains("1"));
      double[] distances = {500, 1000, 2500, 5000, 5001};
      String[] buckets = {"0-500", "501-1000", "1001-2500", "2501-5000", "5000+"};
      for (int index = 0; index < distances.length; index++) {
        when(config.getMaxDetectionDistance()).thenReturn(distances[index]);
        assertTrue(
            values
                .get(1)
                .getRequestJsonObject((message, error) -> fail(message, error), true)
                .toString()
                .contains(buckets[index]));
      }
      UsageStats.getInstance().drainSourcesCollected();
      UsageStats.getInstance().recordSourceCollected();
      assertNotNull(
          values.get(2).getRequestJsonObject((message, error) -> fail(message, error), true));
      assertTrue(
          values
              .get(3)
              .getRequestJsonObject((message, error) -> fail(message, error), true)
              .toString()
              .contains("6"));
      plugin.onDisable();
      verify(manager).shutdown();
      verify(created).shutdown();
    }
  }

  @Test
  void disableBeforeInitializationIsSafe() {
    geiger_counter plugin = mock(geiger_counter.class, CALLS_REAL_METHODS);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    try (var managers = mockStatic(GeigerManager.class)) {
      assertDoesNotThrow(plugin::onDisable);
    }
  }

  @Test
  void messagesReadLiveValuesFallbackToPackagedDefaultsAndReplaceAllPairs() throws Exception {
    JavaPlugin plugin = messagePlugin();
    Files.writeString(directory.resolve("messages.yml"), "custom: '&aFound %item% x%amount%'\n");
    when(plugin.getResource("messages.yml")).thenAnswer(invocation -> stream("default: shipped\n"));
    Messages messages = new Messages(plugin);
    assertEquals("§aFound gem x3", messages.get("custom", "%item%", "gem", "%amount%", 3));
    assertEquals("shipped", messages.get("default"));
    assertEquals("§aFound %item% x%amount%", messages.get("custom", "unpaired"));
    assertEquals("absent", messages.get("absent"));
    assertEquals("-0.5", Messages.coordinate(-0.5));
    Files.writeString(directory.resolve("messages.yml"), "custom: changed\n");
    messages.reload();
    assertEquals("changed", messages.get("custom"));
  }

  JavaPlugin messagePlugin() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    when(plugin.getDataFolder()).thenReturn(directory.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    return plugin;
  }

  static ByteArrayInputStream stream(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void absentOrUnreadablePackagedMessagesDoNotHideUsableLiveMessages() throws Exception {
    JavaPlugin plugin = messagePlugin();
    Files.writeString(directory.resolve("messages.yml"), "custom: keep\n");
    assertEquals("keep", new Messages(plugin).get("custom"));
    when(plugin.getResource("messages.yml"))
        .thenAnswer(
            invocation ->
                new ByteArrayInputStream(new byte[0]) {
                  @Override
                  public void close() throws IOException {
                    throw new IOException("resource failure");
                  }
                });
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());
      assertEquals("keep", new Messages(plugin).get("custom"));
    }
  }

  @Test
  void durationParsingHandlesUnitsWhitespaceFractionsAndInvalidInput() {
    assertNotNull(new Utils());
    assertEquals(-1, Utils.parseDurationMillis(null));
    for (String invalid : List.of("", "-1h", "1w", "1h30m", "NaN", "  ")) {
      assertEquals(-1, Utils.parseDurationMillis(invalid), invalid);
    }
    assertEquals(1500, Utils.parseDurationMillis("1.5s"));
    assertEquals(12000, Utils.parseDurationMillis("12"));
    assertEquals(TimeUnit.MINUTES.toMillis(45), Utils.parseDurationMillis("45m"));
    assertEquals(TimeUnit.HOURS.toMillis(2), Utils.parseDurationMillis(" 2 H "));
    assertEquals(TimeUnit.DAYS.toMillis(1), Utils.parseDurationMillis("1d"));
  }

  @Test
  void durationFormattingRoundsUpAndUsesAppropriateLargestUnits() {
    assertEquals("0s", Utils.formatDuration(-1));
    assertEquals("0s", Utils.formatDuration(0));
    assertEquals("1s", Utils.formatDuration(1));
    assertEquals("1m", Utils.formatDuration(60000));
    assertEquals("1m 1s", Utils.formatDuration(60001));
    assertEquals("1h", Utils.formatDuration(3600000));
    assertEquals("1h 1m", Utils.formatDuration(3661000));
    assertEquals("2d", Utils.formatDuration(TimeUnit.DAYS.toMillis(2) + 60001));
    assertEquals(
        "2d 3h",
        Utils.formatDuration(TimeUnit.DAYS.toMillis(2) + TimeUnit.HOURS.toMillis(3) + 60001));
  }

  @Test
  void colorsAndCollectionEventPreservePublicContract() {
    assertEquals("plain", Utils.colorize("plain"));
    assertEquals("§aGreen", Utils.colorize("&aGreen"));
    assertEquals(
        "§x§f§f§0§0§a§aPink §x§f§f§0§0§a§aAgain", Utils.colorize("#ff00aaPink #ff00aaAgain"));
    Player player = mock(Player.class);
    Location location = new Location(mock(World.class), 1, 2, 3);
    var event = new GeigerSourceCollectEvent(player, location);
    assertSame(player, event.getPlayer());
    assertSame(location, event.getLocation());
    assertSame(GeigerSourceCollectEvent.getHandlerList(), event.getHandlers());
  }

  public static class PluginSubclass extends geiger_counter {}

  /** Satisfies Paper's plugin construction contract without starting a server. */
  private static final class TestPluginLoader extends ClassLoader
      implements ConfiguredPluginClassLoader {
    TestPluginLoader() {
      super(LifecycleUtilitiesTest.class.getClassLoader());
    }

    Class<?> definePluginSubclass() throws IOException {
      String name = LifecycleUtilitiesTest.class.getName() + "$PluginSubclass";
      try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
        assertNotNull(input);
        byte[] bytes = input.readAllBytes();
        return defineClass(name, bytes, 0, bytes.length);
      }
    }

    @Override
    public PluginMeta getConfiguration() {
      return null;
    }

    @Override
    public Class<?> loadClass(
        String name, boolean resolve, boolean checkGlobal, boolean checkLibraries)
        throws ClassNotFoundException {
      return super.loadClass(name, resolve);
    }

    @Override
    public void init(JavaPlugin plugin) {}

    @Override
    public JavaPlugin getPlugin() {
      return null;
    }

    @Override
    public PluginClassLoaderGroup getGroup() {
      return null;
    }

    @Override
    public void close() {}
  }
}
