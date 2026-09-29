package net.tfminecraft.geigercounters.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.tfminecraft.geigercounters.config.*;
import net.tfminecraft.geigercounters.handlers.SourceHandler;
import net.tfminecraft.geigercounters.managers.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GeigerCommandTest {
  GeigerManager manager;
  GeigerConfiguration config;
  SourceHandler source;
  DropLimitManager limits;
  JavaPlugin plugin;
  CommandSender sender;
  GeigerCommand command;
  World world;

  @BeforeEach
  void setUp() {
    manager = mock(GeigerManager.class);
    config = mock(GeigerConfiguration.class);
    source = mock(SourceHandler.class);
    limits = mock(DropLimitManager.class);
    plugin = mock(JavaPlugin.class);
    sender = mock(CommandSender.class);
    world = mock(World.class);
    Messages messages = mock(Messages.class);
    when(manager.getConfiguration()).thenReturn(config);
    when(manager.getSourceHandler()).thenReturn(source);
    when(manager.getDropLimitManager()).thenReturn(limits);
    when(manager.getPlugin()).thenReturn(plugin);
    when(config.getMessages()).thenReturn(messages);
    when(messages.get(anyString(), any(Object[].class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(config.getDropListNames()).thenReturn(List.of("default", "Hunt"));
    when(config.isLimitEnabled()).thenReturn(true);
    when(plugin.getConfig()).thenReturn(new YamlConfiguration());
    when(world.getName()).thenReturn("world");
    command = new GeigerCommand(manager);
  }

  void run(String... args) {
    assertTrue(command.onCommand(sender, mock(Command.class), "geiger", args));
  }

  List<String> complete(CommandSender actor, String... args) {
    return command.onTabComplete(actor, mock(Command.class), "geiger", args);
  }

  @Test
  void manifestRestrictsCommandToAdministrators() throws Exception {
    try (var stream = getClass().getResourceAsStream("/plugin.yml")) {
      assertNotNull(stream);
      var yaml =
          YamlConfiguration.loadConfiguration(
              new InputStreamReader(stream, StandardCharsets.UTF_8));
      assertEquals("geiger.admin", yaml.getString("commands.geiger.permission"));
      assertEquals("op", yaml.getString("permissions.geiger.admin.default"));
    }
  }

  @Test
  void missingOrUnknownSubcommandShowsUsageAndReloadWorksCaseInsensitively() {
    run();
    run("unknown");
    verify(sender, times(2)).sendMessage("admin.usage");
    run("RELOAD");
    verify(manager).reload();
    verify(sender).sendMessage("admin.reloaded");
  }

  @Test
  void locateReportsRelocationOrCurrentCoordinates() {
    run("locate");
    verify(sender).sendMessage("admin.source-relocating");
    when(source.getSourceLocation()).thenReturn(new Location(world, -0.5, 72, 43.2));
    run("locate");
    verify(sender).sendMessage("admin.source-located");
  }

  @Test
  void randomMovementReportsSearchingThenSuccessOrFailureOnCompletion() {
    CompletableFuture<Location> pending = new CompletableFuture<>();
    when(source.moveSourceToRandomLocation()).thenReturn(pending);
    run("move");
    verify(sender).sendMessage("admin.move-searching");
    verify(sender, never()).sendMessage("admin.move-success");
    pending.complete(new Location(world, 12.3, 70, 42));
    verify(sender).sendMessage("admin.move-success");
    when(source.moveSourceToRandomLocation()).thenReturn(CompletableFuture.completedFuture(null));
    run("move");
    verify(sender).sendMessage("admin.move-failed");
  }

  @Test
  void movementValidatesArityAndNumbersAndAcceptsFiniteDecimals() {
    run("move", "1");
    run("move", "bad", "1");
    run("move", "1", "bad");
    verify(sender).sendMessage("admin.move-usage");
    verify(sender, times(2)).sendMessage("admin.move-invalid-coords");
    when(source.moveSourceToLocation(-0.5, 12.25))
        .thenReturn(CompletableFuture.completedFuture(new Location(world, -0.5, 70, 12.25)));
    run("move", "-0.5", "12.25");
    verify(source).moveSourceToLocation(-0.5, 12.25);
    verify(sender).sendMessage("admin.move-success");
  }

  @Test
  void movementRejectsNonFiniteCoordinatesWithoutLoadingChunks() {
    when(source.moveSourceToLocation(anyDouble(), anyDouble()))
        .thenReturn(CompletableFuture.completedFuture(null));
    for (String invalid : List.of("NaN", "Infinity", "-Infinity", "1e999")) {
      run("move", invalid, "1");
      run("move", "1", invalid);
    }
    verify(source, never()).moveSourceToLocation(anyDouble(), anyDouble());
    verify(sender, times(8)).sendMessage("admin.move-invalid-coords");
  }

  @Test
  void limitsHandleUsageDisabledAndUnknownPlayers() {
    run("limits");
    verify(sender).sendMessage("admin.limits-usage");
    when(config.isLimitEnabled()).thenReturn(false);
    run("limits", "Hunter");
    verify(sender).sendMessage("admin.limits-disabled");
    when(config.isLimitEnabled()).thenReturn(true);
    OfflinePlayer unknown = mock(OfflinePlayer.class);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getOfflinePlayer("Unknown")).thenReturn(unknown);
      run("limits", "Unknown");
      run("resetlimits", "Unknown");
    }
    verify(sender, times(2)).sendMessage("admin.unknown-player");
    verifyNoInteractions(limits);
  }

  @Test
  void limitsSupportOfflinePlayersNewOnlinePlayersAliasesAndNextSlotMessage() {
    OfflinePlayer target = mock(OfflinePlayer.class);
    UUID id = UUID.randomUUID();
    when(target.getUniqueId()).thenReturn(id);
    when(target.hasPlayedBefore()).thenReturn(true);
    when(limits.getRemaining(id)).thenReturn(2, 0);
    when(config.getLimitDrops()).thenReturn(3);
    when(config.getLimitWindowMillis()).thenReturn(60000L);
    when(limits.getMillisUntilNextDrop(id)).thenReturn(30000L);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getOfflinePlayer("Hunter")).thenReturn(target);
      run("limit", "Hunter");
      when(target.hasPlayedBefore()).thenReturn(false);
      when(target.isOnline()).thenReturn(true);
      run("limits", "Hunter");
      run("resetlimit", "Hunter");
      run("resetlimits", "Hunter");
    }
    verify(sender, times(2)).sendMessage("admin.limits-status");
    verify(sender).sendMessage("admin.limits-next-drop");
    verify(limits, times(2)).reset(id);
    run("resetlimits");
    verify(sender).sendMessage("admin.resetlimits-usage");
  }

  @Test
  void droplistShowsStatusValidatesInputAndPersistsSelection() {
    run("droplist");
    run("droplist", "a", "b");
    run("droplist", "missing");
    verify(sender).sendMessage("admin.droplist-current");
    verify(sender).sendMessage("admin.droplist-usage");
    verify(sender).sendMessage("admin.droplist-unknown");
    run("droplist", "Hunt");
    assertEquals("Hunt", plugin.getConfig().getString("drops.active-list"));
    verify(plugin).saveConfig();
    verify(manager).reload();
    verify(sender).sendMessage("admin.droplist-changed");
  }

  @Test
  void completionFiltersSubcommandsAndDroplistsAndIgnoresExtraArguments() {
    assertEquals(List.of("locate"), complete(sender, "LO"));
    assertEquals(6, complete(sender, "").size());
    assertEquals(List.of("Hunt"), complete(sender, "droplist", "h"));
    assertTrue(complete(sender, "unknown", "").isEmpty());
    assertTrue(complete(sender, "move", "1", "2", "").isEmpty());
    assertTrue(complete(sender, "droplist", "Hunt", "").isEmpty());
  }

  @Test
  void coordinateCompletionFloorsNegativeNumbersDeduplicatesAndUsesCorrectAxis() {
    when(config.getMinX()).thenReturn(-0.5);
    when(config.getMaxX()).thenReturn(10.8);
    when(config.getMinZ()).thenReturn(-20.3);
    when(config.getMaxZ()).thenReturn(40.2);
    Player actor = mock(Player.class);
    when(actor.getLocation()).thenReturn(new Location(world, -0.5, 64, 30.9));
    assertEquals(List.of("-1", "10"), complete(actor, "move", ""));
    assertEquals(List.of("30", "-21", "40"), complete(actor, "move", "0", ""));
    assertEquals(List.of("-21"), complete(sender, "move", "0", "-"));
    assertEquals(List.of("10"), complete(sender, "move", "1"));
  }

  @Test
  void allLimitAliasesCompleteOnlinePlayerNamesCaseInsensitively() {
    Player first = mock(Player.class), second = mock(Player.class);
    when(first.getName()).thenReturn("Hunter");
    when(second.getName()).thenReturn("Other");
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(first, second));
      for (String alias : List.of("limit", "limits", "resetlimit", "resetlimits")) {
        assertEquals(List.of("Hunter"), complete(sender, alias, "HU"));
      }
    }
  }
}
