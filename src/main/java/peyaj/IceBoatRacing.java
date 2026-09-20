package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import peyaj.commands.RaceTabCompleter;
import peyaj.cosmetics.EditMode;
import peyaj.cosmetics.TrailType;
import peyaj.data.GhostData;
import peyaj.hologram.HologramManager;
import peyaj.integration.DiscordWebhook;
import peyaj.integration.IceBoatPlaceholders;
import peyaj.replay.ReplayManager;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import peyaj.utils.AsyncIO;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Boat;
import org.bukkit.persistence.PersistentDataType;
import peyaj.arena.RaceState;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

public class IceBoatRacing extends JavaPlugin {

    private static IceBoatRacing instance;

    public static IceBoatRacing getInstance() {
        return instance;
    }

    private final Map<String, RaceArena> arenas = new HashMap<>();
    private final Map<UUID, String> playerArenaMap = new ConcurrentHashMap<>();

    // EDITOR & INPUT DATA
    public final Map<UUID, String> editorArena = new HashMap<>();
    public final Map<UUID, EditMode> editorMode = new HashMap<>();
    public final Map<UUID, String> activeVisualizers = new HashMap<>();
    public final Map<UUID, String> inputMode = new HashMap<>();

    // COSMETICS DATA
    private final Map<UUID, Material> playerCagePreference = new HashMap<>();
    private final Map<UUID, TrailType> playerTrailPreference = new HashMap<>();

    // VOTING DATA
    public boolean isVoting = false;
    public int votingTimeRemaining = 0;
    private BukkitTask votingTask;
    public final Map<UUID, String> playerVotes = new HashMap<>();

    // CONFIGS
    private File messagesFile;
    private FileConfiguration messagesConfig;
    private File statsFile;
    private FileConfiguration statsConfig;
    private File arenasFile;
    private FileConfiguration arenasConfig;

    // MANAGERS
    public GUIManager guiManager;
    public ReplayManager replayManager;
    public DiscordWebhook discordWebhook;
    public HologramManager hologramManager;

    // SETTINGS
    public double checkpointRadius = 25.0;
    public String discordWebhookUrl = "";

    public boolean musicEnabled = true;
    public String musicSound = "minecraft:coconutmallmariokartwiiostfourone";
    public int musicDuration = 180;
    public float musicVolume = 10000.0f;
    public float musicPitch = 1.0f;

    public boolean rewardsEnabled = false;
    public int rewardsMinPlayers = 2;
    public final Map<Integer, List<String>> rewardCommands = new HashMap<>();

    public String collisionMode = "DEFAULT";
    public int cageSize = 3;
    public final Map<UUID, Integer> openBoatUtilsPlayers = new ConcurrentHashMap<>();

    public boolean hasOpenBoatUtils(UUID uuid) {
        return openBoatUtilsPlayers.containsKey(uuid) && openBoatUtilsPlayers.get(uuid) >= 10;
    }

