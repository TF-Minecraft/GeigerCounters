package net.tfminecraft.geigercounters.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.UUID;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GeigerEffectsTest {
  GeigerConfiguration config;
  Player player;
  World world;
  UUID id;

  @BeforeEach
  void setUp() {
    config = mock(GeigerConfiguration.class);
    player = mock(Player.class);
    world = mock(World.class);
    id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(player.isOnline()).thenReturn(true);
    when(player.getLocation()).thenAnswer(invocation -> new Location(world, 10, 64, 20));
    when(config.getMaxDetectionDistance()).thenReturn(1000.0);
    when(config.getCloseRangeThreshold()).thenReturn(50.0);
    when(config.getThreeRingsDistance()).thenReturn(100.0);
    when(config.getTwoRingsDistance()).thenReturn(300.0);
    when(config.getCloseRangeStartColor()).thenReturn(new GeigerConfiguration.ColorConfig(0, 0, 0));
    when(config.getCloseRangeEndColor())
        .thenReturn(new GeigerConfiguration.ColorConfig(200, 100, 50));
    when(config.getFarRangeStartColor())
        .thenReturn(new GeigerConfiguration.ColorConfig(200, 100, 50));
    when(config.getFarRangeEndColor()).thenReturn(new GeigerConfiguration.ColorConfig(20, 10, 0));
    when(config.isSoundEnabled()).thenReturn(true);
    when(config.getSoundMinRate()).thenReturn(1.0);
    when(config.getSoundMaxRate()).thenReturn(20.0);
    when(config.getSoundCurve()).thenReturn(2.0);
    when(config.getSoundName()).thenReturn("block.note_block.hat");
    when(config.getSoundVolume()).thenReturn(0.35);
    when(config.getSoundPitch()).thenReturn(1.0);
  }

  @Test
  void particlesOutsideDetectionRangeDoNotReadPlayerPosition() {
    new ParticleRenderer(config).showParticleEffect(player, 1000.1);
    verify(player, never()).getLocation();
  }

  @Test
  void closeRangeParticlesInterpolateColorsAndFormThreeRingsAtExpectedHeight() {
    new ParticleRenderer(config).showParticleEffect(player, 25);
    ArgumentCaptor<Location> locations = ArgumentCaptor.forClass(Location.class);
    ArgumentCaptor<Particle.DustOptions> dust = ArgumentCaptor.forClass(Particle.DustOptions.class);
    verify(player, times(108))
        .spawnParticle(eq(Particle.DUST), locations.capture(), eq(1), dust.capture());
    for (int index = 0; index < locations.getAllValues().size(); index++) {
      Location point = locations.getAllValues().get(index);
      assertEquals(65.2, point.getY(), 0.00001);
      assertEquals(
          0.3 + (index / 36) * 0.1, Math.hypot(point.getX() - 10, point.getZ() - 20), 0.00001);
      assertSame(world, point.getWorld());
      assertEquals(Color.fromRGB(100, 50, 25), dust.getAllValues().get(index).getColor());
      assertEquals(1f, dust.getAllValues().get(index).getSize());
    }
  }

  @Test
  void farRangeParticlesHonorInclusiveRingThresholdsAndEndColor() {
    ParticleRenderer renderer = new ParticleRenderer(config);
    double[] distances = {50, 100, 100.1, 300, 300.1, 1000};
    int[] rings = {3, 3, 2, 2, 1, 1};
    for (int index = 0; index < distances.length; index++) {
      clearInvocations(player);
      renderer.showParticleEffect(player, distances[index]);
      ArgumentCaptor<Particle.DustOptions> dust =
          ArgumentCaptor.forClass(Particle.DustOptions.class);
      verify(player, times(rings[index] * 36))
          .spawnParticle(eq(Particle.DUST), any(Location.class), eq(1), dust.capture());
      if (distances[index] == 1000) {
        assertEquals(Color.fromRGB(20, 10, 0), dust.getValue().getColor());
      }
    }
  }

  GeigerClickPlayer clicker(double randomValue) throws Exception {
    GeigerClickPlayer clicker = new GeigerClickPlayer(config);
    Random random = mock(Random.class);
    when(random.nextDouble()).thenReturn(randomValue);
    Field field = GeigerClickPlayer.class.getDeclaredField("random");
    field.setAccessible(true);
    field.set(clicker, random);
    return clicker;
  }

  @Test
  void soundRateFallsWithDistanceAndStopsOutsideDetectionRange() throws Exception {
    GeigerClickPlayer clicker = clicker(0.5);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      clicker.updateRate(player, 0);
      clicker.tick();
      verify(player)
          .playSound(
              any(Location.class),
              eq("block.note_block.hat"),
              eq(SoundCategory.PLAYERS),
              eq(0.35f),
              eq(1f));
      clearInvocations(player);
      clicker.updateRate(player, 1000);
      clicker.tick();
      clicker.updateRate(player, 1000.1);
      clicker.tick();
      when(config.getMaxDetectionDistance()).thenReturn(0.0);
      clicker.updateRate(player, 0);
      clicker.tick();
      verify(player, never())
          .playSound(
              any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
    }
  }

  @Test
  void ratesExpireAfterFifteenTicksAndForgetAndClearRemoveThemImmediately() throws Exception {
    GeigerClickPlayer clicker = clicker(0.0);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      clicker.updateRate(player, 0);
      for (int tick = 0; tick < 16; tick++) {
        clicker.tick();
      }
      verify(player, times(15))
          .playSound(
              any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
      clearInvocations(player);
      clicker.updateRate(player, 0);
      clicker.forget(id);
      clicker.tick();
      clicker.updateRate(player, 0);
      clicker.clear();
      clicker.tick();
      verify(player, never())
          .playSound(
              any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
    }
  }

  @Test
  void disabledSoundClearsExistingStatesAndIgnoresRateUpdates() throws Exception {
    GeigerClickPlayer clicker = clicker(0.0);
    clicker.updateRate(player, 0);
    when(config.isSoundEnabled()).thenReturn(false);
    clicker.updateRate(player, 0);
    clicker.tick();
    clicker.tick();
    when(config.isSoundEnabled()).thenReturn(true);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      clicker.tick();
      bukkit.verify(() -> Bukkit.getPlayer(id), never());
    }
  }

  @Test
  void missingAndOfflinePlayersAreRemoved() throws Exception {
    GeigerClickPlayer clicker = clicker(0.0);
    try (var bukkit = mockStatic(Bukkit.class)) {
      clicker.updateRate(player, 0);
      clicker.tick();
      bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
      when(player.isOnline()).thenReturn(false);
      clicker.updateRate(player, 0);
      clicker.tick();
      when(player.isOnline()).thenReturn(true);
      clicker.tick();
      verify(player, never())
          .playSound(
              any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
    }
  }

  @Test
  void zeroRatesRemoveStateAndHighRatesCapAtOneClickPerTickWithClampedPitch() throws Exception {
    when(config.getSoundMaxRate()).thenReturn(100.0);
    when(config.getSoundPitchVariance()).thenReturn(10.0);
    for (double randomValue : new double[] {0.0, 0.999}) {
      GeigerClickPlayer clicker = clicker(randomValue);
      clearInvocations(player);
      try (var bukkit = mockStatic(Bukkit.class)) {
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        clicker.updateRate(player, 0);
        clicker.tick();
        verify(player)
            .playSound(
                any(Location.class),
                anyString(),
                eq(SoundCategory.PLAYERS),
                eq(0.35f),
                eq(randomValue == 0 ? 0.5f : 2f));
      }
    }
    when(config.getSoundMaxRate()).thenReturn(0.0);
    when(config.getSoundMinRate()).thenReturn(0.0);
    GeigerClickPlayer clicker = clicker(0.0);
    clicker.updateRate(player, 0);
    try (var bukkit = mockStatic(Bukkit.class)) {
      clicker.tick();
      bukkit.verifyNoInteractions();
    }
  }
}
