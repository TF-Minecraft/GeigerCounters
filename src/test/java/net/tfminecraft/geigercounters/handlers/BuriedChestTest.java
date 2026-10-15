package net.tfminecraft.geigercounters.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class BuriedChestTest {
  BuriedChest.Actions actions;
  BuriedChest chest;
  World world;
  Player player;
  UUID id = UUID.randomUUID();
  BlockData chestData;
  BlockData stone;
  MockedStatic<Bukkit> bukkit;
  // The cache centre, and the block it sits in
  Location cache;
  Location block;

  @BeforeEach
  void setUp() {
    actions = mock(BuriedChest.Actions.class);
    chest = new BuriedChest(actions);
    world = mock(World.class);
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(id);
    when(player.getWorld()).thenReturn(world);
    when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    stone = mock(BlockData.class);
    Block real = mock(Block.class);
    when(real.getBlockData()).thenReturn(stone);
    when(world.getBlockAt(any(Location.class))).thenReturn(real);
    chestData = mock(BlockData.class);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(() -> Bukkit.createBlockData(eq(Material.CHEST), any(Consumer.class))).thenReturn(chestData);
    bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
    cache = new Location(world, 10.5, 60.5, -20.5);
    block = new Location(world, 10, 60, -21);
  }

  @AfterEach
  void tearDown() {
    bukkit.close();
  }

  Block blockAt(Location location) {
    Block clicked = mock(Block.class);
    when(clicked.getLocation()).thenReturn(location);
    return clicked;
  }

  void keep(int checks) {
    for (int check = 0; check < checks; check++) {
      chest.retain(Set.of(id));
    }
  }

  @Test
  void theChestIsSentOnceThenResentEveryFewChecks() {
    chest.show(player, cache);
    verify(player).sendBlockChange(block, chestData);
    keep(BuriedChest.RESEND_CHECKS - 1);
    chest.show(player, cache);
    verify(player, times(1)).sendBlockChange(any(Location.class), any(BlockData.class));
    keep(1);
    chest.show(player, cache);
    verify(player, times(2)).sendBlockChange(block, chestData);
  }

  @Test
  void aMovedCachePutsTheOldBlockBack() {
    chest.show(player, cache);
    Location moved = new Location(world, 40.5, 50.5, 7.5);
    chest.show(player, moved);
    verify(player).sendBlockChange(block, stone);
    verify(player).sendBlockChange(new Location(world, 40, 50, 7), chestData);
  }

  @Test
  void theChestFacesTheSameWayEverySend() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Consumer<BlockData>> shape = ArgumentCaptor.forClass(Consumer.class);
    chest.show(player, cache);
    chest.show(player, new Location(world, -1.5, 60.5, 0.5));
    bukkit.verify(() -> Bukkit.createBlockData(eq(Material.CHEST), shape.capture()), times(2));
    Chest east = mock(Chest.class), south = mock(Chest.class);
    shape.getAllValues().get(0).accept(east);
    shape.getAllValues().get(1).accept(south);
    // 10 * 31 - 21 = 289, 289 mod 4 = 1; -2 * 31 + 0 = -62, floorMod 4 = 2
    verify(east).setFacing(BlockFace.EAST);
    verify(south).setFacing(BlockFace.SOUTH);
  }

  @Test
  void retainPutsTheRealBlockBackOnlyWhereThePlayerCanSeeIt() {
    chest.show(player, cache);
    chest.retain(Set.of());
    verify(player).sendBlockChange(block, stone);

    // Unloaded chunk, another world, offline: nothing to put back
    when(world.isChunkLoaded(0, -2)).thenReturn(false);
    chest.show(player, cache);
    chest.retain(Set.of());
    when(world.isChunkLoaded(0, -2)).thenReturn(true);
    when(player.getWorld()).thenReturn(mock(World.class));
    chest.show(player, cache);
    chest.clear();
    bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);
    chest.show(player, cache);
    chest.clear();
    verify(player, times(1)).sendBlockChange(block, stone);
  }

  @Test
  void clickingTheShownChestOpensItAndNothingElseDoes() {
    PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    when(event.getPlayer()).thenReturn(player);
    chest.onInteract(event);
    Block at1 = blockAt(block);
    when(event.getClickedBlock()).thenReturn(at1);
    chest.onInteract(event);
    chest.show(player, cache);
    Block at2 = blockAt(new Location(world, 10, 61, -21));
    when(event.getClickedBlock()).thenReturn(at2);
    chest.onInteract(event);
    verify(event, never()).setCancelled(anyBoolean());
    verifyNoInteractions(actions);
    Block at3 = blockAt(block);
    when(event.getClickedBlock()).thenReturn(at3);
    chest.onInteract(event);
    verify(event).setCancelled(true);
    verify(actions).open(player);
  }

  @Test
  void breakingTheChestBlockIsRefusedAndTheChestResent() {
    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(player);
    Block at4 = blockAt(block);
    when(event.getBlock()).thenReturn(at4);
    chest.onBreakCache(event);
    verify(event, never()).setCancelled(anyBoolean());
    chest.show(player, cache);
    chest.onBreakCache(event);
    verify(event).setCancelled(true);
    chest.show(player, cache);
    verify(player, times(2)).sendBlockChange(block, chestData);
  }

  @Test
  void aRefusedDigBesideTheCacheMeansTheGroundIsProtected() {
    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(player);
    Block at5 = blockAt(new Location(world, 10, 63, -21));
    when(event.getBlock()).thenReturn(at5);
    when(event.isCancelled()).thenReturn(true);
    chest.onProtectedDig(event);
    chest.show(player, cache);
    when(event.isCancelled()).thenReturn(false);
    chest.onProtectedDig(event);
    when(event.isCancelled()).thenReturn(true);
    // The cache block itself, another world, too far away
    Block at6 = blockAt(block);
    when(event.getBlock()).thenReturn(at6);
    chest.onProtectedDig(event);
    Block at7 = blockAt(new Location(mock(World.class), 10, 63, -21));
    when(event.getBlock()).thenReturn(at7);
    chest.onProtectedDig(event);
    Block at8 = blockAt(new Location(world, 10, 64, -21));
    when(event.getBlock()).thenReturn(at8);
    chest.onProtectedDig(event);
    verifyNoInteractions(actions);
    Block at9 = blockAt(new Location(world, 10, 63, -21));
    when(event.getBlock()).thenReturn(at9);
    chest.onProtectedDig(event);
    verify(actions).groundProtected(player);
    assertEquals(3.0, new Location(world, 10, 63, -21).distance(block));
  }
}
