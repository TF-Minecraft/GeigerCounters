package net.tfminecraft.geigercounters.utils;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.ChatColor;

public class Utils {

    // Durations like "12h", "90m", "1d", "30s" - or a bare number, read as seconds
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?i)^[ \t]*([0-9]+(?:[.][0-9]+)?)[ \t]*([smhd]?)[ \t]*$");

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static String colorize(String msg) {
        Matcher match = Pattern.compile("#[a-fA-F0-9]{6}").matcher(msg);
        while (match.find()) {
            String color = msg.substring(match.start(), match.end());
            msg = msg.replace(color, String.valueOf(ChatColor.of(color)));
            match = Pattern.compile("#[a-fA-F0-9]{6}").matcher(msg);
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    // ====================================
    // Parse a duration string into milliseconds
    // Returns -1 when the value cannot be read, so callers can fall back
    // ====================================
    public static long parseDurationMillis(String value) {
        if (value == null) {
            return -1L;
        }

        Matcher matcher = DURATION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return -1L;
        }

        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toLowerCase();

        long unitMillis;
        switch (unit) {
            case "d":
                unitMillis = TimeUnit.DAYS.toMillis(1);
                break;
            case "h":
                unitMillis = TimeUnit.HOURS.toMillis(1);
                break;
            case "m":
                unitMillis = TimeUnit.MINUTES.toMillis(1);
                break;
            default: // "s" or no unit
                unitMillis = TimeUnit.SECONDS.toMillis(1);
                break;
        }

        return Math.round(amount * unitMillis);
    }

    // ====================================
    // Render a duration as "2d 3h 15m" - the two largest non-zero units,
    // dropping to seconds only when there is nothing bigger to show
    // ====================================
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "0s";
        }

        long totalSeconds = (millis + 999) / 1000; // round up so "0s" never shows while waiting
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0 && days == 0) {
            builder.append(minutes).append("m ");
        }
        if (seconds > 0 && days == 0 && hours == 0) {
            builder.append(seconds).append("s");
        }

        return builder.toString().trim();
    }
}
