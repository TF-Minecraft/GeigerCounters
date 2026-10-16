package net.tfminecraft.geigercounters.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.tfminecraft.geigercounters.config.*;
import net.tfminecraft.geigercounters.handlers.SourceHandler;
import net.tfminecraft.geigercounters.managers.*;
import net.tfminecraft.geigercounters.utils.Hunters;
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
    command = new GeigerCommand(manager, new OfflinePlayer[0]);
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

  Player online(String name) {
    Player target = mock(Player.class);
    when(target.getName()).thenReturn(name);
    when(target.getLocation()).thenReturn(new Location(world, 1, 64, 2));
    return target;
  }

  // Runs with Hunter online as <target> playing <hunter>
  void runAs(Player target, Optional<UUID> hunter, String... args) {
    try (var bukkit = mockStatic(Bukkit.class);
        var hunters = mockStatic(Hunters.class)) {
      bukkit.when(() -> Bukkit.getPlayerExact("Hunter")).thenReturn(target);
      hunters.when(() -> Hunters.of(target)).thenReturn(hunter);
      run(args);
    }
  }

  @Test
  void locateValidatesUsageOnlineTargetAndCharacter() {
    run("locate");
    run("locate", "a", "b");
    verify(sender, times(2)).sendMessage("admin.locate-usage");
    runAs(null, Optional.empty(), "locate", "Hunter");
    verify(sender).sendMessage("admin.player-offline");
    runAs(online("Hunter"), Optional.empty(), "locate", "Hunter");
    verify(sender).sendMessage("admin.no-character");
    verifyNoInteractions(source);
  }

  @Test
  void locateReportsPlacingMissingOrCurrentSourceIncludingOwn() {
    UUID hunter = UUID.randomUUID();
    Player target = online("Hunter");
    when(source.isPlacing(hunter)).thenReturn(true, false);
    runAs(target, Optional.of(hunter), "locate", "Hunter");
    verify(sender).sendMessage("admin.source-relocating");
    runAs(target, Optional.of(hunter), "locate", "Hunter");
    verify(sender).sendMessage("admin.no-source");
    when(source.getSource(hunter)).thenReturn(new Location(world, -0.5, 72, 43.2));
    try (var hunters = mockStatic(Hunters.class)) {
      hunters.when(() -> Hunters.of(target)).thenReturn(Optional.of(hunter));
      assertTrue(command.onCommand(target, mock(Command.class), "geiger", new String[] {"locate"}));
    }
    verify(target).sendMessage("admin.source-located");
  }

  @Test
  void moveWithoutCoordinatesClearsTheSource() {
    UUID hunter = UUID.randomUUID();
    runAs(online("Hunter"), Optional.of(hunter), "move", "Hunter");
    verify(source).clear(hunter);
    verify(sender).sendMessage("admin.move-cleared");
  }

  @Test
  void moveToCoordinatesReportsSuccessOrFailureOnCompletion() {
    UUID hunter = UUID.randomUUID();
    CompletableFuture<Location> pending = new CompletableFuture<>();
    when(source.placeAt(hunter, -0.5, 12.25)).thenReturn(pending);
    runAs(online("Hunter"), Optional.of(hunter), "move", "Hunter", "-0.5", "12.25");
    verify(sender, never()).sendMessage("admin.move-success");
    pending.complete(new Location(world, -0.5, 70, 12.25));
    verify(sender).sendMessage("admin.move-success");
    when(source.placeAt(hunter, 1, 2)).thenReturn(CompletableFuture.completedFuture(null));
    runAs(online("Hunter"), Optional.of(hunter), "move", "Hunter", "1", "2");
    verify(sender).sendMessage("admin.move-failed");
  }

  @Test
  void moveValidatesArityNumbersAndTarget() {
    run("move");
    run("move", "Hunter", "1");
    run("move", "Hunter", "bad", "1");
    run("move", "Hunter", "1", "bad");
    verify(sender, times(2)).sendMessage("admin.move-usage");
    verify(sender, times(2)).sendMessage("admin.move-invalid-coords");
    runAs(null, Optional.empty(), "move", "Hunter");
    verify(sender).sendMessage("admin.player-offline");
    runAs(online("Hunter"), Optional.empty(), "move", "Hunter", "1", "2");
    verify(sender).sendMessage("admin.no-character");
    verifyNoInteractions(source);
  }

  @Test
  void moveRejectsNonFiniteCoordinatesWithoutLoadingChunks() {
    for (String invalid : List.of("NaN", "Infinity", "-Infinity", "1e999")) {
      run("move", "Hunter", invalid, "1");
      run("move", "Hunter", "1", invalid);
    }
    verifyNoInteractions(source);
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
  void modeShowsTheCurrentModeValidatesInputAndPersistsTheSwitch() {
    when(config.getHuntMode()).thenReturn(GeigerConfiguration.HuntMode.PLAYER);
    run("mode");
    verify(sender).sendMessage("admin.mode-current");
    run("mode", "everyone");
    run("mode", "global", "now");
    verify(sender, times(2)).sendMessage("admin.mode-usage");
    verify(manager, never()).reload();
    run("mode", "GLOBAL");
    assertEquals("global", plugin.getConfig().getString("source.mode"));
    verify(plugin).saveConfig();
    verify(manager).reload();
    verify(sender).sendMessage("admin.mode-changed");
  }

  @Test
  void globalLocateReportsTheSharedSourceToAnyone() {
    when(config.getHuntMode()).thenReturn(GeigerConfiguration.HuntMode.GLOBAL);
    when(source.isPlacing(SourceHandler.SHARED)).thenReturn(true, false);
    run("locate");
    verify(sender).sendMessage("admin.shared-source-relocating");
    run("locate", "Hunter");
    verify(sender).sendMessage("admin.shared-no-source");
    when(source.getSource(SourceHandler.SHARED)).thenReturn(new Location(world, 5, 40, -7));
    run("locate");
    verify(sender).sendMessage("admin.shared-source-located");
  }

  @Test
  void globalMoveClearsOrBuriesTheSharedSource() {
    when(config.getHuntMode()).thenReturn(GeigerConfiguration.HuntMode.GLOBAL);
    run("move", "Hunter");
    run("move", "Hunter", "1", "2");
    verify(sender, times(2)).sendMessage("admin.shared-move-usage");
    run("move", "bad", "2");
    verify(sender).sendMessage("admin.move-invalid-coords");
    verifyNoInteractions(source);
    run("move");
    verify(source).clear(SourceHandler.SHARED);
    verify(sender).sendMessage("admin.shared-move-cleared");
    CompletableFuture<Location> pending = new CompletableFuture<>();
    when(source.placeAt(SourceHandler.SHARED, 3, -4)).thenReturn(pending);
    run("move", "3", "-4");
    verify(sender, never()).sendMessage("admin.shared-move-success");
    pending.complete(new Location(world, 3, 50, -4));
    verify(sender).sendMessage("admin.shared-move-success");
    when(source.placeAt(SourceHandler.SHARED, 1, 2)).thenReturn(CompletableFuture.completedFuture(null));
    run("move", "1", "2");
    verify(sender).sendMessage("admin.move-failed");
  }

  @Test
  void globalCompletionSkipsPlayersForLocateAndMoveAndOffersModes() {
    when(config.getHuntMode()).thenReturn(GeigerConfiguration.HuntMode.GLOBAL);
    when(config.getMinX()).thenReturn(-5.0);
    when(config.getMaxX()).thenReturn(5.0);
    when(config.getMinZ()).thenReturn(-9.0);
    when(config.getMaxZ()).thenReturn(9.0);
    Player online = mock(Player.class);
    when(online.getName()).thenReturn("Hunter");
    when(online.getUniqueId()).thenReturn(UUID.randomUUID());
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(online));
      assertTrue(complete(sender, "locate", "").isEmpty());
      assertEquals(List.of("-5", "5"), complete(sender, "move", ""));
      assertEquals(List.of("-9", "9"), complete(sender, "move", "1", ""));
      assertTrue(complete(sender, "move", "1", "2", "").isEmpty());
      assertEquals(List.of("Hunter"), complete(sender, "limits", "h"));
    }
    assertEquals(List.of("player", "global"), complete(sender, "mode", ""));
    assertEquals(List.of("global"), complete(sender, "mode", "G"));
    assertTrue(complete(sender, "mode", "global", "").isEmpty());
  }

  @Test
  void completionFiltersSubcommandsAndDroplistsAndIgnoresExtraArguments() {
    assertEquals(List.of("locate"), complete(sender, "LO"));
    assertEquals(7, complete(sender, "").size());
    assertEquals(List.of("Hunt"), complete(sender, "droplist", "h"));
    assertTrue(complete(sender, "unknown", "").isEmpty());
    assertTrue(complete(sender, "move", "Hunter", "1", "2", "").isEmpty());
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
    assertEquals(List.of("-1", "10"), complete(actor, "move", "Hunter", ""));
    assertEquals(List.of("30", "-21", "40"), complete(actor, "move", "Hunter", "0", ""));
    assertEquals(List.of("-21"), complete(sender, "move", "Hunter", "0", "-"));
    assertEquals(List.of("10"), complete(sender, "move", "Hunter", "1"));
  }

  @Test
  void playerArgumentsCompleteOnlinePlayerNamesCaseInsensitively() {
    Player first = mock(Player.class), second = mock(Player.class);
    when(first.getName()).thenReturn("Hunter");
    when(first.getUniqueId()).thenReturn(UUID.randomUUID());
    when(second.getName()).thenReturn("Other");
    when(second.getUniqueId()).thenReturn(UUID.randomUUID());
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(first, second));
      for (String alias : List.of("locate", "move", "limit", "limits", "resetlimit", "resetlimits")) {
        assertEquals(List.of("Hunter"), complete(sender, alias, "HU"));
      }
    }
  }

  @Test
  void limitCompletionUsesKnownOfflineNamesAndJoinUpdatesWithoutProfileLookups() {
    UUID knownId = UUID.randomUUID();
    OfflinePlayer known = mock(OfflinePlayer.class), unnamed = mock(OfflinePlayer.class),
        unknown = mock(OfflinePlayer.class);
    when(known.getUniqueId()).thenReturn(knownId);
    when(known.getName()).thenReturn("OldName");
    when(known.hasPlayedBefore()).thenReturn(true);
    when(unknown.getName()).thenReturn("NeverPlayed");
    command = new GeigerCommand(manager, new OfflinePlayer[] {known, unnamed, unknown});
    Player joined = mock(Player.class), online = mock(Player.class);
    when(joined.getUniqueId()).thenReturn(knownId);
    when(joined.getName()).thenReturn("RenamedHunter");
    when(online.getUniqueId()).thenReturn(UUID.randomUUID());
    when(online.getName()).thenReturn("Online");
    var join = mock(org.bukkit.event.player.PlayerJoinEvent.class);
    when(join.getPlayer()).thenReturn(joined);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(online));
      for (String action : List.of("limit", "limits", "resetlimit", "resetlimits")) {
        assertEquals(List.of("OldName", "Online"), complete(sender, action, ""));
        assertEquals(List.of("OldName"), complete(sender, action.toUpperCase(java.util.Locale.ROOT), "oLD"));
        assertTrue(complete(sender, action, "absent").isEmpty());
        assertTrue(complete(sender, action, "OldName", "").isEmpty());
      }
      for (String action : List.of("locate", "move")) {
        assertEquals(List.of("Online"), complete(sender, action, ""));
        assertTrue(complete(sender, action, "Old").isEmpty());
      }
      command.onJoin(join);
      assertEquals(List.of("RenamedHunter"), complete(sender, "limits", "rEN"));
      assertTrue(complete(sender, "resetlimits", "Old").isEmpty());
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(joined, online));
      assertEquals(List.of("RenamedHunter", "Online"), complete(sender, "limits", ""));
      assertTrue(complete(sender).isEmpty());
      bukkit.verify(Bukkit::getOfflinePlayers, never());
      bukkit.verify(() -> Bukkit.getOfflinePlayer(anyString()), never());
    }
  }
}
