package peyaj.integration;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import peyaj.IceBoatRacing;
import peyaj.Utils;

import java.util.Locale;
import java.util.Map;

/**
 * PlaceholderAPI expansion for IceBoatRacing.
 * Provides placeholders: %iceboat_wins%, %iceboat_races%, %iceboat_winrate%,
 * %iceboat_best_time_<arena>%, %iceboat_current_arena%, %iceboat_title%
 */
public class IceBoatPlaceholders extends PlaceholderExpansion {

    private final IceBoatRacing plugin;

    public IceBoatPlaceholders(IceBoatRacing plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "iceboat";
    }

    @Override
    public @NotNull String getAuthor() {
        return "peyaj";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // Don't unregister on reload
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null)
            return "";

        // %iceboat_wins%
        if (params.equalsIgnoreCase("wins")) {
            return String.valueOf(plugin.getStat(offlinePlayer.getUniqueId(), "wins"));
        }

        // %iceboat_races%
        if (params.equalsIgnoreCase("races")) {
            return String.valueOf(plugin.getStat(offlinePlayer.getUniqueId(), "races_played"));
        }

        // %iceboat_winrate%
        if (params.equalsIgnoreCase("winrate")) {
            int wins = plugin.getStat(offlinePlayer.getUniqueId(), "wins");
            int races = plugin.getStat(offlinePlayer.getUniqueId(), "races_played");
            if (races == 0)
                return "0%";
            return (wins * 100 / races) + "%";
        }

        // %iceboat_current_arena%
        if (params.equalsIgnoreCase("current_arena")) {
            var arena = plugin.getPlayerArena(offlinePlayer.getUniqueId());
            return arena != null ? arena.getName() : "None";
        }

        // %iceboat_in_race%
        if (params.equalsIgnoreCase("in_race")) {
            return plugin.isRacer(offlinePlayer.getUniqueId()) ? "Yes" : "No";
        }

        // %iceboat_title%
        if (params.equalsIgnoreCase("title")) {
            return getPlayerTitle(offlinePlayer);
        }

        String lower = params.toLowerCase();

        // %iceboat_best_time_...%
        if (lower.startsWith("best_time_")) {
            String sub = lower.substring(10);
            String arenaName;
            int type = 0; // 0: formatted, 1: clock (00:00.00), 2: raw millis, 3: decimal seconds

            if (sub.startsWith("raw_")) {
                arenaName = params.substring(14);
                type = 2;
            } else if (sub.startsWith("ms_")) {
                arenaName = params.substring(13);
                type = 2;
            } else if (sub.startsWith("seconds_")) {
                arenaName = params.substring(18);
                type = 3;
            } else if (sub.startsWith("sec_")) {
                arenaName = params.substring(14);
                type = 3;
            } else if (sub.startsWith("clock_")) {
                arenaName = params.substring(16);
                type = 1;
            } else if (sub.startsWith("formatted_")) {
                arenaName = params.substring(20);
                type = 0;
            } else {
                arenaName = params.substring(10);
                type = 0;
            }

            var arena = plugin.getArena(arenaName);
            if (arena == null)
                return "N/A";

            Long bestTime = arena.bestTimes.get(offlinePlayer.getUniqueId());
            if (bestTime == null)
                return "N/A";

            return switch (type) {
                case 1 -> Utils.formatClockTime(bestTime);
                case 2 -> String.valueOf(bestTime);
                case 3 -> String.format(Locale.US, "%.2f", bestTime / 1000.0);
                default -> Utils.formatTime(bestTime);
            };
        }

        // %iceboat_arena_record_holder_<arena>%
        if (lower.startsWith("arena_record_holder_")) {
            String arenaName = params.substring(20);
            var arena = plugin.getArena(arenaName);
            if (arena == null || arena.bestTimes.isEmpty())
                return "None";

            var optEntry = arena.bestTimes.entrySet().stream()
                    .min(Map.Entry.comparingByValue());
            if (optEntry.isEmpty())
                return "None";

            OfflinePlayer holder = Bukkit.getOfflinePlayer(optEntry.get().getKey());
            return holder.getName() != null ? holder.getName() : "Unknown";
        }

        // %iceboat_arena_record_...%
        if (lower.startsWith("arena_record_")) {
            String sub = lower.substring(13);
            String arenaName;
            int type = 0; // 0: formatted, 1: clock (00:00.00), 2: raw millis, 3: decimal seconds

            if (sub.startsWith("raw_")) {
                arenaName = params.substring(17);
                type = 2;
            } else if (sub.startsWith("ms_")) {
                arenaName = params.substring(16);
                type = 2;
            } else if (sub.startsWith("seconds_")) {
                arenaName = params.substring(21);
                type = 3;
            } else if (sub.startsWith("sec_")) {
                arenaName = params.substring(17);
                type = 3;
            } else if (sub.startsWith("clock_")) {
                arenaName = params.substring(19);
                type = 1;
            } else if (sub.startsWith("formatted_")) {
                arenaName = params.substring(23);
                type = 0;
            } else {
                arenaName = params.substring(13);
                type = 0;
            }

            var arena = plugin.getArena(arenaName);
            if (arena == null || arena.bestTimes.isEmpty())
                return "N/A";

            long record = arena.bestTimes.values().stream().mapToLong(Long::longValue).min().orElse(0);
            return switch (type) {
                case 1 -> Utils.formatClockTime(record);
                case 2 -> String.valueOf(record);
                case 3 -> String.format(Locale.US, "%.2f", record / 1000.0);
                default -> Utils.formatTime(record);
            };
        }

        // %iceboat_total_arenas%
        if (params.equalsIgnoreCase("total_arenas")) {
            return String.valueOf(plugin.getArenas().size());
        }

        return null;
    }

    private String getPlayerTitle(OfflinePlayer player) {
        int wins = plugin.getStat(player.getUniqueId(), "wins");
        int races = plugin.getStat(player.getUniqueId(), "races_played");

        if (wins >= 100)
            return "§6§lLegend";
        if (wins >= 50)
            return "§c§lChampion";
        if (wins >= 25)
            return "§d§lMaster";
        if (wins >= 10)
            return "§b§lVeteran";
        if (wins >= 5)
            return "§a§lRacer";
        if (races >= 10)
            return "§e§lEnthusiast";
        if (races >= 1)
            return "§7§lNewcomer";
        return "§8§lRookie";
    }
}
