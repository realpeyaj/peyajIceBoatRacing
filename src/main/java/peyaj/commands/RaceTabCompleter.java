package peyaj.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import peyaj.IceBoatRacing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tab completer for all race commands.
 */
public class RaceTabCompleter implements TabCompleter {

    private final IceBoatRacing plugin;

    public RaceTabCompleter(IceBoatRacing plugin) {
        this.plugin = plugin;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (label.equalsIgnoreCase("checkpoint") || label.equalsIgnoreCase("cp") || label.equalsIgnoreCase("stuck")) {
            return completions; // No args for checkpoint
        }

        if (args.length == 1) {
            // First argument - main subcommands
            List<String> subcommands = new ArrayList<>(
                    Arrays.asList("join", "leave", "vote", "cp", "checkpoint", "replay"));
            if (sender.hasPermission("race.admin")) {
                subcommands.addAll(Arrays.asList("start", "stop", "admin"));
            }
            return filterCompletions(subcommands, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            switch (sub) {
                case "join", "start", "stop" -> {
                    // Arena names
                    return filterCompletions(new ArrayList<>(plugin.getArenas().keySet()), args[1]);
                }
                case "admin" -> {
                    if (sender.hasPermission("race.admin")) {
                        return filterCompletions(Arrays.asList(
                                "wand", "startvote", "delete", "visualize", "reload", "setmainlobby", "purgeholograms"), args[1]);
                    }
                }
                case "replay" -> {
                    return filterCompletions(Arrays.asList("list", "watch", "stop"), args[1]);
                }
            }
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase();
            String action = args[1].toLowerCase();

            if (sub.equals("admin")) {
                if (action.equals("delete") || action.equals("visualize")) {
                    // Arena names
                    return filterCompletions(new ArrayList<>(plugin.getArenas().keySet()), args[2]);
                }
            }

            if (sub.equals("party")) {
                if (action.equals("invite") || action.equals("kick")) {
                    // Online player names
                    return filterCompletions(
                            Bukkit.getOnlinePlayers().stream()
                                    .map(Player::getName)
                                    .collect(Collectors.toList()),
                            args[2]);
                }
            }

            if (sub.equals("replay") && action.equals("list")) {
                // Arena names for replay list
                return filterCompletions(new ArrayList<>(plugin.getArenas().keySet()), args[2]);
            }
        }

        return completions;
    }

    private List<String> filterCompletions(List<String> options, String input) {
        String lowerInput = input.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(lowerInput))
                .sorted()
                .collect(Collectors.toList());
    }
}
