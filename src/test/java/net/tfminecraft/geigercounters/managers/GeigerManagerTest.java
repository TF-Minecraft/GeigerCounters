package net.tfminecraft.geigercounters.managers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.handlers.*;
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
  World world;

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
    world = mock(World.class);
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
    try (var b = mockStatic(Bukkit.class)) {
      b.when(Bukkit::getOnlinePlayers).thenReturn(Arrays.asList(players));
      Method m = GeigerManager.class.getDeclaredMethod("checkAllPlayers");
      m.setAccessible(true);
      m.invoke(manager);
    }
  }

  Player player(World w, boolean main, boolean off) {
    Player p = mock(Player.class);
    PlayerInventory inv = mock(PlayerInventory.class);
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
  void noSourceDoesNotInspectPlayers() throws Exception {
    Player p = mock(Player.class);
    check(p);
    verifyNoInteractions(p, particles, clicks);
  }

  @Test
  void bothHandsAndNoCounterUseHorizontalDistance() throws Exception {
    when(source.getSourceLocation()).thenReturn(new Location(world, 0, -64, 0));
    Player main = player(world, true, true),
        off = player(world, false, true),
        neither = player(world, false, false);
    check(main, off, neither);
    verify(source).tryCollectSource(main, 5, EquipmentSlot.HAND);
    verify(source).tryCollectSource(off, 5, EquipmentSlot.OFF_HAND);
    verify(particles).showParticleEffect(main, 5);
    verify(clicks).updateRate(off, 5);
    verify(source, never()).tryCollectSource(eq(neither), anyDouble(), any());
  }

  @Test
  void playersInOtherWorldCannotDetectOrCollectSource() throws Exception {
    when(source.getSourceLocation()).thenReturn(new Location(world, 0, 64, 0));
    Player other = player(mock(World.class), true, false);
    check(other);
    verify(source, never()).tryCollectSource(eq(other), anyDouble(), any());
    verifyNoInteractions(particles, clicks);
  }

  @Test
  void refreshesSourceForEachPlayerWhenRelocationCompletesImmediately() throws Exception {
    Location first = new Location(world, 0, 64, 0), next = new Location(world, 1003, 64, 4);
    when(source.getSourceLocation()).thenReturn(first);
    Player a = player(world, true, false), b = player(world, true, false);
    doAnswer(
            i -> {
              when(source.getSourceLocation()).thenReturn(next);
              return null;
            })
        .when(source)
        .tryCollectSource(a, 5, EquipmentSlot.HAND);
    check(a, b);
    verify(source).tryCollectSource(b, 1000, EquipmentSlot.HAND);
  }

  @Test
  void shutdownHandlesUninitializedAndInitializedComponents() throws Exception {
    set(manager, "clickPlayer", null);
    manager.shutdown();
    DropLimitManager limits = mock(DropLimitManager.class);
    set(manager, "dropLimitManager", limits);
    set(manager, "clickPlayer", clicks);
    manager.shutdown();
    verify(limits).save();
    verify(clicks).clear();
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
        var limits = mockConstruction(DropLimitManager.class);
        var sources = mockConstruction(SourceHandler.class)) {
      b.when(Bukkit::getScheduler).thenReturn(scheduler);
      t.when(TLibs::getItemAPI).thenReturn(api);
      manager.initialize();
      verify(configs.constructed().getFirst()).load();
      verify(limits.constructed().getFirst()).load();
      verify(sources.constructed().getFirst()).moveSourceToRandomLocation();
      ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class),
          click = ArgumentCaptor.forClass(Runnable.class);
      verify(scheduler).runTaskTimer(eq(plugin), check.capture(), eq(0L), eq(5L));
      verify(scheduler).runTaskTimer(eq(plugin), click.capture(), eq(0L), eq(1L));
      check.getValue().run();
      click.getValue().run();
      verify(clickers.constructed().getFirst()).tick();
    }
  }
}
