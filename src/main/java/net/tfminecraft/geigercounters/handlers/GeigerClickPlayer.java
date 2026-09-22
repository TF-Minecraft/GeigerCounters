package net.tfminecraft.geigercounters.handlers;

import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

// ====================================
// Plays the Geiger clicking sound.
//
// Distance controls the click RATE, not the pitch - that is what a real
// Geiger-Muller tube does, and it keeps the audio readable as a signal.
// Clicks are rolled per tick against a probability rather than played on a
// fixed interval, so the spacing is irregular the way real decay events are.
// An evenly spaced click just sounds like a metronome.
//
// Rates are fed in by the slower player check; this class only decides
// whether a given tick clicks. Entries not refreshed by that check (player
// holstered the counter, logged out) expire on their own.
// ====================================
public class GeigerClickPlayer {

    // Server ticks per second - the ceiling on how fast clicks can be played
    private static final double TICKS_PER_SECOND = 20.0;

    // How many ticks a rate stays valid without a refresh from the player check
    private static final long RATE_EXPIRY_TICKS = 15L;

    private final GeigerConfiguration config;
    private final Random random = new Random();

    // Players currently holding a Geiger Counter, with their click rate
    private final Map<UUID, ClickState> states = new HashMap<>();

    private long currentTick;

    public GeigerClickPlayer(GeigerConfiguration config) {
        this.config = config;
    }

    // ====================================
    // Record how fast this player should be clicking
    // Called by the periodic player check, not every tick
    // ====================================
    public void updateRate(Player player, double distance) {
        if (!config.isSoundEnabled()) {
            return;
        }

        double clicksPerTick = calculateClicksPerTick(distance);
        if (clicksPerTick <= 0.0) {
            states.remove(player.getUniqueId());
            return;
        }

        states.put(player.getUniqueId(), new ClickState(clicksPerTick, currentTick));
    }

    // ====================================
    // Roll every tracked player against their click rate
    // Runs every tick so close range can crackle rather than tick
    // ====================================
    public void tick() {
        currentTick++;

        if (!config.isSoundEnabled()) {
            if (!states.isEmpty()) {
                states.clear();
            }
            return;
        }

        Iterator<Map.Entry<UUID, ClickState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ClickState> entry = iterator.next();
            ClickState state = entry.getValue();

            // Stale entry - the player check stopped refreshing this player
            if (currentTick - state.updatedAtTick > RATE_EXPIRY_TICKS) {
                iterator.remove();
                continue;
            }

            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }

            if (random.nextDouble() < state.clicksPerTick) {
                playClick(player);
            }
        }
    }

    // Drop a player's state immediately (used on quit)
    public void forget(UUID playerId) {
        states.remove(playerId);
    }

    public void clear() {
        states.clear();
    }

    // ====================================
    // Map distance to clicks per tick.
    //
    // Full rate at the source, falling to the minimum at max detection range.
    // The curve exponent biases the fast clicking toward close range, so the
    // "getting hot" feeling lands where the player is actually hunting.
    // ====================================
    private double calculateClicksPerTick(double distance) {
        double maxDistance = config.getMaxDetectionDistance();
        if (maxDistance <= 0.0 || distance > maxDistance) {
            return 0.0;
        }

        double progress = Math.min(1.0, Math.max(0.0, distance / maxDistance));
        double closeness = Math.pow(1.0 - progress, config.getSoundCurve());

        double minRate = config.getSoundMinRate();
        double maxRate = config.getSoundMaxRate();
        double clicksPerSecond = minRate + (maxRate - minRate) * closeness;

        // One click per tick is the hard ceiling - a tick cannot play two
        return Math.min(1.0, clicksPerSecond / TICKS_PER_SECOND);
    }

    private void playClick(Player player) {
        float pitch = (float) clampPitch(config.getSoundPitch()
            + (random.nextDouble() * 2.0 - 1.0) * config.getSoundPitchVariance());

        // Played to the holder only - a Geiger Counter is a personal readout
        player.playSound(player.getLocation(), config.getSoundName(), SoundCategory.PLAYERS,
            (float) config.getSoundVolume(), pitch);
    }

    // Bukkit clamps silently outside this range, which would flatten the jitter
    private static double clampPitch(double pitch) {
        return Math.min(2.0, Math.max(0.5, pitch));
    }

    // ====================================
    // A player's current click rate and when it was last refreshed
    // ====================================
    private static class ClickState {
        final double clicksPerTick;
        final long updatedAtTick;

        ClickState(double clicksPerTick, long updatedAtTick) {
            this.clicksPerTick = clicksPerTick;
            this.updatedAtTick = updatedAtTick;
        }
    }
}
