package net.tfminecraft.geigercounters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.handlers.*;
import net.tfminecraft.geigercounters.utils.Hunters;
import net.tfminecraft.geigercounters.validators.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.mockito.*;

class GeigerManagerTest {
  JavaPlugin plugin;
  GeigerManager manager;
  SourceHandler source;
  GeigerValidator validator;
  ParticleRenderer particles;
  GeigerClickPlayer clicks;
  SignalBar bar;
  GeigerConfiguration config;
  World world;
  UUID hunter = UUID.randomUUID();

  @BeforeEach
  void setup() throws Exception {
    set(null, "instance", null);
    plugin = mock(JavaPlugin.class);
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    manager = GeigerManager.getInstance(plugin);
    source = mock(SourceHandler.class);
    validator = mock(GeigerValidator.class);
    particles = mock(ParticleRenderer.class);
    clicks = mock(GeigerClickPlayer.class);
    bar = mock(SignalBar.class);
    config = mock(GeigerConfiguration.class);
    world = mock(World.class);
    when(config.getWorld()).thenReturn(world);
    when(config.getRerollDistance()).thenReturn(1000.0);
    set(manager, "configuration", config);
    set(manager, "signalBar", bar);
    set(manager, "sourceHandler", source);
    set(manager, "validator", validator);
    set(manager, "particleRenderer", particles);
    set(manager, "clickPlayer", clicks);
  }

  @AfterEach
  void cleanup() throws Exception {
    set(null, "instance", null);
  }

