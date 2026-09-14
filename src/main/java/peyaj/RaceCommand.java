package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import peyaj.arena.RaceType;
import peyaj.cosmetics.EditMode;
import peyaj.replay.ReplayData;

import java.util.List;

public class RaceCommand implements CommandExecutor {

    private final IceBoatRacing plugin;

    public RaceCommand(IceBoatRacing plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can run this command.");
            return true;
        }

        String cmd = command.getName().toLowerCase();

        if (cmd.equals("checkpoint")
                || (args.length >= 1 && (args[0].equalsIgnoreCase("cp") || args[0].equalsIgnoreCase("checkpoint")))) {
            handleCheckpointRespawn(p);
            return true;
        }
        if (cmd.equals("racequit")) {
            handleLeave(p);
            return true;
        }
        if (cmd.equals("iceboat") && args.length == 0) {
            plugin.guiManager.openMainMenu(p);
            return true;
        }

        if (args.length < 1) {
            p.sendMessage(plugin.getMessage("race-usage"));
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "start" -> handleStart(p, args);
            case "stop" -> handleStop(p, args);
            case "vote" -> handleVote(p);
            case "join" -> handleJoin(p, args);
            case "leave" -> handleLeave(p);
            case "admin" -> handleAdmin(p, args);
            case "replay" -> handleReplay(p, args);
            default -> p.sendMessage(plugin.getMessage("race-usage"));
        }
        return true;
    }

    private void handleCheckpointRespawn(Player p) {
        RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
        if (arena == null) {
            p.sendMessage(plugin.getMessage("not-in-race"));
            return;
        }
        arena.respawnPlayer(p);
    }

    private void handleStart(Player p, String[] args) {
        if (!p.hasPermission("race.admin")) {
            p.sendMessage(plugin.getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(Component.text("Usage: /race start <arena>", NamedTextColor.RED));
            return;
        }
        String arenaName = args[1];
        RaceArena arena = plugin.getArena(arenaName);
        if (arena == null) {
            p.sendMessage(plugin.getMessage("invalid-arena"));
            return;
        }
        arena.startRace();
        p.sendMessage(Component.text("Force started race on " + arenaName, NamedTextColor.GREEN));
    }

    private void handleStop(Player p, String[] args) {
        if (!p.hasPermission("race.admin")) {
            p.sendMessage(plugin.getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(Component.text("Usage: /race stop <arena>", NamedTextColor.RED));
            return;
        }
        String arenaName = args[1];
        RaceArena arena = plugin.getArena(arenaName);
        if (arena == null) {
            p.sendMessage(plugin.getMessage("invalid-arena"));
            return;
        }
        arena.stopRace();
        p.sendMessage(Component.text("Stopped race on " + arenaName, NamedTextColor.YELLOW));
    }

    private void handleVote(Player p) {
        if (!plugin.isVoting) {
            p.sendMessage(Component.text("No vote in progress.", NamedTextColor.RED));
            return;
        }
        plugin.guiManager.openVoteMenu(p);
    }

    private void handleJoin(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(Component.text("Usage: /race join <arena>", NamedTextColor.RED));
            return;
        }
        if (plugin.isRacer(p.getUniqueId())) {
            p.sendMessage(Component.text("You are already in an arena! Use /race leave first.", NamedTextColor.RED));
            return;
        }
        
        String arenaName = args[1];
        RaceArena arena = plugin.getArena(arenaName);
        if (arena == null) {
            p.sendMessage(plugin.getMessage("invalid-arena"));
            return;
        }
        arena.addPlayer(p);
    }

    private void handleLeave(Player p) {
        RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
        if (arena == null) {
            p.sendMessage(plugin.getMessage("not-in-race"));
            return;
        }
        arena.removePlayer(p);
        p.sendMessage(plugin.getMessage("arena-left"));
    }



    private void handleReplay(Player p, String[] args) {
        if (!p.hasPermission("race.replay") && !p.hasPermission("race.use")) {
            p.sendMessage(plugin.getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(Component.text("Usage: /race replay <list|watch|stop> [arena] [index]", NamedTextColor.RED));
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "list" -> {
                if (args.length < 3) {
                    p.sendMessage(Component.text("Usage: /race replay list <arena>", NamedTextColor.RED));
                    return;
                }
                String arenaName = args[2];
                RaceArena arena = plugin.getArena(arenaName);
                if (arena == null) {
                    p.sendMessage(plugin.getMessage("invalid-arena"));
                    return;
                }
                List<ReplayData> replays = plugin.replayManager.getReplays(arenaName);
                if (replays.isEmpty()) {
                    p.sendMessage(Component.text("No replays available for " + arenaName, NamedTextColor.YELLOW));
                    return;
                }
                p.sendMessage(Component.text("--- Replays for " + arenaName + " ---", NamedTextColor.GOLD));
                for (int i = 0; i < replays.size(); i++) {
                    ReplayData r = replays.get(i);
                    String date = new java.text.SimpleDateFormat("MM/dd HH:mm")
                            .format(new java.util.Date(r.getTimestamp()));
                    p.sendMessage(
                            Component.text("  [" + i + "] " + date + " - " + r.getPlayerNames().size() + " racers",
                                    NamedTextColor.AQUA));
                }
                p.sendMessage(Component.text("Use /race replay watch <arena> <index> to watch", NamedTextColor.GRAY));
            }
            case "watch" -> {
                if (args.length < 4) {
                    p.sendMessage(Component.text("Usage: /race replay watch <arena> <index>", NamedTextColor.RED));
                    return;
                }
                String arenaName = args[2];
                RaceArena arena = plugin.getArena(arenaName);
                if (arena == null) {
                    p.sendMessage(plugin.getMessage("invalid-arena"));
                    return;
                }
                int index;
                try {
                    index = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    p.sendMessage(Component.text("Invalid index.", NamedTextColor.RED));
                    return;
                }
                List<ReplayData> replays = plugin.replayManager.getReplays(arenaName);
                if (index < 0 || index >= replays.size()) {
                    p.sendMessage(Component.text("Replay index out of range.", NamedTextColor.RED));
                    return;
                }
                ReplayData replay = replays.get(index);
                plugin.replayManager.startPlayback(p, replay,
                        arena.getSpawns().isEmpty() ? p.getWorld() : arena.getSpawns().get(0).getWorld());
            }
            case "stop" -> plugin.replayManager.stopPlayback(p);
            default -> p.sendMessage(Component.text("Unknown action. Use list, watch, or stop.", NamedTextColor.RED));
        }
    }

    private void handleAdmin(Player p, String[] args) {
        if (!p.hasPermission("race.admin")) {
            p.sendMessage(plugin.getMessage("no-permission"));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(Component.text("Usage: /race admin <wand|startvote|delete|visualize|reload|setmainlobby|purgeholograms>",
                    NamedTextColor.RED));
            return;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "wand" -> {
                ItemStack wand = new ItemStack(Material.BLAZE_ROD);
                ItemMeta meta = wand.getItemMeta();
                meta.displayName(Component.text("§b§lRace Wand §7(Click to cycle modes)"));
                meta.lore(List.of(
                        Component.text("§7Left-Click: Add point"),
                        Component.text("§7Right-Click: Cycle mode"),
                        Component.text("§7Shift+Right: Remove point")));
                meta.getPersistentDataContainer().set(plugin.guiManager.raceWandKey, PersistentDataType.BYTE, (byte) 1);
                wand.setItemMeta(meta);
                p.getInventory().addItem(wand);
                p.sendMessage(Component.text("Race Wand given!", NamedTextColor.GREEN));

                // Check if in editor mode
                if (!plugin.editorArena.containsKey(p.getUniqueId())) {
                    p.sendMessage(Component.text("Tip: Open the Admin Panel and select an arena to edit it.",
                            NamedTextColor.YELLOW));
                }
            }
            case "startvote" -> {
                int duration = 60;
                if (args.length >= 3) {
                    try {
                        duration = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                plugin.startVotingRound(duration);
                p.sendMessage(Component.text("Voting started for " + duration + " seconds!", NamedTextColor.GREEN));
            }
            case "delete" -> {
                if (args.length < 3) {
                    p.sendMessage(Component.text("Usage: /race admin delete <arena>", NamedTextColor.RED));
                    return;
                }
                String arenaName = args[2];
                if (plugin.getArena(arenaName) == null) {
                    p.sendMessage(plugin.getMessage("invalid-arena"));
                    return;
                }
                plugin.removeArena(arenaName);
                plugin.saveArenas();
                p.sendMessage(Component.text("Arena " + arenaName + " deleted.", NamedTextColor.YELLOW));
            }
            case "visualize" -> {
                if (args.length < 3) {
                    p.sendMessage(Component.text("Usage: /race admin visualize <arena>", NamedTextColor.RED));
                    return;
                }
                String arenaName = args[2];
                if (plugin.getArena(arenaName) == null) {
                    p.sendMessage(plugin.getMessage("invalid-arena"));
                    return;
                }
                if (plugin.activeVisualizers.containsKey(p.getUniqueId())
                        && plugin.activeVisualizers.get(p.getUniqueId()).equals(arenaName)) {
                    plugin.activeVisualizers.remove(p.getUniqueId());
                    p.sendMessage(Component.text("Visualizer disabled for " + arenaName, NamedTextColor.YELLOW));
                } else {
                    plugin.activeVisualizers.put(p.getUniqueId(), arenaName);
                    p.sendMessage(Component.text("Visualizer enabled for " + arenaName, NamedTextColor.GREEN));
                }
            }
            case "reload" -> {
                plugin.reload();
                p.sendMessage(Component.text("Configuration reloaded!", NamedTextColor.GREEN));
            }
            case "setmainlobby" -> {
                Location loc = p.getLocation();
                // Set main lobby for all arenas
                for (RaceArena arena : plugin.getArenas().values()) {
                    arena.setMainLobby(loc);
                }
                plugin.saveArenas();
                p.sendMessage(Component.text("Main lobby set for all arenas!", NamedTextColor.GREEN));
            }
            case "purgeholograms" -> {
                int purged = (plugin.getHologramManager() != null) ? plugin.getHologramManager().purgeAllOrphanedHolograms() : 0;
                for (RaceArena arena : plugin.getArenas().values()) {
                    Location lb = arena.getLeaderboardLocation();
                    if (lb != null && lb.getWorld() != null && lb.getWorld().isChunkLoaded(lb.getBlockX() >> 4, lb.getBlockZ() >> 4)) {
                        arena.updateLeaderboardHologram();
                    }
                }
                p.sendMessage(Component.text("Leaderboard holograms refreshed! Purged " + purged + " duplicate/ghost display(s).", NamedTextColor.GREEN));
            }
            default -> p.sendMessage(Component.text("Unknown admin action.", NamedTextColor.RED));
        }
    }
}