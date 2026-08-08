package com.pirro.minigames.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Parses and validates /minigames subcommands, then delegates the actual
 * work to PillarGame. Owns permission checks and user-facing messaging;
 * owns none of the game state itself.
 */
final class PillarGameCommands implements TabExecutor {
    private final PillarGame game;

    PillarGameCommands(PillarGame game) {
        this.game = game;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ready")) {
            return handleReady(sender, args, 0);
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("Use /ready, /" + label
                    + " restart, /" + label + " forcestart, /" + label + " give <amount> [player], or /"
                    + label + " feather <double|triple> [player].", NamedTextColor.YELLOW));
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "ready" -> handleReady(sender, args, 1);
            case "restart" -> handleRestart(sender);
            case "forcestart" -> handleForceStart(sender);
            case "give", "forcegive" -> handleGive(sender, args);
            case "feather" -> handleFeather(sender, args);
            default -> {
                sender.sendMessage(Component.text("Unknown subcommand. Use ready, restart, forcestart, give, or feather.", NamedTextColor.RED));
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            if (command.getName().equalsIgnoreCase("ready")) {
                return List.of();
            }
            return complete(args[0], List.of("ready", "restart", "forcestart", "give", "feather"));
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("forcegive"))) {
            return complete(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("feather")) {
            return complete(args[1], List.of("double", "triple"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("feather")) {
            return complete(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private boolean handleReady(CommandSender sender, String[] args, int valueIndex) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can ready up.", NamedTextColor.RED));
            return true;
        }
        if (!game.isReadyCheckOpen() || !game.isGameWorld(player)) {
            player.sendMessage(Component.text("There is no ready check open right now.", NamedTextColor.RED));
            return true;
        }
        if (args.length != valueIndex) {
            player.sendMessage(Component.text("Use /ready.", NamedTextColor.YELLOW));
            return true;
        }
        game.toggleReady(player);
        return true;
    }

    private boolean handleRestart(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        game.restartGame();
        sender.sendMessage(Component.text("The arena was reset. The ready check is now open.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleForceStart(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (game.isGameStarted()) {
            sender.sendMessage(Component.text("The game is already running.", NamedTextColor.RED));
            return true;
        }
        game.forceStart();
        sender.sendMessage(Component.text("Force-started the game with 3 lives.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(Component.text("Use /minigames give <1-64> [player].", NamedTextColor.YELLOW));
            return true;
        }
        Integer amount = parseBoundedInteger(args[1], 1, 64);
        if (amount == null) {
            sender.sendMessage(Component.text("Amount must be a whole number from 1 to 64.", NamedTextColor.RED));
            return true;
        }

        Collection<? extends Player> targets = resolveTargets(sender, args, 2);
        if (targets == null) {
            return true;
        }

        game.giveItems(targets, amount);
        sender.sendMessage(Component.text("Gave " + amount + " random " + (amount == 1 ? "item" : "items")
                + " to " + targets.size() + " player(s).", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleFeather(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(Component.text("Use /minigames feather <double|triple> [player].", NamedTextColor.YELLOW));
            return true;
        }

        int jumpTier = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "double" -> 2;
            case "triple" -> 3;
            default -> 0;
        };
        if (jumpTier == 0) {
            sender.sendMessage(Component.text("Choose double or triple.", NamedTextColor.RED));
            return true;
        }

        Collection<? extends Player> targets;
        if (args.length == 3) {
            targets = resolveTargets(sender, args, 2);
            if (targets == null) {
                return true;
            }
        } else if (sender instanceof Player player && game.isGameWorld(player)) {
            targets = List.of(player);
        } else {
            sender.sendMessage(Component.text("Console must include a player name.", NamedTextColor.YELLOW));
            return true;
        }

        game.giveFeathers(targets, jumpTier);
        sender.sendMessage(Component.text("Gave a " + (jumpTier == 2 ? "Double" : "Triple")
                + " Jump feather to " + targets.size() + " player(s).", NamedTextColor.GREEN));
        return true;
    }

    /** Resolves the optional trailing player-name argument, or all in-arena players if omitted. */
    private Collection<? extends Player> resolveTargets(CommandSender sender, String[] args, int nameIndex) {
        if (args.length > nameIndex) {
            Player target = Bukkit.getPlayerExact(args[nameIndex]);
            if (target == null || !game.isGameWorld(target)) {
                sender.sendMessage(Component.text("That player is not in the pillar world.", NamedTextColor.RED));
                return null;
            }
            return List.of(target);
        }
        return game.playersInArena();
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("minigames.admin")) {
            return true;
        }
        sender.sendMessage(Component.text("You do not have permission to use that command.", NamedTextColor.RED));
        return false;
    }

    private static Integer parseBoundedInteger(String input, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(input);
            return value >= minimum && value <= maximum ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<String> complete(String token, List<String> values) {
        String lowerToken = token.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(lowerToken)).toList();
    }
}
