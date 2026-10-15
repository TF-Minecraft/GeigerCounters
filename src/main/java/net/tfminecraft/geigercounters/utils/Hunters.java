package net.tfminecraft.geigercounters.utils;

import net.tfminecraft.tlibs.character.Characters;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

// ====================================
// Who a source belongs to: the character being played, so each character
// hunts its own. Without RPCharacters the account stands in. A player with
// no active character (the selection room) has no hunter.
// ====================================
public final class Hunters {

    private Hunters() {
    }

    public static Optional<UUID> of(Player player) {
        if (!Characters.available()) {
            return Optional.of(player.getUniqueId());
        }
        return Characters.currentSession(player).map(session -> session.ref().characterId());
    }
}