    public void sendOpenBoatUtilsNocol(Player player, boolean enableNocol) {
        if (!hasOpenBoatUtils(player.getUniqueId())) return;
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(byteStream)) {
            out.writeShort(27); // PACKET_ID_NOCOL
            out.writeShort(enableNocol ? 1 : 0); // 1 = NOCOL_MODE_NO_COLLISION_BOATS_PLAYERS, 0 = OFF
            player.sendPluginMessage(this, "openboatutils:settings", byteStream.toByteArray());
        } catch (Exception ignored) {
        }
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(byteStream)) {
            out.writeShort(29); // PACKET_ID_INTERPOLATION_FIX
            out.writeShort(enableNocol ? 1 : 0);
            player.sendPluginMessage(this, "openboatutils:settings", byteStream.toByteArray());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onLoad() {
        instance = this;
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings().checkForUpdates(false).bStats(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        instance = this;
        PacketEvents.getAPI().init();

        // PacketEvents Listener to eliminate boat collision rubberbanding/freezing
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() == PacketType.Play.Server.VEHICLE_MOVE) {
                    Object pObj = event.getPlayer();
                    if (pObj instanceof Player p) {
                        RaceArena arena = getPlayerArena(p.getUniqueId());
                        if (arena != null && (arena.getState() == RaceState.ACTIVE || arena.getState() == RaceState.STARTING)) {
                            if (!arena.hasTeleportGrace(p.getUniqueId()) && p.isInsideVehicle() && p.getVehicle() instanceof Boat) {
                                event.setCancelled(true);
                            }
                        }
                    }
                }
            }
        });

        hologramManager = new HologramManager(this);

        // OpenBoatUtils integration
        getServer().getMessenger().registerOutgoingPluginChannel(this, "openboatutils:settings");
        getServer().getMessenger().registerIncomingPluginChannel(this, "openboatutils:settings", (channel, player, message) -> {
            if (!"openboatutils:settings".equals(channel) || message == null || message.length < 6) return;
            try {
                ByteArrayDataInput in = ByteStreams.newDataInput(message);
                short packetId = in.readShort();
                if (packetId == 0) {
                    int version = in.readInt();
                    openBoatUtilsPlayers.put(player.getUniqueId(), version);

                    getServer().getScheduler().runTaskLater(this, () -> {
                        if (player.isOnline()) {
                            sendOpenBoatUtilsNocol(player, true);
                        }
                    }, 10L);
                }
            } catch (Exception ignored) {
            }
        });

        // Optimize Spigot movement thresholds for high-speed ice boat racing to prevent rubberbanding/lagbacks
        try {
            Class<?> spigotConfigClass = Class.forName("org.spigotmc.SpigotConfig");
            java.lang.reflect.Field wronglyField = spigotConfigClass.getField("movedWronglyThreshold");
            java.lang.reflect.Field quicklyField = spigotConfigClass.getField("movedTooQuicklyMultiplier");
            if (wronglyField.getDouble(null) < 100.0) {
                wronglyField.setDouble(null, 100.0);
            }
            if (quicklyField.getDouble(null) < 100.0) {
                quicklyField.setDouble(null, 100.0);
            }
            getLogger().info("Optimized Spigot moved-wrongly-threshold and moved-too-quickly-multiplier for high-speed ice racing.");
        } catch (Throwable ignored) {
        }

        sendStartupBanner();

        saveDefaultConfig();
        checkConfigUpdates();
        loadConfigSettings();
        loadMessages();
        loadStats();

        // Load Arenas from dedicated file
        loadArenasConfig();

        if (getConfig().contains("arenas")) {
            getLogger().info("Migrating arenas from config.yml to arenas.yml...");
            arenasConfig.set("arenas", getConfig().getConfigurationSection("arenas"));
            getConfig().set("arenas", null);
            saveConfig();
            saveArenasConfig();
            getLogger().info("Migration complete!");
        }

        loadArenas();
        if (hologramManager != null) {
            hologramManager.purgeAllOrphanedHolograms();
        }

        // Initialize managers
        guiManager = new GUIManager(this);
        replayManager = new ReplayManager(this);
        discordWebhook = new DiscordWebhook(this);

        getServer().getPluginManager().registerEvents(guiManager, this);

        // Register commands with tab completer
        RaceCommand cmd = new RaceCommand(this);
        RaceTabCompleter tabCompleter = new RaceTabCompleter(this);

        if (getCommand("race") != null) {
            Objects.requireNonNull(getCommand("race")).setExecutor(cmd);
            Objects.requireNonNull(getCommand("race")).setTabCompleter(tabCompleter);
        }
        if (getCommand("iceboat") != null) {
            Objects.requireNonNull(getCommand("iceboat")).setExecutor(cmd);
            Objects.requireNonNull(getCommand("iceboat")).setTabCompleter(tabCompleter);
        }
        if (getCommand("checkpoint") != null) {
            Objects.requireNonNull(getCommand("checkpoint")).setExecutor(cmd);
        }
        if (getCommand("racequit") != null) {
            Objects.requireNonNull(getCommand("racequit")).setExecutor(cmd);
        }

        // Initialize bStats Metrics
        int pluginId = 33031;
        Metrics metrics = new Metrics(this, pluginId);
        metrics.addCustomChart(new SimplePie("total_arenas", () -> String.valueOf(arenas.size())));

        getServer().getPluginManager().registerEvents(new RaceListener(this), this);

        // Main game tick
        new BukkitRunnable() {
            @Override
            public void run() {
                for (RaceArena arena : arenas.values())
                    arena.tick();
            }
        }.runTaskTimer(this, 0L, 1L);

        // Visualizer tick
        new BukkitRunnable() {
            @Override
            public void run() {
                Utils.tickVisualizers(IceBoatRacing.this);
            }
        }.runTaskTimer(this, 0L, 10L);

        // Register PlaceholderAPI expansion if available
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new IceBoatPlaceholders(this).register();
            getLogger().info("PlaceholderAPI expansion registered!");
        }
    }

    @Override
    public void onDisable() {
        instance = null;
        if (votingTask != null) {
            votingTask.cancel();
        }
        for (RaceArena arena : arenas.values()) {
            arena.stopRace();
            arena.deleteLeaderboardHologram();
        }
        if (hologramManager != null) {
            hologramManager.removeAll();
        }
        if (arenasConfig != null) {
            saveArenas();
        }
        if (statsConfig != null) {
            saveStats();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        openBoatUtilsPlayers.clear();
        playerArenaMap.clear();
        try {
            PacketEvents.getAPI().terminate();
        } catch (Exception ignored) {
        }
    }

    public void reload() {
        reloadConfig();
        loadConfigSettings();
        loadMessages();
        if (hologramManager != null) {
            hologramManager.removeAll();
        }
        arenas.clear();
        loadArenasConfig();
        loadArenas();
        if (hologramManager != null) {
            hologramManager.purgeAllOrphanedHolograms();
        }
        getLogger().info("Configuration reloaded.");
    }

    // VOTING LOGIC

    public void startVotingRound(int durationSeconds) {
        if (isVoting)
            return;
        isVoting = true;
        votingTimeRemaining = durationSeconds;
        playerVotes.clear();

        Bukkit.broadcast(Component.text("---------------------------------------", NamedTextColor.GREEN));
        Bukkit.broadcast(Component.text(" 🗳️ Map Voting has started!", NamedTextColor.YELLOW));
        Bukkit.broadcast(Component.text(" Type /race vote to choose the next map!", NamedTextColor.AQUA));
        Bukkit.broadcast(Component.text("---------------------------------------", NamedTextColor.GREEN));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
        }

        votingTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (votingTimeRemaining <= 0) {
                    endVotingRound();
                    cancel();
                    return;
                }

                if (votingTimeRemaining == 30 || votingTimeRemaining == 10 || votingTimeRemaining <= 5) {
                    Bukkit.broadcast(
                            Component.text("Voting ends in " + votingTimeRemaining + "s...", NamedTextColor.GRAY));
                }

                votingTimeRemaining--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    public void endVotingRound() {
        isVoting = false;
        if (votingTask != null)
            votingTask.cancel();

        Map<String, Integer> counts = new HashMap<>();
        for (String arena : playerVotes.values()) {
            counts.put(arena, counts.getOrDefault(arena, 0) + 1);
        }

        String winner = null;
        int max = -1;

        if (counts.isEmpty()) {
            List<String> keys = new ArrayList<>(arenas.keySet());
            if (!keys.isEmpty())
                winner = keys.get(new Random().nextInt(keys.size()));
        } else {
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > max) {
                    max = entry.getValue();
                    winner = entry.getKey();
                }
            }
        }

        if (winner == null || !arenas.containsKey(winner)) {
            Bukkit.broadcast(Component.text("Voting ended. No arenas available.", NamedTextColor.RED));
            return;
        }

        RaceArena winningArena = arenas.get(winner);
        Bukkit.broadcast(Component.text("---------------------------------------", NamedTextColor.GREEN));
        Bukkit.broadcast(Component.text(" 🏆 Voting Finished!", NamedTextColor.GOLD));
        Bukkit.broadcast(Component.text(" Next Map: " + winningArena.getName(), NamedTextColor.AQUA));
        Bukkit.broadcast(Component.text("---------------------------------------", NamedTextColor.GREEN));

        for (UUID uuid : playerVotes.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline() && !isRacer(uuid)) {
                winningArena.addPlayer(p);
            }
        }
    }

    public void castVote(Player p, String arenaName) {
        if (!isVoting) {
            p.sendMessage(Component.text("No voting in progress.", NamedTextColor.RED));
            return;
        }
        playerVotes.put(p.getUniqueId(), arenaName);
        p.sendMessage(Component.text("You voted for " + arenaName, NamedTextColor.GREEN));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    public int getVoteCount(String arenaName) {
        int count = 0;
        for (String s : playerVotes.values()) {
            if (s.equals(arenaName))
                count++;
        }
        return count;
    }

    private void checkConfigUpdates() {
        if (!getConfig().contains("victory.rewards")) {
            getConfig().set("victory.rewards.enabled", false);
            getConfig().set("victory.rewards.min-players", 2);
            getConfig().set("victory.rewards.1", Arrays.asList("eco give %player% 500"));
            getConfig().set("victory.rewards.2", Arrays.asList("eco give %player% 250"));
            getConfig().set("victory.rewards.3", Arrays.asList("eco give %player% 100"));
            saveConfig();
            getLogger().info("Updated config.yml with new victory.rewards section.");
        }
        if (!getConfig().contains("settings.collision-mode")) {
            getConfig().set("settings.collision-mode", "DEFAULT");
            saveConfig();
        }
        if (!getConfig().contains("settings.cage-size")) {
            getConfig().set("settings.cage-size", 3);
            saveConfig();
        }
    }

    // CONFIG HELPERS
    private void loadArenasConfig() {
        arenasFile = new File(getDataFolder(), "arenas.yml");
        if (!arenasFile.exists()) {
            try {
                arenasFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile);
    }

    public void saveArenasConfig() {
        if (arenasConfig != null && arenasFile != null) {
            AsyncIO.saveConfigAsync(arenasConfig, arenasFile, this);
        }
    }

    private void loadMessages() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists())
            saveResource("messages.yml", false);
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public Component getMessage(String key) {
        String prefix = messagesConfig.getString("prefix", "&b[IceBoat] ");
        String msg = messagesConfig.getString(key, "&cMissing message: " + key);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + msg);
    }

    public String getRawMessage(String key) {
        return messagesConfig.getString(key, key);
    }

    private void loadStats() {
        statsFile = new File(getDataFolder(), "stats.yml");
        if (!statsFile.exists()) {
            try {
                statsFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
    }

    public void saveStats() {
        if (statsConfig != null && statsFile != null) {
            AsyncIO.saveConfigAsync(statsConfig, statsFile, this);
        }
    }

    public void incrementStat(UUID uuid, String stat) {
        String path = uuid.toString() + "." + stat;
        int current = statsConfig.getInt(path, 0);
        statsConfig.set(path, current + 1);
        saveStats();
    }

    public int getStat(UUID uuid, String stat) {
        return statsConfig.getInt(uuid.toString() + "." + stat, 0);
    }

    // COSMETICS
    public Material getPlayerCagePreference(UUID uuid) {
        return playerCagePreference.getOrDefault(uuid, Material.GLASS);
    }

    public void setPlayerCagePreference(UUID uuid, Material mat) {
        playerCagePreference.put(uuid, mat);
    }

    public TrailType getPlayerTrailPreference(UUID uuid) {
        return playerTrailPreference.getOrDefault(uuid, TrailType.SMOKE);
    }

    public void setPlayerTrailPreference(UUID uuid, TrailType trail) {
        playerTrailPreference.put(uuid, trail);
    }

    // ARENA MANAGEMENT
    public RaceArena getArena(String name) {
        return arenas.get(name.toLowerCase());
    }

    public Map<String, RaceArena> getArenas() {
        return arenas;
    }

    public void addArena(String name, RaceArena arena) {
        arenas.put(name.toLowerCase(), arena);
    }

    public void removeArena(String name) {
        RaceArena arena = arenas.remove(name.toLowerCase());
        if (arena != null) {
            arena.stopRace();
            arena.deleteLeaderboardHologram();
        }
    }

    public RaceArena getPlayerArena(UUID uuid) {
        String name = playerArenaMap.get(uuid);
        return (name != null) ? arenas.get(name) : null;
    }

    public void setPlayerArena(UUID uuid, String arenaName) {
        playerArenaMap.put(uuid, arenaName);
    }

    public void removePlayerFromArenaMap(UUID uuid) {
        playerArenaMap.remove(uuid);
    }

    public boolean isRacer(UUID uuid) {
        return playerArenaMap.containsKey(uuid);
    }

    public boolean isRaceBoat(Boat boat) {
        if (boat == null) return false;
        if (boat.getPersistentDataContainer().has(new NamespacedKey(this, "race_boat"), PersistentDataType.BYTE)) {
            return true;
        }
        for (RaceArena arena : arenas.values()) {
            if (arena.isBoatInArena(boat)) {
                return true;
            }
        }
        return false;
    }

    // SAVE LOGIC (ARENAS.YML)
    public void saveArenas() {
        getConfig().set("settings.checkpoint-radius", checkpointRadius);
        getConfig().set("settings.discord-webhook-url", discordWebhookUrl);
        getConfig().set("settings.cage-size", cageSize);

        getConfig().set("music.enabled", musicEnabled);
        getConfig().set("music.sound-name", musicSound);
        getConfig().set("music.loop-duration-seconds", musicDuration);
        getConfig().set("music.volume", musicVolume);
        getConfig().set("music.pitch", musicPitch);

        saveConfig();

        arenasConfig.set("arenas", null);
        for (RaceArena arena : arenas.values()) {
            String path = "arenas." + arena.getName();
            arenasConfig.set(path + ".type", arena.getType().name());
            arenasConfig.set(path + ".laps", arena.getTotalLaps());
            arenasConfig.set(path + ".min-players", arena.minPlayers);
            arenasConfig.set(path + ".auto-start-delay", arena.autoStartDelay);
            arenasConfig.set(path + ".void-y", arena.voidY);
            arenasConfig.set(path + ".lobby", arena.getLobby());
            arenasConfig.set(path + ".mainlobby", arena.getMainLobby());
            arenasConfig.set(path + ".finish1", arena.getFinishPos1());
            arenasConfig.set(path + ".finish2", arena.getFinishPos2());
            arenasConfig.set(path + ".leaderboard", arena.getLeaderboardLocation());
            arenasConfig.set(path + ".spawns", arena.getSpawns());
            arenasConfig.set(path + ".checkpoints", arena.getCheckpoints());

            if (!arena.bestTimes.isEmpty()) {
                for (Map.Entry<UUID, Long> entry : arena.bestTimes.entrySet()) {
                    arenasConfig.set(path + ".best_times." + entry.getKey().toString(), entry.getValue());
                }
            }
        }
        saveArenasConfig();
    }

    private void loadConfigSettings() {
        this.checkpointRadius = getConfig().getDouble("settings.checkpoint-radius", 25.0);
        this.discordWebhookUrl = getConfig().getString("settings.discord-webhook-url", "");
        this.collisionMode = getConfig().getString("settings.collision-mode", "DEFAULT").toUpperCase();
        this.cageSize = getConfig().getInt("settings.cage-size", 3);

        this.musicEnabled = getConfig().getBoolean("music.enabled", true);
        this.musicSound = getConfig().getString("music.sound-name", "minecraft:coconutmallmariokartwiiostfourone");
        this.musicDuration = getConfig().getInt("music.loop-duration-seconds", 180);
        this.musicVolume = (float) getConfig().getDouble("music.volume", 10000.0);
        this.musicPitch = (float) getConfig().getDouble("music.pitch", 1.0);

        this.rewardsEnabled = getConfig().getBoolean("victory.rewards.enabled", false);
        this.rewardsMinPlayers = getConfig().getInt("victory.rewards.min-players", 2);
        this.rewardCommands.clear();
        
        if (this.rewardsEnabled) {
            org.bukkit.configuration.ConfigurationSection rewardsSection = getConfig().getConfigurationSection("victory.rewards");
            if (rewardsSection != null) {
                for (String key : rewardsSection.getKeys(false)) {
                    if (key.equals("enabled") || key.equals("min-players")) continue;
                    try {
                        int rank = Integer.parseInt(key);
                        List<String> commands = rewardsSection.getStringList(key);
                        if (commands != null && !commands.isEmpty()) {
                            this.rewardCommands.put(rank, commands);
                        }
                    } catch (NumberFormatException ignored) {
                        // Ignore non-integer keys
                    }
                }
            }
        }
    }

    private void loadArenas() {
        ConfigurationSection section = arenasConfig.getConfigurationSection("arenas");
        if (section == null)
            return;

        for (String key : section.getKeys(false)) {
            if (arenas.containsKey(key.toLowerCase()))
                continue;

            String path = "arenas." + key;
            RaceArena arena = new RaceArena(key, this);

            try {
                arena.setType(peyaj.arena.RaceType.valueOf(arenasConfig.getString(path + ".type", "DEFAULT")));
            } catch (Exception e) {
                arena.setType(peyaj.arena.RaceType.DEFAULT);
            }

            arena.setTotalLaps(arenasConfig.getInt(path + ".laps", 1));
            arena.minPlayers = arenasConfig.getInt(path + ".min-players", 2);
            arena.autoStartDelay = arenasConfig.getInt(path + ".auto-start-delay", 30);
            arena.voidY = arenasConfig.getInt(path + ".void-y", -64);

            arena.setLobby(arenasConfig.getLocation(path + ".lobby"));
            arena.setMainLobby(arenasConfig.getLocation(path + ".mainlobby"));
            arena.setLeaderboardLocation(arenasConfig.getLocation(path + ".leaderboard"), false);

            arena.setFinishLine(
                    arenasConfig.getLocation(path + ".finish1"),
                    arenasConfig.getLocation(path + ".finish2"));

            List<?> loadedSpawns = arenasConfig.getList(path + ".spawns");
            if (loadedSpawns != null)
                for (Object obj : loadedSpawns)
                    if (obj instanceof Location)
                        arena.addSpawn((Location) obj);

            List<?> loadedCheckpoints = arenasConfig.getList(path + ".checkpoints");
            if (loadedCheckpoints != null)
                for (Object obj : loadedCheckpoints)
                    if (obj instanceof Location)
                        arena.addCheckpoint((Location) obj);

            ConfigurationSection timeSection = arenasConfig.getConfigurationSection(path + ".best_times");
            if (timeSection != null) {
                for (String uuidStr : timeSection.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        long time = timeSection.getLong(uuidStr);
                        arena.bestTimes.put(uuid, time);
                    } catch (Exception ignored) {
                    }
                }
            }

            arenas.put(key.toLowerCase(), arena);
            Location lb = arena.getLeaderboardLocation();
            if (lb != null && lb.getWorld() != null && lb.getWorld().isChunkLoaded(lb.getBlockX() >> 4, lb.getBlockZ() >> 4)) {
                arena.updateLeaderboardHologram();
            }
            getLogger().info("Loaded arena: " + key);
        }
    }

    private void sendStartupBanner() {
        Bukkit.getConsoleSender()
                .sendMessage(Component.text("                                                 ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender()
                .sendMessage(Component.text("  ___   ____  _____ ____   ___    _  _____ ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender()
                .sendMessage(Component.text(" |_ _| / ___|| ____| __ ) / _ \\  / \\|_   _|", NamedTextColor.AQUA));
        Bukkit.getConsoleSender()
                .sendMessage(Component.text("  | | | |    |  _| |  _ \\| | | |/ _ \\ | |  ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender()
                .sendMessage(Component.text("  | | | |___ | |___| |_) | |_| / ___ \\| |  ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender()
                .sendMessage(Component.text(" |___| \\____||_____|____/ \\___/_/   \\_\\_|  ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender()
                .sendMessage(Component.text("                                                 ", NamedTextColor.AQUA));
        String versionInfo = "   v" + getPluginMeta().getVersion() + " by "
                + String.join(", ", getPluginMeta().getAuthors()) + " enabled!";
        Bukkit.getConsoleSender().sendMessage(Component.text(versionInfo, NamedTextColor.GREEN));
        Bukkit.getConsoleSender()
                .sendMessage(Component.text("   Parties, Replays, 17 Trails, PAPI Support", NamedTextColor.YELLOW));
    }

    public HologramManager getHologramManager() {
        return hologramManager;
    }
}
