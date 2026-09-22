package net.tfminecraft.geigercounters.handlers;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;

// ====================================
// Handles rendering particle effects for the Geiger Counter
// ====================================
public class ParticleRenderer {
    
    private final GeigerConfiguration config;
    
    public ParticleRenderer(GeigerConfiguration config) {
        this.config = config;
    }
    
    // ====================================
    //  Show colored particle rings based on distance to source
    // ====================================
    public void showParticleEffect(Player player, double distance) {
        if (distance > config.getMaxDetectionDistance()) {
            return;
        }
        
        ParticleConfig particleConfig = calculateParticleConfig(distance);
        spawnParticleRings(player, particleConfig);
    }
    
    // ====================================
    // Determine particle configuration based on distance
    // Uses close range or far range calculation
    // ====================================
    private ParticleConfig calculateParticleConfig(double distance) {
        if (distance < config.getCloseRangeThreshold()) {
            return calculateCloseRangeParticles(distance);
        } else {
            return calculateFarRangeParticles(distance);
        }
    }
    
    // ====================================
    // Calculate particle color for "close range"
    // Interpolates between start and end colors based on distance
    // Always shows 3 rings in close range
    // ====================================
    private ParticleConfig calculateCloseRangeParticles(double distance) {
        // Calculate how far through the close range we are (0.0 to 1.0)
        double progress = distance / config.getCloseRangeThreshold();
        
        GeigerConfiguration.ColorConfig startColor = config.getCloseRangeStartColor();
        GeigerConfiguration.ColorConfig endColor = config.getCloseRangeEndColor();
        
        // Interpolate each color
        int red = interpolateColor(startColor.getRed(), endColor.getRed(), progress);
        int green = interpolateColor(startColor.getGreen(), endColor.getGreen(), progress);
        int blue = interpolateColor(startColor.getBlue(), endColor.getBlue(), progress);
        
        return new ParticleConfig(red, green, blue, 3);
    }
    
    // ====================================
    // Calculate particle color for far range
    // Interpolates between start and end colors based on distance
    // Ring count varies based on distance thresholds
    // ====================================
    private ParticleConfig calculateFarRangeParticles(double distance) {
        // Calculate how far through the far range we are (0.0 to 1.0)
        double progress = (distance - config.getCloseRangeThreshold()) / 
                         (config.getMaxDetectionDistance() - config.getCloseRangeThreshold());
        
        GeigerConfiguration.ColorConfig startColor = config.getFarRangeStartColor();
        GeigerConfiguration.ColorConfig endColor = config.getFarRangeEndColor();
        
        // Interpolate each color channel
        int red = interpolateColor(startColor.getRed(), endColor.getRed(), progress);
        int green = interpolateColor(startColor.getGreen(), endColor.getGreen(), progress);
        int blue = interpolateColor(startColor.getBlue(), endColor.getBlue(), progress);
        
        int rings = calculateRingCount(distance);
        
        return new ParticleConfig(red, green, blue, rings);
    }
    
    private int interpolateColor(int start, int end, double progress) {
        return (int)(start + (end - start) * progress);
    }
    
    // ====================================
    // Determine number of particle rings based on distance
    // Closer distances show more rings for better visibility
    // ====================================
    private int calculateRingCount(double distance) {
        if (distance <= config.getThreeRingsDistance()) return 3;
        if (distance <= config.getTwoRingsDistance()) return 2;
        return 1;
    }
    
    // ====================================
    // Spawn multiple particle rings around the player
    // ====================================
    private void spawnParticleRings(Player player, ParticleConfig config) {
        Color particleColor = Color.fromRGB(config.red, config.green, config.blue);
        Particle.DustOptions dustOptions = new Particle.DustOptions(particleColor, 1f);
        Location centerLocation = player.getLocation().add(0.0, 1.2, 0.0);
        
        // Spawn each ring with increasing radius
        for (int ringIndex = 0; ringIndex < config.rings; ringIndex++) {
            spawnSingleRing(player, centerLocation, dustOptions, ringIndex);
        }
    }
    
    // ====================================
    // Spawn a single ring of particles around a center point
    // ====================================
    private void spawnSingleRing(Player player, Location center, Particle.DustOptions dust, int ringIndex) {
        // Calculate radius for this ring (each ring is slightly larger)
        double radius = 0.3 + (ringIndex * 0.1);
        
        // Create circle using angles (every 10 degrees)
        for (int angle = 0; angle < 360; angle += 10) {
            double radians = Math.toRadians(angle);
            double xOffset = radius * Math.cos(radians);
            double zOffset = radius * Math.sin(radians);
            
            Location particleLocation = center.clone().add(xOffset, 0.0, zOffset);
            player.spawnParticle(Particle.DUST, particleLocation, 1, dust);
        }
    }
    
    // ====================================
    // Stores particle configuration: color (RGB) and number of rings
    // ====================================
    private static class ParticleConfig {
        final int red;
        final int green;
        final int blue;
        final int rings;
        
        ParticleConfig(int red, int green, int blue, int rings) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.rings = rings;
        }
    }
}
