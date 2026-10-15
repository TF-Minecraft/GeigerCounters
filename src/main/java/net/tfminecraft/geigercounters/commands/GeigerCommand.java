package net.tfminecraft.geigercounters.commands;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import net.tfminecraft.geigercounters.config.GeigerConfiguration;
import net.tfminecraft.geigercounters.config.Messages;
import net.tfminecraft.geigercounters.managers.GeigerManager;
import net.tfminecraft.geigercounters.utils.Hunters;
import net.tfminecraft.geigercounters.utils.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// ====================================
// Admin command handler:
// /geiger <locate [player]|move <player> [x z]|limits <player>|resetlimits <player>|droplist [name]|reload>
// ====================================
public class GeigerCommand implements CommandExecutor, TabCompleter, Listener {

    private static final List<String> SUBCOMMANDS = Arrays.asList("locate", "move", "limits", "resetlimits", "droplist", "reload");

    private final GeigerManager manager;
    private final Map<UUID, String> knownNames = new LinkedHashMap<>();

    public GeigerCommand(GeigerManager manager, OfflinePlayer[] knownPlayers) {
        this.manager = manager;
        for (OfflinePlayer player : knownPlayers) {
            if (player.getName() != null && player.hasPlayedBefore()) {
                knownNames.put(player.getUniqueId(), player.getName());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        knownNames.put(player.getUniqueId(), player.getName());
    }

    // Fetched per use rather than cached - /geiger reload swaps the file
    private Messages messages() {
        return manager.getConfiguration().getMessages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "locate":
                handleLocate(sender, args);
                return true;
            case "move":
                handleMove(sender, args);
                return true;
            // Singular spellings are aliases - both read naturally
            case "limit":
            case "limits":
                handleLimits(sender, args);
                return true;
            case "resetlimit":
            case "resetlimits":
                handleResetLimits(sender, args);
                return true;
            case "droplist":
                handleDropList(sender, args);
                return true;
            case "reload":
                handleReload(sender);
                return true;
            default:
                sendUsage(sender);
                return true;
        }
    }

    // /geiger locate          -> the sender's own source
    // /geiger locate <player> -> that player's current character's source
    private void handleLocate(CommandSender sender, String[] args) {
        if (args.length > 2 || (args.length == 1 && !(sender instanceof Player))) {
            sender.sendMessage(messages().get("admin.locate-usage"));
            return;
        }

        Player target = args.length == 2 ? onlineTarget(sender, args[1]) : (Player) sender;
        UUID hunter = target == null ? null : hunterOf(sender, target);
        if (hunter == null) {
            return;
        }

        if (manager.getSourceHandler().isPlacing(hunter)) {
            sender.sendMessage(messages().get("admin.source-relocating", "%player%", target.getName()));
            return;
        }

        Location source = manager.getSourceHandler().getSource(hunter);
        if (source == null) {
            sender.sendMessage(messages().get("admin.no-source", "%player%", target.getName()));
            return;
        }

        sender.sendMessage(messages().get("admin.source-located",
            "%player%", target.getName(),
            "%x%", Messages.coordinate(source.getX()),
            "%z%", Messages.coordinate(source.getZ()),
            "%world%", source.getWorld().getName()));
    }

    // /geiger move <player>         -> clear it; a new one is placed near them on their next hold
    // /geiger move <player> <x> <z> -> specific coordinates
    private void handleMove(CommandSender sender, String[] args) {
        if (args.length != 2 && args.length != 4) {
            sender.sendMessage(messages().get("admin.move-usage"));
            return;
        }

        double x = 0;
        double z = 0;
        if (args.length == 4) {
            try {
                x = Double.parseDouble(args[2]);
                z = Double.parseDouble(args[3]);
                if (!Double.isFinite(x) || !Double.isFinite(z)) {
                    throw new NumberFormatException("Coordinates must be finite");
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(messages().get("admin.move-invalid-coords"));
                return;
            }
        }

        Player target = onlineTarget(sender, args[1]);
        UUID hunter = target == null ? null : hunterOf(sender, target);
        if (hunter == null) {
            return;
        }

        if (args.length == 2) {
            manager.getSourceHandler().clear(hunter);
            sender.sendMessage(messages().get("admin.move-cleared", "%player%", target.getName()));
            return;
        }

        String name = target.getName();
        manager.getSourceHandler().placeAt(hunter, x, z)
            .thenAccept(location -> sendNewLocation(sender, name, location));
    }

    // Sources belong to characters, so the target has to be online to know which one
    private Player onlineTarget(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(messages().get("admin.player-offline", "%player%", name));
        }
        return target;
    }

    private UUID hunterOf(CommandSender sender, Player target) {
        Optional<UUID> hunter = Hunters.of(target);
        if (hunter.isEmpty()) {
            sender.sendMessage(messages().get("admin.no-character", "%player%", target.getName()));
            return null;
        }
        return hunter.get();
    }

    // Called once the chunk load behind the move has resolved
    private void sendNewLocation(CommandSender sender, String name, Location source) {
        if (source == null) {
            sender.sendMessage(messages().get("admin.move-failed"));
            return;
        }

        sender.sendMessage(messages().get("admin.move-success",
            "%player%", name,
            "%x%", Messages.coordinate(source.getX()),
            "%z%", Messages.coordinate(source.getZ())));
    }

    // /geiger limits <player> -> how many collections that player has left
    private void handleLimits(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(messages().get("admin.limits-usage"));
            return;
        }

        if (!manager.getConfiguration().isLimitEnabled()) {
            sender.sendMessage(messages().get("admin.limits-disabled"));
            return;
        }

        OfflinePlayer target = resolvePlayer(sender, args[1]);
        if (target == null) {
            return;
        }

        int remaining = manager.getDropLimitManager().getRemaining(target.getUniqueId());
        int max = manager.getConfiguration().getLimitDrops();
        String window = Utils.formatDuration(manager.getConfiguration().getLimitWindowMillis());

        sender.sendMessage(messages().get("admin.limits-status",
            "%player%", args[1],
            "%remaining%", remaining,
            "%max%", max,
            "%window%", window));

        if (remaining == 0) {
            long wait = manager.getDropLimitManager().getMillisUntilNextDrop(target.getUniqueId());
            sender.sendMessage(messages().get("admin.limits-next-drop",
                "%time%", Utils.formatDuration(wait)));
        }
    }