  static void set(Object target, String name, Object value) throws Exception {
    Field f = GeigerManager.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  void check(Player... players) throws Exception {
    check(Optional.of(hunter), players);
  }

  void check(Optional<UUID> hunterId, Player... players) throws Exception {
    try (var b = mockStatic(Bukkit.class);
        var h = mockStatic(Hunters.class)) {
      b.when(Bukkit::getOnlinePlayers).thenReturn(Arrays.asList(players));
      h.when(() -> Hunters.of(any())).thenReturn(hunterId);
      Method m = GeigerManager.class.getDeclaredMethod("checkAllPlayers");
      m.setAccessible(true);
      m.invoke(manager);
    }
  }

  Player player(World w, boolean main, boolean off) {
    Player p = mock(Player.class);
    PlayerInventory inv = mock(PlayerInventory.class);
    when(p.getUniqueId()).thenReturn(UUID.randomUUID());
    when(p.getWorld()).thenReturn(w);
    when(p.getInventory()).thenReturn(inv);
    when(p.getLocation()).thenReturn(new Location(w, 3, 150, 4));
    ItemStack a = mock(ItemStack.class), b = mock(ItemStack.class);
    when(inv.getItemInMainHand()).thenReturn(a);
    when(inv.getItemInOffHand()).thenReturn(b);
    when(validator.isGeigerCounter(a)).thenReturn(main);
    when(validator.isGeigerCounter(b)).thenReturn(off);
    return p;
  }

  @Test
  void singletonAndGetters() {
    assertSame(manager, GeigerManager.getInstance());
    assertSame(manager, GeigerManager.getInstance(mock(JavaPlugin.class)));
    assertSame(plugin, manager.getPlugin());
    assertSame(source, manager.getSourceHandler());
    assertSame(clicks, manager.getClickPlayer());
  }

  @Test
  void playersOutsideTheSourceWorldOrWithoutACounterAreSkipped() throws Exception {
    Player other = player(mock(World.class), true, false), empty = player(world, false, false);
    check(other, empty);
    verifyNoInteractions(source, particles, clicks);
    verify(bar).retain(Set.of());
    when(config.getWorld()).thenReturn(null);
    check(player(world, true, false));
    verifyNoInteractions(source);
  }

  @Test
  void playersWithoutACharacterHaveNoHunt() throws Exception {
    check(Optional.empty(), player(world, true, false));
    verifyNoInteractions(source, particles, clicks);
  }

  @Test
  void missingSourceIsPlacedNearTheHunterOnce() throws Exception {
    Player p = player(world, true, false);
    check(p);
    verify(source).placeNear(hunter, p.getLocation());
    when(source.isPlacing(hunter)).thenReturn(true);
    check(p);
    verify(source, times(1)).placeNear(any(), any());
    verifyNoInteractions(particles, clicks);
    verify(bar, times(2)).retain(Set.of());
  }

  @Test
  void sourceTooFarOrInAnotherWorldIsPlacedAgain() throws Exception {
    Player p = player(world, true, false);
    when(source.getSource(hunter)).thenReturn(new Location(world, 1004, 64, 4));
    check(p);
    when(source.getSource(hunter)).thenReturn(new Location(mock(World.class), 3, 64, 4));
    check(p);
    verify(source, times(2)).placeNear(hunter, p.getLocation());
    verifyNoInteractions(particles, clicks);
  }

  @Test
  void bothHandsReadHorizontalDistanceAndKeepTheirBars() throws Exception {
    Location at = new Location(world, 0, -64, 0);
    when(source.getSource(hunter)).thenReturn(at);
    Player main = player(world, true, true), off = player(world, false, true);
    check(main, off);
    verify(source).tryCollectSource(main, hunter, 5, EquipmentSlot.HAND);
    verify(source).tryCollectSource(off, hunter, 5, EquipmentSlot.OFF_HAND);
    verify(particles).showParticleEffect(main, 5);
    verify(clicks).updateRate(off, 5);
    verify(bar).update(main, at, 5);
    verify(bar).retain(Set.of(main.getUniqueId(), off.getUniqueId()));
    verify(source, never()).placeNear(any(), any());
  }

  @Test
  void shutdownHandlesUninitializedAndInitializedComponents() throws Exception {
    set(manager, "clickPlayer", null);
    set(manager, "signalBar", null);
    set(manager, "sourceHandler", null);
    manager.shutdown();
    DropLimitManager limits = mock(DropLimitManager.class);
    set(manager, "dropLimitManager", limits);
    set(manager, "clickPlayer", clicks);
    set(manager, "signalBar", bar);
    set(manager, "sourceHandler", source);
    manager.shutdown();
    verify(limits).save();
    verify(clicks).clear();
    verify(bar).clear();
    verify(source).save();
    assertSame(limits, manager.getDropLimitManager());
  }

  @Test
  void reloadRefreshesConfiguration() throws Exception {
    GeigerConfiguration config = mock(GeigerConfiguration.class);
    set(manager, "configuration", config);
    manager.reload();
    verify(plugin).reloadConfig();
    verify(config).load();
    assertSame(config, manager.getConfiguration());
  }

  @Test
  void initializationLoadsPersistenceSpawnsSourceAndSchedulesBothTasks() {
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    ItemAPI api = mock(ItemAPI.class);
    try (var b = mockStatic(Bukkit.class);
        var t = mockStatic(TLibs.class);
        var configs = mockConstruction(GeigerConfiguration.class);
        var validators = mockConstruction(GeigerValidator.class);
        var filters = mockConstruction(SpawnLocationFilter.class);
        var renderers = mockConstruction(ParticleRenderer.class);
        var clickers = mockConstruction(GeigerClickPlayer.class);
        var bars = mockConstruction(SignalBar.class);
        var limits = mockConstruction(DropLimitManager.class);
        var sources = mockConstruction(SourceHandler.class)) {
      b.when(Bukkit::getScheduler).thenReturn(scheduler);
      t.when(TLibs::getItemAPI).thenReturn(api);
      manager.initialize();
      verify(configs.constructed().getFirst()).load();
      verify(limits.constructed().getFirst()).load();
      verify(sources.constructed().getFirst()).load();
      assertEquals(1, bars.constructed().size());
      ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class),
          click = ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler).runTaskTimer(eq(plugin), check.capture(), eq(0L), eq(5L));
      verify(scheduler).runTaskTimer(eq(plugin), click.capture(), eq(0L), eq(1L));
      when(configs.constructed().getFirst().getWorld()).thenReturn(world);
      b.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
      check.getValue().run();
      click.getValue().run();
      verify(clickers.constructed().getFirst()).tick();
    }
  }
}
