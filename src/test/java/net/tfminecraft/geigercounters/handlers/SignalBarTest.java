package net.tfminecraft.geigercounters.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SignalBarTest {
  GeigerConfiguration config;
  SignalBar bar;
  World world;
  Player player;
  UUID id = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    config = mock(GeigerConfiguration.class);
    Messages messages = mock(Messages.class);
    when(config.getMessages()).thenReturn(messages);
    when(messages.get("player.signal-bar")).thenReturn("§x§a§a§0§0§f§fArcane signal");
    when(messages.get("player.signal-bar-close")).thenReturn("Dig close by");
    when(config.getPinpointRange()).thenReturn(10.0);
    when(config.isBarEnabled()).thenReturn(true);
    when(config.getBarRange()).thenReturn(100.0);
    when(config.getBarFacingWeight()).thenReturn(0.5);
    world = mock(World.class);
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(id);
    // Yaw 0 faces +Z
    when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0, 0, 0));
    bar = new SignalBar(config);
  }

  BossBar shown() {
    ArgumentCaptor<BossBar> captor = ArgumentCaptor.forClass(BossBar.class);
    verify(player).showBossBar(captor.capture());
    return captor.getValue();
  }

  // Closeness at 50 blocks on the 10..100 log scale
  static final double AT_50 = 1.0 - Math.log10(5);

  @Test
  void barIsShownOnceThenUpdatedInPlace() {
    bar.update(player, new Location(world, 0, 64, 50), 50);
    BossBar shown = shown();
    assertEquals(AT_50, shown.progress(), 1e-6);
    assertEquals(BossBar.Color.PURPLE, shown.color());
    assertEquals(BossBar.Overlay.NOTCHED_10, shown.overlay());
    bar.update(player, new Location(world, 0, 64, -50), 50);
    assertEquals(AT_50 / 2, shown.progress(), 1e-6);
    verify(player, times(1)).showBossBar(any(BossBar.class));
  }

  @Test
  void pinpointRetitlesTheBarAndShowsClosenessAlone() {
    bar.update(player, new Location(world, 0, 64, 50), 50);
    BossBar shown = shown();
    bar.pinpoint(player, 4);
    assertEquals(0.6f, shown.progress(), 1e-6);
    assertEquals("Dig close by", PlainTextComponentSerializer.plainText().serialize(shown.name()));
    bar.pinpoint(player, 30);
    assertEquals(0f, shown.progress(), 1e-6);
    bar.update(player, new Location(world, 0, 64, 50), 50);
    assertEquals(AT_50, shown.progress(), 1e-6);
    assertEquals("Arcane signal", PlainTextComponentSerializer.plainText().serialize(shown.name()));
    verify(player, times(1)).showBossBar(any(BossBar.class));
  }

  @Test
  void aPinpointReadingCanOpenTheBar() {
    bar.pinpoint(player, 0);
    assertEquals(1f, shown().progress(), 1e-6);
    assertEquals("Dig close by", PlainTextComponentSerializer.plainText().serialize(shown().name()));
  }

  @Test
  void disabledBarIsHidden() {
    bar.update(player, new Location(world, 0, 64, 50), 50);
    BossBar shown = shown();
    when(config.isBarEnabled()).thenReturn(false);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      bar.update(player, new Location(world, 0, 64, 50), 50);
      bar.update(player, new Location(world, 0, 64, 50), 50);
    }
    verify(player, times(1)).hideBossBar(shown);
  }

  @Test
  void retainHidesBarsOfPlayersNoLongerHuntingAndForgetsOfflineOnes() {
    Player other = mock(Player.class), gone = mock(Player.class);
    UUID otherId = UUID.randomUUID(), goneId = UUID.randomUUID();
    when(other.getUniqueId()).thenReturn(otherId);
    when(gone.getUniqueId()).thenReturn(goneId);
    Location here = player.getLocation();
    when(other.getLocation()).thenReturn(here);
    when(gone.getLocation()).thenReturn(here);
    Location source = new Location(world, 0, 64, 50);
    bar.update(player, source, 50);
    bar.update(other, source, 50);
    bar.update(gone, source, 50);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(otherId)).thenReturn(other);
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      bar.retain(Set.of(id));
      verify(other).hideBossBar(any(BossBar.class));
      verify(player, never()).hideBossBar(any(BossBar.class));
      bar.clear();
      verify(player).hideBossBar(any(BossBar.class));
    }
    verify(gone, never()).hideBossBar(any(BossBar.class));
    bar.update(gone, source, 50);
    verify(gone, times(2)).showBossBar(any(BossBar.class));
  }

  @Test
  void signalFallsWithDistanceAndPeaksWhenFacingTheSource() {
    Location from = player.getLocation();
    assertEquals(1.0, bar.signal(from, new Location(world, 0, 64, 1), 0), 1e-9);
    // Full from pinpoint range in, empty from bar range out
    assertEquals(1.0, bar.signal(from, new Location(world, 0, 64, 10), 10), 1e-9);
    assertEquals(0.0, bar.signal(from, new Location(world, 0, 64, 150), 150), 1e-9);
    when(config.getBarFacingWeight()).thenReturn(0.0);
    assertEquals(AT_50, bar.signal(from, new Location(world, 0, 64, -50), 50), 1e-9);
    // Every halving of the distance fills the same amount
    Location behind = new Location(world, 0, 64, -1);
    double at20 = bar.signal(from, behind, 20), at40 = bar.signal(from, behind, 40);
    assertEquals(at20 - at40, at40 - bar.signal(from, behind, 80), 1e-9);
  }

  @Test
  void facingFollowsYawOnly() {
    Location from = new Location(world, 0, 64, 0, 0, -80);
    assertEquals(1.0, SignalBar.facing(from, new Location(world, 0, 0, 10)), 1e-9);
    assertEquals(0.0, SignalBar.facing(from, new Location(world, 0, 0, -10)), 1e-9);
    assertEquals(0.25, SignalBar.facing(from, new Location(world, 10, 0, 0)), 1e-9);
    // Yaw 90 faces -X
    Location west = new Location(world, 0, 64, 0, 90, 0);
    assertEquals(1.0, SignalBar.facing(west, new Location(world, -10, 0, 0)), 1e-9);
    assertEquals(1.0, SignalBar.facing(from, from.clone()), 1e-9);
  }
}
