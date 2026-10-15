package net.tfminecraft.geigercounters.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class HuntersTest {
  @Test
  void accountStandsInWithoutRpCharactersAndCharactersHuntSeparately() {
    Player player = mock(Player.class);
    UUID account = UUID.randomUUID(), character = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(account);
    try (var characters = mockStatic(Characters.class)) {
      characters.when(Characters::available).thenReturn(false);
      assertEquals(Optional.of(account), Hunters.of(player));
      characters.when(Characters::available).thenReturn(true);
      characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
      assertEquals(Optional.empty(), Hunters.of(player));
      characters
          .when(() -> Characters.currentSession(player))
          .thenReturn(Optional.of(new CharacterSession(new CharacterRef(account, character), 7)));
      assertEquals(Optional.of(character), Hunters.of(player));
    }
  }
}
