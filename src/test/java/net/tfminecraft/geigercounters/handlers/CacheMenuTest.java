package net.tfminecraft.geigercounters.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CacheMenuTest {
  CacheMenu menu;
  Player player;
  PlayerInventory pockets;
  Inventory window;

  @BeforeEach
  void setUp() {
    GeigerConfiguration config = mock(GeigerConfiguration.class);
    Messages messages = mock(Messages.class);
    when(config.getMessages()).thenReturn(messages);
    when(messages.get("player.cache-title")).thenReturn("Arcane Cache");
    menu = new CacheMenu(config);
    player = mock(Player.class);
    pockets = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(pockets);
    when(pockets.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
    window = mock(Inventory.class);
    when(window.getContents()).thenReturn(new ItemStack[0]);
    when(player.openInventory(any(Inventory.class))).thenReturn(mock(InventoryView.class));
  }

  InventoryHolder open(ItemStack reward) {
    ArgumentCaptor<InventoryHolder> holder = ArgumentCaptor.forClass(InventoryHolder.class);
    ArgumentCaptor<Component> title = ArgumentCaptor.forClass(Component.class);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit
          .when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(27), any(Component.class)))
          .thenReturn(window);
      menu.open(player, reward);
      bukkit.verify(() -> Bukkit.createInventory(holder.capture(), eq(27), title.capture()));
    }
    assertEquals("Arcane Cache", PlainTextComponentSerializer.plainText().serialize(title.getValue()));
    assertSame(window, holder.getValue().getInventory());
    verify(player).openInventory(window);
    return holder.getValue();
  }

  ItemStack item(boolean empty) {
    ItemStack item = mock(ItemStack.class);
    when(item.isEmpty()).thenReturn(empty);
    return item;
  }

  @Test
  void theRewardSitsInTheMiddleOfTheCache() {
    ItemStack reward = item(false);
    open(reward);
    verify(window).setItem(13, reward);
  }

  @Test
  void anEmptyCacheStillOpens() {
    open(null);
    verify(window, never()).setItem(anyInt(), any());
  }

  @Test
  void whateverIsLeftWhenTheCacheClosesGoesToThePlayer() {
    InventoryHolder holder = open(null);
    ItemStack left = item(false), air = item(true);
    when(window.getHolder()).thenReturn(holder);
    when(window.getContents()).thenReturn(new ItemStack[] {null, air, left});
    InventoryCloseEvent event = mock(InventoryCloseEvent.class);
    when(event.getInventory()).thenReturn(window);
    when(event.getPlayer()).thenReturn(player);
    menu.onClose(event);
    verify(window).clear();
    verify(pockets).addItem(left);
    verify(pockets, times(1)).addItem(any(ItemStack.class));
  }

  @Test
  void otherWindowsAndNonPlayersAreLeftAlone() {
    InventoryHolder holder = open(null);
    InventoryCloseEvent event = mock(InventoryCloseEvent.class);
    when(event.getInventory()).thenReturn(window);
    when(window.getHolder()).thenReturn(mock(InventoryHolder.class));
    when(event.getPlayer()).thenReturn(player);
    menu.onClose(event);
    when(window.getHolder()).thenReturn(holder);
    when(event.getPlayer()).thenReturn(mock(HumanEntity.class));
    menu.onClose(event);
    verify(window, never()).clear();
  }

  @Test
  void openCachesAreHandedBackWhenThePluginStops() {
    InventoryHolder holder = open(null);
    ItemStack left = item(false);
    when(window.getHolder()).thenReturn(holder);
    when(window.getContents()).thenReturn(new ItemStack[] {left});
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(window);
    when(player.getOpenInventory()).thenReturn(view);
    Player browsing = mock(Player.class);
    InventoryView other = mock(InventoryView.class);
    Inventory chest = mock(Inventory.class);
    when(other.getTopInventory()).thenReturn(chest);
    when(browsing.getOpenInventory()).thenReturn(other);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player, browsing));
      menu.closeAll();
    }
    verify(pockets).addItem(left);
    verify(player).closeInventory();
    verify(browsing, never()).closeInventory();
  }

  @Test
  void aRefusedWindowHandsTheRewardOverAtOnce() {
    ItemStack reward = item(false);
    when(player.openInventory(any(Inventory.class))).thenReturn(null);
    when(window.getContents()).thenReturn(new ItemStack[] {reward});
    open(reward);
    verify(pockets).addItem(reward);
    verify(window).clear();
  }

  PlayerDeathEvent death(Inventory top, boolean keep, List<ItemStack> drops) {
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(top);
    when(player.getOpenInventory()).thenReturn(view);
    PlayerDeathEvent event = mock(PlayerDeathEvent.class);
    when(event.getEntity()).thenReturn(player);
    when(event.getKeepInventory()).thenReturn(keep);
    when(event.getDrops()).thenReturn(drops);
    return event;
  }

  @Test
  void dyingWithTheCacheOpenDropsWhatIsInIt() {
    InventoryHolder holder = open(null);
    ItemStack left = item(false);
    when(window.getHolder()).thenReturn(holder);
    when(window.getContents()).thenReturn(new ItemStack[] {left});
    List<ItemStack> drops = new ArrayList<>();
    menu.onDeath(death(window, false, drops));
    assertEquals(List.of(left), drops);
    verify(window).clear();
    verify(pockets, never()).addItem(any(ItemStack.class));
  }

  @Test
  void keptInventoriesKeepTheCacheContentsToo() {
    InventoryHolder holder = open(null);
    ItemStack left = item(false);
    when(window.getHolder()).thenReturn(holder);
    when(window.getContents()).thenReturn(new ItemStack[] {left});
    List<ItemStack> drops = new ArrayList<>();
    menu.onDeath(death(window, true, drops));
    assertTrue(drops.isEmpty());
    verify(pockets).addItem(left);
  }

  @Test
  void deathWithAnyOtherWindowIsIgnored() {
    Inventory chest = mock(Inventory.class);
    List<ItemStack> drops = new ArrayList<>();
    menu.onDeath(death(chest, false, drops));
    assertTrue(drops.isEmpty());
    verify(chest, never()).clear();
  }
}