    // /geiger resetlimits <player> -> clear that player's collection history
    private void handleResetLimits(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(messages().get("admin.resetlimits-usage"));
            return;
        }

        OfflinePlayer target = resolvePlayer(sender, args[1]);
        if (target == null) {
            return;
        }

        manager.getDropLimitManager().reset(target.getUniqueId());
        sender.sendMessage(messages().get("admin.resetlimits-success", "%player%", args[1]));
    }

    // Offline lookup so limits can be inspected while the player is away
    @SuppressWarnings("deprecation")
    private OfflinePlayer resolvePlayer(CommandSender sender, String name) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(messages().get("admin.unknown-player", "%player%", name));
            return null;
        }
        return target;
    }

    // /geiger droplist        -> which list is active, and which exist
    // /geiger droplist <name> -> switch to that list, saved to config.yml
    private void handleDropList(CommandSender sender, String[] args) {
        GeigerConfiguration config = manager.getConfiguration();
        String available = String.join(", ", config.getDropListNames());

        if (args.length == 1) {
            sender.sendMessage(messages().get("admin.droplist-current",
                "%list%", config.getActiveDropList(),
                "%lists%", available));
            return;
        }

        if (args.length != 2) {
            sender.sendMessage(messages().get("admin.droplist-usage"));
            return;
        }

        String name = args[1];
        if (!config.getDropListNames().contains(name)) {
            sender.sendMessage(messages().get("admin.droplist-unknown",
                "%list%", name,
                "%lists%", available));
            return;
        }

        manager.getPlugin().getConfig().set("drops.active-list", name);
        manager.getPlugin().saveConfig();
        manager.reload();

        sender.sendMessage(messages().get("admin.droplist-changed", "%list%", name));
    }

    private void handleReload(CommandSender sender) {
        manager.reload();
        sender.sendMessage(messages().get("admin.reloaded"));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(messages().get("admin.usage"));
    }

    // ====================================
    // Suggest coordinates worth typing: where the sender is standing, and the
    // corners of the configured search area. Axis follows the argument -
    // X for the first, Z for the second.
    // ====================================
    private List<String> completeCoordinate(CommandSender sender, String[] args) {
        boolean isX = args.length == 3;
        String typed = args[args.length - 1];

        List<String> suggestions = new ArrayList<>();

        if (sender instanceof Player) {
            Location location = ((Player) sender).getLocation();
            suggestions.add(String.valueOf(isX ? location.getBlockX() : location.getBlockZ()));
        }

        GeigerConfiguration config = manager.getConfiguration();
        suggestions.add(formatCoordinate(isX ? config.getMinX() : config.getMinZ()));
        suggestions.add(formatCoordinate(isX ? config.getMaxX() : config.getMaxZ()));

        List<String> matches = new ArrayList<>();
        for (String suggestion : suggestions) {
            if (suggestion.startsWith(typed) && !matches.contains(suggestion)) {
                matches.add(suggestion);
            }
        }
        return matches;
    }

    // Whole blocks read better in a command than 1000.0
    private static String formatCoordinate(double value) {
        return String.valueOf((long) Math.floor(value));
    }

    private static boolean takesPlayer(String argument) {
        return argument.equalsIgnoreCase("locate") || argument.equalsIgnoreCase("move")
            || argument.equalsIgnoreCase("limit") || argument.equalsIgnoreCase("limits")
            || argument.equalsIgnoreCase("resetlimit") || argument.equalsIgnoreCase("resetlimits");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return List.of();
        if (args.length == 1) {
            List<String> matches = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    matches.add(sub);
                }
            }
            return matches;
        }

        // Coordinates for /geiger move <player> <x> <z>
        if (args[0].equalsIgnoreCase("move") && (args.length == 3 || args.length == 4)) {
            return completeCoordinate(sender, args);
        }

        // Drop list names for /geiger droplist <name>
        if (args.length == 2 && args[0].equalsIgnoreCase("droplist")) {
            List<String> matches = new ArrayList<>();
            for (String name : manager.getConfiguration().getDropListNames()) {
                if (name.toLowerCase().startsWith(args[1].toLowerCase())) {
                    matches.add(name);
                }
            }
            return matches;
        }

        // locate, move and both limit subcommands (either spelling) take a player name
        if (args.length == 2 && takesPlayer(args[0])) {
            Map<UUID, String> names = new LinkedHashMap<>();
            if (args[0].equalsIgnoreCase("limit") || args[0].equalsIgnoreCase("limits")
                    || args[0].equalsIgnoreCase("resetlimit") || args[0].equalsIgnoreCase("resetlimits")) {
                names.putAll(knownNames);
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.put(player.getUniqueId(), player.getName());
            }
            return new LinkedHashSet<>(names.values()).stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return new ArrayList<>();
    }
}
