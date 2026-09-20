package peyaj;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import peyaj.arena.RaceState;
import peyaj.arena.RaceType;
import peyaj.arena.SpectatorMode;
import peyaj.cosmetics.TrailType;
import peyaj.data.GhostData;
import peyaj.replay.ReplayData;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class RaceArena {

    private final String name;
    private final IceBoatRacing plugin;

    private RaceType type = RaceType.DEFAULT;
    private int totalLaps = 1;
    private RaceState state = RaceState.LOBBY;

    // Locations
    private final List<Location> spawns = new ArrayList<>();
    private final List<Location> checkpoints = new ArrayList<>();
    private Location lobby;
    private Location mainLobby;
    private Location leaderboardLocation;

    private Location finishPos1, finishPos2;
    private BoundingBox finishBox;
    private Location finishCenter;

    // Settings
    public int minPlayers = 2;
    public int autoStartDelay = 30;
    public int voidY = -64;

    // Runtime Data
    private final Map<UUID, Integer> playerCheckpoints = new HashMap<>();
    private final Map<UUID, Integer> playerLaps = new HashMap<>();
    private final Map<UUID, Long> startTimes = new HashMap<>();
    private final Map<UUID, String> finishTimes = new HashMap<>();
    private final Map<UUID, Long> finishTimesMs = new HashMap<>();
    private final Map<UUID, Boat> playerBoats = new HashMap<>();
    private final Set<UUID> players = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final List<UUID> finishOrder = new ArrayList<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final List<Location> glassBlocks = new ArrayList<>();
    private final Map<UUID, Location> playerSpawnLocations = new HashMap<>();

    // Spectator modes
    private final Map<UUID, SpectatorMode> spectatorModes = new HashMap<>();
    private final Map<UUID, UUID> spectatorTargets = new HashMap<>();

    // Leaderboard Data
    public final Map<UUID, Long> bestTimes = new HashMap<>();

    // Player Inventory Backups
    private final Map<UUID, ItemStack[]> savedInventories = new HashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor = new HashMap<>();
    private final Map<UUID, ItemStack> savedOffHand = new HashMap<>();
    private final Map<UUID, Integer> savedLevels = new HashMap<>();
    private final Map<UUID, Float> savedExp = new HashMap<>();
    private final Map<UUID, GameMode> savedGameModes = new HashMap<>();

    // Ghost & Replay
    private final Map<UUID, GhostData> currentRecordings = new HashMap<>();
    private GhostData bestGhost = null;
    private int ghostPlaybackTick = 0;
    private Boat visualGhostBoat = null;
    private ReplayData currentReplay = null;

    private final Map<UUID, Map<Integer, Long>> checkpointTimestamps = new HashMap<>();

    // Utils
    private int tickCounter = 0;
    private BukkitTask autoStartTask = null;
    private int lobbyCountdown = -1;
    private int raceStartCountdown = -1;
    private BukkitTask musicTask = null;
    private boolean isTimeTrialMode = false;

    // Elimination mode tracking
    private final Set<UUID> eliminatedPlayers = new HashSet<>();
    private int currentLapForElimination = 0;

    // Fake entity IDs for ghosts
    private final Map<UUID, Integer> ghostEntityIds = new HashMap<>();

    // Teleport grace tracking to prevent PacketEvents from blocking intentional teleports
    private final Map<UUID, Long> teleportGraceUntil = new java.util.concurrent.ConcurrentHashMap<>();

    public void markTeleportGrace(UUID uuid) {
        teleportGraceUntil.put(uuid, System.currentTimeMillis() + 1000L);
    }

    public boolean hasTeleportGrace(UUID uuid) {
        Long until = teleportGraceUntil.get(uuid);
        return until != null && System.currentTimeMillis() < until;
    }

    public boolean isBoatInArena(Boat boat) {
        if (boat == null) return false;
        return playerBoats.containsValue(boat);
    }

    public void updateNoCollisionTeam() {
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                Scoreboard b = p.getScoreboard();
                Team racers = b.getTeam("ibr_racers");
                if (racers == null) {
                    racers = b.registerNewTeam("ibr_racers");
                    racers.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
                }
                for (UUID otherUuid : players) {
                    Player other = Bukkit.getPlayer(otherUuid);
                    if (other != null && other.isOnline()) {
                        racers.addEntry(other.getName());
                    }
                    Boat boat = playerBoats.get(otherUuid);
                    if (boat != null && boat.isValid()) {
                        try {
                            racers.addEntity(boat);
                        } catch (Throwable t) {
                            racers.addEntry(boat.getUniqueId().toString());
                        }
                    }
                }
            }
        }
    }

    public RaceArena(String name, IceBoatRacing plugin) {
        this.name = name;
        this.plugin = plugin;
    }

    // GETTERS & SETTERS
    public String getName() {
        return name;
    }

    public RaceType getType() {
        return type;
    }

    public void setType(RaceType type) {
        this.type = type;
    }

    public int getTotalLaps() {
        return totalLaps;
    }

    public void setTotalLaps(int laps) {
        this.totalLaps = Math.max(1, laps);
        if (this.totalLaps > 1 && this.type == RaceType.DEFAULT) {
            this.type = RaceType.LAP;
        } else if (this.totalLaps == 1 && this.type == RaceType.LAP) {
            this.type = RaceType.DEFAULT;
        }
    }

    public Location getLobby() {
        return lobby;
    }

    public void setLobby(Location loc) {
        this.lobby = loc;
    }

    public Location getMainLobby() {
        return mainLobby;
    }

    public void setMainLobby(Location loc) {
        this.mainLobby = loc;
    }

    public List<Location> getSpawns() {
        return spawns;
    }

    public List<Location> getCheckpoints() {
        return checkpoints;
    }

    public Location getFinishPos1() {
        return finishPos1;
    }

    public Location getFinishPos2() {
        return finishPos2;
    }

    public BoundingBox getFinishBox() {
        return finishBox;
    }

    public RaceState getState() {
        return state;
    }

    public int getPlayerCount() {
        return players.size();
    }

    public boolean isTimeTrial() {
        return isTimeTrialMode;
    }

    public Location getLeaderboardLocation() {
        return leaderboardLocation;
    }

    public void setLeaderboardLocation(Location loc) {
        setLeaderboardLocation(loc, true);
    }

    public void setLeaderboardLocation(Location loc, boolean updateHologram) {
        Location oldLoc = this.leaderboardLocation;
        this.leaderboardLocation = (loc != null) ? loc.clone() : null;
        if (updateHologram && plugin.getHologramManager() != null) {
            String holoName = "race_lb_" + name;
            if (this.leaderboardLocation == null) {
                plugin.getHologramManager().removeHologram(holoName);
                if (oldLoc != null) {
                    plugin.getHologramManager().cleanUpHologramAt(holoName, oldLoc);
                }
            } else {
                updateLeaderboardHologram(oldLoc);
            }
        }
    }

    public boolean isSpectator(UUID uuid) {
        return spectators.contains(uuid);
    }

    public Location getPlayerSpawn(UUID uuid) {
        return playerSpawnLocations.get(uuid);
    }

    public void addSpawn(Location loc) {
        spawns.add(loc);
    }

    public void addCheckpoint(Location loc) {
        checkpoints.add(loc);
    }

    public boolean removeNodeAtBlock(List<Location> list, Location clickedBlockLoc) {
        Iterator<Location> it = list.iterator();
        while (it.hasNext()) {
            Location nodeLoc = it.next();
            if (nodeLoc.getWorld().equals(clickedBlockLoc.getWorld()) &&
                    nodeLoc.getBlockX() == clickedBlockLoc.getBlockX() &&
                    nodeLoc.getBlockZ() == clickedBlockLoc.getBlockZ() &&
                    (nodeLoc.getBlockY() == clickedBlockLoc.getBlockY()
                            || nodeLoc.getBlockY() == clickedBlockLoc.getBlockY() + 1)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public void setFinishLine(Location p1, Location p2) {
        this.finishPos1 = p1;
        this.finishPos2 = p2;
        recalculateFinishBox();
    }

    public void recalculateFinishBox() {
        if (finishPos1 != null && finishPos2 != null && finishPos1.getWorld() != null
                && finishPos1.getWorld().equals(finishPos2.getWorld())) {
            finishBox = BoundingBox.of(finishPos1, finishPos2).expand(0, 10.0, 0);
            finishCenter = finishBox.getCenter().toLocation(finishPos1.getWorld());
        }
    }

    // HOLOGRAMS
    public void updateLeaderboardHologram() {
        updateLeaderboardHologram(null);
    }

    public void updateLeaderboardHologram(Location oldLoc) {
        if (leaderboardLocation == null || plugin.getHologramManager() == null)
            return;
        try {
            String holoName = "race_lb_" + name;
            List<String> lines = getLeaderboardLines();
            if (oldLoc != null && !oldLoc.equals(leaderboardLocation)) {
                plugin.getHologramManager().moveOrUpdateHologram(holoName, oldLoc, leaderboardLocation, lines);
            } else {
                plugin.getHologramManager().createOrUpdateHologram(holoName, leaderboardLocation, lines);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to update leaderboard hologram for " + name + ": " + e.getMessage());
        }
    }

    public List<String> getLeaderboardLines() {
        List<String> lines = new ArrayList<>();
        lines.add("&b&l❄ " + name.toUpperCase() + " LEADERBOARD ❄");
        lines.add("&7------------------------");
        List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(bestTimes.entrySet());
        sorted.sort(Map.Entry.comparingByValue());
        int limit = Math.min(sorted.size(), 10);
        for (int i = 0; i < limit; i++) {
            UUID uuid = sorted.get(i).getKey();
            long time = sorted.get(i).getValue();
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String pName = (op.getName() != null) ? op.getName() : "Unknown";
            String color = (i == 0) ? "&e" : (i == 1) ? "&f" : (i == 2) ? "&6" : "&7";
            lines.add(color + (i + 1) + ". &f" + pName + " &7- &b" + Utils.formatTime(time));
        }
        if (limit == 0)
            lines.add("&7No records yet!");
        lines.add("&7------------------------");
        return lines;
    }

    public void deleteLeaderboardHologram() {
        if (plugin.getHologramManager() != null) {
            String holoName = "race_lb_" + name;
            plugin.getHologramManager().removeHologram(holoName);
            if (leaderboardLocation != null) {
                plugin.getHologramManager().cleanUpHologramAt(holoName, leaderboardLocation);
            }
        }
    }

    // PLAYER MANAGEMENT
    public void addPlayer(Player p) {
        addPlayer(p, false);
    }

    public void addPlayer(Player p, boolean timeTrial) {
        if (state != RaceState.LOBBY && !timeTrial) {
            addSpectator(p);
            return;
        }
        if (timeTrial && state == RaceState.ACTIVE) {
            p.sendMessage(plugin.getMessage("race-already-active"));
            return;
        }

        if (timeTrial && !players.isEmpty()) {
            p.sendMessage(
                    Component.text("Lobby is not empty! Joining match instead of Time Trial.", NamedTextColor.YELLOW));
            timeTrial = false;
        }

        savePlayerInventory(p);
        players.add(p.getUniqueId());
        plugin.setPlayerArena(p.getUniqueId(), name);
        if (lobby != null && lobby.getWorld() != null) {
            p.teleport(lobby);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        }
        giveLobbyItems(p, timeTrial);
        playerCheckpoints.put(p.getUniqueId(), 0);
        playerLaps.put(p.getUniqueId(), 1);
        p.sendMessage(plugin.getMessage("arena-joined").replaceText(b -> b.matchLiteral("{arena}").replacement(name)));

        if (timeTrial) {
            startRace(true);
        } else {
            checkAutoStart();
        }
        updateLobbyScoreboard();
    }

    public void addSpectator(Player p) {
        savePlayerInventory(p);
        spectators.add(p.getUniqueId());
        spectatorModes.put(p.getUniqueId(), SpectatorMode.FREE_FLY);
        if (!spawns.isEmpty())
            p.teleport(spawns.get(0));
        else if (lobby != null)
            p.teleport(lobby);

        p.setGameMode(GameMode.SPECTATOR);

        // Fallback for Multiverse-Core or other plugins that force world gamemode on world change
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && spectators.contains(p.getUniqueId())) {
                p.setGameMode(GameMode.SPECTATOR);
            }
        }, 2L);

        p.sendMessage(
                plugin.getMessage("arena-spectating").replaceText(b -> b.matchLiteral("{arena}").replacement(name)));
        p.showTitle(Title.title(Component.text("SPECTATING", NamedTextColor.GREEN),
                Component.text("You are watching " + name, NamedTextColor.AQUA)));
        p.playSound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1f, 1f);
        giveSpectatorItems(p);
        setupRaceScoreboard(p);
        plugin.setPlayerArena(p.getUniqueId(), name);
        for (UUID uuid : players) {
            Player racer = Bukkit.getPlayer(uuid);
            Boat boat = playerBoats.get(uuid);
            if (racer != null && racer.isOnline()) p.showEntity(plugin, racer);
            if (boat != null && boat.isValid()) p.showEntity(plugin, boat);
        }
    }

    private void giveSpectatorItems(Player p) {
        p.getInventory().clear();

        // Compass to select player
        ItemStack compass = new ItemStack(Material.COMPASS);
        var compassMeta = compass.getItemMeta();
        compassMeta.displayName(Component.text("Select Player", NamedTextColor.YELLOW));
        compass.setItemMeta(compassMeta);
        p.getInventory().setItem(0, compass);

        // Clock to cycle modes
        ItemStack clock = new ItemStack(Material.CLOCK);
        var clockMeta = clock.getItemMeta();
        clockMeta.displayName(Component.text(
                "Camera Mode: " + spectatorModes.getOrDefault(p.getUniqueId(), SpectatorMode.FREE_FLY).displayName,
                NamedTextColor.AQUA));
        clock.setItemMeta(clockMeta);
        p.getInventory().setItem(4, clock);

        // Barrier to exit
        ItemStack barrier = new ItemStack(Material.BARRIER);
        var barrierMeta = barrier.getItemMeta();
        barrierMeta.displayName(Component.text("Leave Spectating", NamedTextColor.RED));
        barrier.setItemMeta(barrierMeta);
        p.getInventory().setItem(8, barrier);
    }

    public void cycleSpectatorMode(Player p) {
        SpectatorMode current = spectatorModes.getOrDefault(p.getUniqueId(), SpectatorMode.FREE_FLY);
        SpectatorMode next = current.next();
        spectatorModes.put(p.getUniqueId(), next);
        if (next == SpectatorMode.FREE_FLY) {
            try {
                if (p.getGameMode() == GameMode.SPECTATOR) {
                    p.setSpectatorTarget(null);
                }
            } catch (Exception ignored) {
            }
        }
        p.sendMessage(
                Component.text("Camera mode: " + next.displayName + " - " + next.description, NamedTextColor.AQUA));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        giveSpectatorItems(p);
    }

    private void giveLobbyItems(Player p, boolean isTimeTrial) {
        p.getInventory().clear();
        ItemStack compass = new ItemStack(Material.COMPASS);
        var meta = compass.getItemMeta();
        meta.displayName(Component.text("Race Menu", NamedTextColor.AQUA));
        meta.lore(List.of(Component.text("Right Click to open", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "race_menu"), PersistentDataType.BYTE, (byte) 1);
        compass.setItemMeta(meta);
        p.getInventory().setItem(4, compass);

        if (isTimeTrial) {
            ItemStack reset = new ItemStack(Material.RED_DYE);
            var rMeta = reset.getItemMeta();
            rMeta.displayName(Component.text("Reset Run", NamedTextColor.RED));
            rMeta.lore(List.of(Component.text("Right Click to restart", NamedTextColor.GRAY)));
            reset.setItemMeta(rMeta);
            p.getInventory().setItem(8, reset);
        }
    }

    public void resetTimeTrial(Player p) {
        if (!isTimeTrialMode || !players.contains(p.getUniqueId()))
            return;
        p.sendMessage(Component.text("↺ Run Reset!", NamedTextColor.YELLOW));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 2f);

        if (playerBoats.containsKey(p.getUniqueId())) {
            Boat b = playerBoats.remove(p.getUniqueId());
            if (b != null)
                b.remove();
        }

        if (visualGhostBoat != null) {
            visualGhostBoat.remove();
            visualGhostBoat = null;
        }
        ghostPlaybackTick = 0;

        currentRecordings.put(p.getUniqueId(), new GhostData(p.getName(), 0));
        checkpointTimestamps.put(p.getUniqueId(), new HashMap<>());
        playerCheckpoints.put(p.getUniqueId(), 0);
        playerLaps.put(p.getUniqueId(), 1);
        finishOrder.remove(p.getUniqueId());
        finishTimes.remove(p.getUniqueId());
        finishTimesMs.remove(p.getUniqueId());

        markTeleportGrace(p.getUniqueId());
        startRace(true);
    }

    public void removePlayer(Player p) {
        if (spectators.contains(p.getUniqueId())) {
            try {
                if (p.getGameMode() == GameMode.SPECTATOR) {
                    p.setSpectatorTarget(null);
                }
            } catch (Exception ignored) {
            }
            spectators.remove(p.getUniqueId());
            spectatorModes.remove(p.getUniqueId());
            spectatorTargets.remove(p.getUniqueId());
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            restorePlayerInventory(p);
            if (mainLobby != null && mainLobby.getWorld() != null)
                p.teleport(mainLobby);
            else if (p.getWorld() != null)
                p.teleport(p.getWorld().getSpawnLocation());
            plugin.removePlayerFromArenaMap(p.getUniqueId());
            p.sendMessage(plugin.getMessage("spectator-left"));
            return;
        }
        players.remove(p.getUniqueId());
        eliminatedPlayers.remove(p.getUniqueId());
        if (playerBoats.containsKey(p.getUniqueId())) {
            Boat b = playerBoats.remove(p.getUniqueId());
            if (b != null)
                b.remove();
        }
        currentRecordings.remove(p.getUniqueId());
        checkpointTimestamps.remove(p.getUniqueId());
        stopMusic(p);
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        restorePlayerInventory(p);
        if (mainLobby != null && mainLobby.getWorld() != null)
            p.teleport(mainLobby);
        else if (p.getWorld() != null)
            p.teleport(p.getWorld().getSpawnLocation());
        plugin.removePlayerFromArenaMap(p.getUniqueId());
        restoreAllVisibility();

        if (players.isEmpty()) {
            if (state != RaceState.LOBBY)
                stopRace();
            cancelAutoStart();
        } else if (state == RaceState.LOBBY) {
            checkAutoStart();
        } else if (state == RaceState.ACTIVE) {
            checkFinishCondition();
        }
        updateLobbyScoreboard();
    }

    public void checkFinishCondition() {
        if (state != RaceState.ACTIVE)
            return;
        boolean allFinished = true;
        for (UUID uuid : players) {
            if (!finishOrder.contains(uuid) && !eliminatedPlayers.contains(uuid)) {
                allFinished = false;
                break;
            }
        }
        if (players.isEmpty() || allFinished) {
            Bukkit.broadcast(plugin.getMessage("race-ended"));
            new BukkitRunnable() {
                @Override
                public void run() {
                    stopRace();
                }
            }.runTaskLater(plugin, 100L);
        }
    }

    // GAME LOOP
    public void startRace() {
        startRace(false);
    }

    public void startRace(boolean isTimeTrialSession) {
        if (spawns.isEmpty())
            return;
        cancelAutoStart();

        this.isTimeTrialMode = isTimeTrialSession;
        this.currentLapForElimination = 0;
        eliminatedPlayers.clear();

        state = RaceState.STARTING;
        removeCages();
        finishOrder.clear();
        finishTimes.clear();
        finishTimesMs.clear();
        currentRecordings.clear();
        checkpointTimestamps.clear();
        ghostPlaybackTick = 0;
        tickCounter = 0;

        // Start replay recording
        if (!isTimeTrialSession && players.size() > 1) {
            currentReplay = plugin.replayManager.startRecording(name);
        }

        if (visualGhostBoat != null) {
            visualGhostBoat.remove();
            visualGhostBoat = null;
        }

        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null)
                p.getInventory().clear();
        }

        int spawnIndex = 0;
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null)
                continue;

            Location spawn = spawns.get(spawnIndex % spawns.size());
            if (spawn.getWorld() == null)
                continue;

            spawnIndex++;
            Location boatSpawn = spawn.clone();
            if (plugin.cageSize == 2) {
                boatSpawn.setX(spawn.getBlockX() + 1.0);
                boatSpawn.setZ(spawn.getBlockZ() + 1.0);
            }
            playerSpawnLocations.put(uuid, spawn.clone());
            markTeleportGrace(uuid);
            p.teleport(boatSpawn);
            Boat boat = Utils.spawnRandomBoat(boatSpawn);
            boat.getPersistentDataContainer().set(new NamespacedKey(plugin, "race_boat"), PersistentDataType.BYTE, (byte) 1);
            boat.getPersistentDataContainer().set(new NamespacedKey(plugin, "race_arena"), PersistentDataType.STRING, name);
            boat.addPassenger(p);
            boat.setInvulnerable(true);
            playerBoats.put(uuid, boat);
            currentRecordings.put(uuid, new GhostData(p.getName(), 0));
            checkpointTimestamps.put(uuid, new HashMap<>());
            createCage(spawn, uuid);
            playerCheckpoints.put(uuid, 0);
            playerLaps.put(uuid, 1);
            setupRaceScoreboard(p);
        }
        updateNoCollisionTeam();
        syncGhostMode();

        raceStartCountdown = 5;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (state != RaceState.STARTING) {
                    removeCages();
                    this.cancel();
                    return;
                }
                if (raceStartCountdown == 3)
                    startMusic();
                if (raceStartCountdown > 0) {
                    // TRAFFIC LIGHT ANIMATION
                    Color lightColor;
                    if (raceStartCountdown >= 4) {
                        lightColor = Color.RED;
                    } else if (raceStartCountdown >= 2) {
                        lightColor = Color.YELLOW;
                    } else {
                        lightColor = Color.LIME;
                    }

                    Title title = Title.title(
                            Component.text(raceStartCountdown,
                                    raceStartCountdown >= 4 ? NamedTextColor.RED
                                            : raceStartCountdown >= 2 ? NamedTextColor.YELLOW : NamedTextColor.GREEN),
                            Component.empty());

                    for (UUID uuid : players) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            p.showTitle(title);
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f,
                                    0.5f + ((5 - raceStartCountdown) * 0.3f));
                            Boat boat = playerBoats.get(uuid);
                            if (boat != null) {
                                // Spawn traffic light particles above boat
                                Location lightLoc = boat.getLocation().add(0, 3, 0);
                                p.getWorld().spawnParticle(Particle.DUST, lightLoc, 15, 0.3, 0.3, 0.3, 0,
                                        new Particle.DustOptions(lightColor, 2f));
                            }
                        }
                    }
                    raceStartCountdown--;
                } else {
                    removeCages();
                    state = RaceState.ACTIVE;
                    long now = System.currentTimeMillis();
                    for (UUID uuid : players) {
                        startTimes.put(uuid, now);
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            Component goTitle = LegacyComponentSerializer.legacyAmpersand()
                                    .deserialize(plugin.getRawMessage("race-started"));
                            p.showTitle(Title.title(goTitle, Component.empty()));
                            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1f);
                            lastLocations.put(uuid, p.getLocation());

                            // Green burst on GO
                            Location boatLoc = p.getLocation();
                            p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, boatLoc.add(0, 2, 0), 30, 1, 0.5, 1, 0);
                        }
                    }
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public void stopRace() {
        cancelAutoStart();
        stopAllMusic();

        // Send Discord results
        if (!finishOrder.isEmpty() && !plugin.discordWebhookUrl.isEmpty()) {
            plugin.discordWebhook.sendRaceResults(plugin.discordWebhookUrl, name, finishOrder, finishTimes);
        }

        // Save replay
        if (currentReplay != null && !finishOrder.isEmpty()) {
            plugin.replayManager.finishRecording(currentReplay, finishTimesMs);
            currentReplay = null;
        }

        state = RaceState.LOBBY;
        isTimeTrialMode = false;

        restoreAllVisibility();
        removeCages();
        for (Boat b : playerBoats.values())
            b.remove();
        playerBoats.clear();
        finishOrder.clear();
        finishTimes.clear();
        finishTimesMs.clear();
        currentRecordings.clear();
        checkpointTimestamps.clear();
        eliminatedPlayers.clear();
        playerSpawnLocations.clear();

        // Clean up fake entities
        for (UUID uuid : players) {
            if (ghostEntityIds.containsKey(uuid)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null)
                    PacketUtils.destroyFakeEntity(p, ghostEntityIds.get(uuid));
            }
        }
        ghostEntityIds.clear();

        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                restorePlayerInventory(p);
                if (mainLobby != null && mainLobby.getWorld() != null)
                    p.teleport(mainLobby);
                else if (p.getWorld() != null)
                    p.teleport(p.getWorld().getSpawnLocation());
                plugin.removePlayerFromArenaMap(uuid);
            }
        }
        players.clear();
        for (UUID uuid : spectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                try {
                    if (p.getGameMode() == GameMode.SPECTATOR) {
                        p.setSpectatorTarget(null);
                    }
                } catch (Exception ignored) {
                }
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                restorePlayerInventory(p);
                if (mainLobby != null && mainLobby.getWorld() != null)
                    p.teleport(mainLobby);
                else if (p.getWorld() != null)
                    p.teleport(p.getWorld().getSpawnLocation());
                plugin.removePlayerFromArenaMap(uuid);
                p.sendMessage(plugin.getMessage("spectator-left"));
            }
        }
        spectators.clear();
        spectatorModes.clear();
        spectatorTargets.clear();
        updateLeaderboardHologram();
    }

    public void tick() {
        if (state == RaceState.LOBBY)
            return;
        if (state == RaceState.ACTIVE) {
            tickCounter++;

            if (tickCounter % 20 == 0) {
                updateNoCollisionTeam();
            }

            // Record replay frame
            if (currentReplay != null && tickCounter % 2 == 0) {
                Map<UUID, Location> locations = new HashMap<>();
                for (UUID uuid : players) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null)
                        locations.put(uuid, p.getLocation());
                }
                plugin.replayManager.recordFrame(currentReplay, tickCounter, locations,
                        new HashSet<>(finishOrder), playerCheckpoints, playerLaps);
            }

            // Ghost playback
            if (isTimeTrialMode && bestGhost != null && ghostPlaybackTick < bestGhost.points.size()) {
                Location ghostLoc = bestGhost.points.get(ghostPlaybackTick);
                if (ghostLoc != null && ghostLoc.getWorld() != null) {
                    ghostLoc.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, ghostLoc.clone().add(0, 0.5, 0), 1, 0,
                            0, 0, 0);
                    for (UUID uuid : players) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            if (!ghostEntityIds.containsKey(uuid)) {
                                UUID fakeUuid = UUID.randomUUID();
                                int id = PacketUtils.spawnFakeBoat(p, ghostLoc, fakeUuid);
                                ghostEntityIds.put(uuid, id);
                                org.bukkit.scoreboard.Team t = p.getScoreboard().getTeam("ghost");
                                if (t != null) {
                                    t.addEntry(fakeUuid.toString());
                                }
                            } else {
                                PacketUtils.moveFakeBoat(p, ghostEntityIds.get(uuid), ghostLoc);
                            }
                        }
                    }
                }
            } else if (ghostPlaybackTick >= (bestGhost != null ? bestGhost.points.size() : 0)) {
                for (UUID uuid : ghostEntityIds.keySet()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null)
                        PacketUtils.destroyFakeEntity(p, ghostEntityIds.get(uuid));
                }
                ghostEntityIds.clear();
            }
            ghostPlaybackTick++;

            List<UUID> ranking = calculateRankings();

            // Spectator camera logic
            for (UUID uuid : spectators) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline())
                    continue;

                // Ensure spectator is in SPECTATOR game mode (Multiverse world-change safety)
                if (p.getGameMode() != GameMode.SPECTATOR) {
                    p.setGameMode(GameMode.SPECTATOR);
                    continue;
                }

                SpectatorMode mode = spectatorModes.getOrDefault(uuid, SpectatorMode.FREE_FLY);
                if (mode == SpectatorMode.FOLLOW_LEADER && !ranking.isEmpty()) {
                    UUID leader = ranking.get(0);
                    Player leaderPlayer = Bukkit.getPlayer(leader);
                    if (leaderPlayer != null && leaderPlayer.isOnline() && !leaderPlayer.equals(p)) {
                        try {
                            if (p.getGameMode() == GameMode.SPECTATOR && (p.getSpectatorTarget() == null || !p.getSpectatorTarget().equals(leaderPlayer))) {
                                p.setSpectatorTarget(leaderPlayer);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } else if (mode == SpectatorMode.FOLLOW_PLAYER) {
                    UUID target = spectatorTargets.get(uuid);
                    if (target != null) {
                        Player targetPlayer = Bukkit.getPlayer(target);
                        if (targetPlayer != null && targetPlayer.isOnline() && !targetPlayer.equals(p)) {
                            try {
                                if (p.getGameMode() == GameMode.SPECTATOR && (p.getSpectatorTarget() == null || !p.getSpectatorTarget().equals(targetPlayer))) {
                                    p.setSpectatorTarget(targetPlayer);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }

            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline())
                    continue;

                if (eliminatedPlayers.contains(uuid))
                    continue;

                if (!finishOrder.contains(uuid) && playerBoats.containsKey(uuid)) {
                    Location currentLoc = p.getLocation();

                    if (currentLoc.getY() < voidY) {
                        respawnPlayer(p);
                        p.sendMessage(Component.text("§cYou fell! Respawning..."));
                        continue;
                    }

                    if (currentRecordings.containsKey(uuid)) {
                        currentRecordings.get(uuid).points.add(currentLoc);
                    }
                    Location lastLoc = lastLocations.getOrDefault(uuid, currentLoc);
                    double speedKmH = (lastLoc.getWorld() == currentLoc.getWorld())
                            ? currentLoc.distance(lastLoc) * 72.0
                            : 0;
                    lastLocations.put(uuid, currentLoc);
                    int safety = 0;
                    boolean keepChecking = true;
                    while (keepChecking && safety < 3) {
                        keepChecking = checkObjectivesAlongPath(p, uuid, lastLoc, currentLoc);
                        safety++;
                    }

                    TrailType trail = plugin.getPlayerTrailPreference(uuid);
                    Utils.spawnTrailParticles(p, playerBoats.get(uuid), trail);

                    long timeMs = System.currentTimeMillis()
                            - startTimes.getOrDefault(uuid, System.currentTimeMillis());
                    String timeStr = Utils.formatTime(timeMs);
                    int displayLap = (type == RaceType.LAP || type == RaceType.ELIMINATION)
                            ? playerLaps.getOrDefault(uuid, 1)
                            : 1;
                    int maxLap = (type == RaceType.LAP || type == RaceType.ELIMINATION) ? totalLaps : 1;
                    int cp = playerCheckpoints.getOrDefault(uuid, 0);
                    updateRaceScoreboard(p, timeStr, speedKmH, cp, checkpoints.size(), displayLap, maxLap, ranking);

                    if (tickCounter % 400 == 0) {
                        p.sendMessage(plugin.getMessage("stuck-tip"));
                    }
                    String abText = String.format("§b%.0f km/h  §7|  §aCP: %d/%d", speedKmH, cp, checkpoints.size());
                    if (type == RaceType.LAP || type == RaceType.ELIMINATION)
                        abText += String.format("  §7|  §6Lap: %d/%d", displayLap, maxLap);
                    p.sendActionBar(Component.text(abText));
                    highlightNextTarget(p, uuid);
                } else {
                    updateRaceScoreboard(p, "FINISHED", 0, 0, 0, 0, 0, ranking);
                }
            }
            for (UUID uuid : spectators) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    updateRaceScoreboard(p, "SPECTATING", 0, 0, checkpoints.size(), 0, totalLaps, ranking);
                }
            }
        }
    }

    // LOGIC HELPERS
    private boolean checkObjectivesAlongPath(Player p, UUID uuid, Location from, Location to) {
        int currentCpIndex = playerCheckpoints.getOrDefault(uuid, 0);
        Location cpTarget = null;
        boolean checkFinish = false;
        if (currentCpIndex < checkpoints.size()) {
            cpTarget = checkpoints.get(currentCpIndex);
        } else {
            checkFinish = true;
        }
        if (cpTarget != null) {
            if (Utils.lineSegmentIntersectsSphere(from, to, cpTarget, plugin.checkpointRadius)) {
                int totalCPs = checkpoints.size();
                int currentLap = playerLaps.getOrDefault(uuid, 1);
                int globalCPIndex = ((currentLap - 1) * totalCPs) + currentCpIndex;
                if (checkpointTimestamps.containsKey(uuid)) {
                    checkpointTimestamps.get(uuid).put(globalCPIndex, System.currentTimeMillis());
                }
                playerCheckpoints.put(uuid, currentCpIndex + 1);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
                return true;
            }
        }
        if (checkFinish && finishBox != null) {
            Vector start = from.toVector();
            Vector direction = to.toVector().subtract(start);
            double maxDist = direction.length();
            if (maxDist > 0.01) {
                org.bukkit.util.RayTraceResult result = finishBox.rayTrace(start, direction.normalize(), maxDist);
                if (result != null) {
                    handleFinishLineHit(p, uuid);
                    return true;
                }
            }
            if (finishBox.contains(to.toVector())) {
                handleFinishLineHit(p, uuid);
                return true;
            }
        }
        return false;
    }

    private void handleFinishLineHit(Player p, UUID uuid) {
        if (totalLaps > 1 || type == RaceType.LAP || type == RaceType.ELIMINATION) {
            int lap = playerLaps.getOrDefault(uuid, 1);
            if (lap < totalLaps) {
                playerLaps.put(uuid, lap + 1);
                playerCheckpoints.put(uuid, 0);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f);
                String msg = plugin.getRawMessage("lap-message")
                        .replace("{lap}", String.valueOf(lap + 1))
                        .replace("{total}", String.valueOf(totalLaps));
                p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));

                // ELIMINATION MODE: Check for elimination at end of each lap
                if (type == RaceType.ELIMINATION && lap > currentLapForElimination) {
                    currentLapForElimination = lap;
                    eliminateLastPlace();
                }
            } else {
                finishPlayer(p);
            }
        } else {
            finishPlayer(p);
        }
    }

    private void eliminateLastPlace() {
        List<UUID> ranking = calculateRankings();
        if (ranking.size() <= 1)
            return;

        // Find the last non-finished, non-eliminated player
        UUID lastPlace = null;
        for (int i = ranking.size() - 1; i >= 0; i--) {
            UUID uuid = ranking.get(i);
            if (!finishOrder.contains(uuid) && !eliminatedPlayers.contains(uuid)) {
                lastPlace = uuid;
                break;
            }
        }

        if (lastPlace == null)
            return;

        eliminatedPlayers.add(lastPlace);
        Player eliminated = Bukkit.getPlayer(lastPlace);
        if (eliminated != null) {
            // Remove boat and convert to spectator
            if (playerBoats.containsKey(lastPlace)) {
                Boat boat = playerBoats.remove(lastPlace);
                if (boat != null)
                    boat.remove();
            }

            eliminated.setGameMode(GameMode.SPECTATOR);
            eliminated.showTitle(Title.title(
                    Component.text("ELIMINATED", NamedTextColor.RED),
                    Component.text("You finished last this lap!", NamedTextColor.GRAY)));
            eliminated.playSound(eliminated.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.5f, 0.5f);

            Bukkit.broadcast(
                    Component.text("💀 " + eliminated.getName() + " has been eliminated!", NamedTextColor.RED));
        }

        // Check if race should end
        long remainingRacers = players.stream()
                .filter(uuid -> !finishOrder.contains(uuid) && !eliminatedPlayers.contains(uuid))
                .count();

        if (remainingRacers <= 1) {
            // Last player standing wins
            for (UUID uuid : players) {
                if (!finishOrder.contains(uuid) && !eliminatedPlayers.contains(uuid)) {
                    Player winner = Bukkit.getPlayer(uuid);
                    if (winner != null) {
                        finishPlayer(winner);
                    }
                    break;
                }
            }
        }
    }

    private void finishPlayer(Player p) {
        stopMusic(p);
        if (finishOrder.contains(p.getUniqueId()))
            return;
        finishOrder.add(p.getUniqueId());
        long timeMs = System.currentTimeMillis() - startTimes.get(p.getUniqueId());
        String timeStr = Utils.formatTime(timeMs);
        finishTimes.put(p.getUniqueId(), timeStr);
        finishTimesMs.put(p.getUniqueId(), timeMs);
        plugin.incrementStat(p.getUniqueId(), "races_played");
        boolean isWinner = finishOrder.size() == 1;
        if (isWinner) {
            plugin.incrementStat(p.getUniqueId(), "wins");
        }

        if (!bestTimes.containsKey(p.getUniqueId()) || timeMs < bestTimes.get(p.getUniqueId())) {
            bestTimes.put(p.getUniqueId(), timeMs);
            p.sendMessage(plugin.getMessage("new-pb").replaceText(b -> b.matchLiteral("{time}").replacement(timeStr)));

            // Check for server record
            if (currentRecordings.containsKey(p.getUniqueId())) {
                boolean isServerBest = true;
                for (Long t : bestTimes.values()) {
                    if (t < timeMs) {
                        isServerBest = false;
                        break;
                    }
                }
                if (isServerBest) {
                    bestGhost = currentRecordings.get(p.getUniqueId());
                    p.sendMessage(plugin.getMessage("new-server-record"));

                    // Discord notification for server record
                    if (!plugin.discordWebhookUrl.isEmpty()) {
                        plugin.discordWebhook.sendNewRecord(plugin.discordWebhookUrl, name, p.getName(), timeStr, true);
                    }
                }
            }
        }

        Component titleMain = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(plugin.getRawMessage("finished-title"));
        Component titleSub = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(plugin.getRawMessage("finished-subtitle").replace("{time}", timeStr));
        p.showTitle(Title.title(titleMain, titleSub));

        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, 1f);

        // Command Rewards
        int rank = finishOrder.size();
        if (plugin.rewardsEnabled && players.size() >= plugin.rewardsMinPlayers) {
            if (plugin.rewardCommands.containsKey(rank)) {
                List<String> commands = plugin.rewardCommands.get(rank);
                for (String cmd : commands) {
                    if (cmd == null || cmd.trim().isEmpty()) continue;
                    String finalCmd = cmd.replace("%player%", p.getName());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                    });
                }
            }
        }

        // VICTORY CELEBRATION: Spawn fireworks for winner
        if (isWinner) {
            spawnVictoryFireworks(p.getLocation());
            Bukkit.broadcast(Component.text(""));
            Bukkit.broadcast(Component.text("🏆 " + p.getName() + " WINS THE RACE! 🏆", NamedTextColor.GOLD));
            Bukkit.broadcast(Component.text(""));
        }

        Boat boat = playerBoats.remove(p.getUniqueId());
        if (boat != null)
            boat.remove();
        p.setGameMode(GameMode.SPECTATOR);
        String broadcastMsg = plugin.getRawMessage("finish-broadcast").replace("{player}", p.getName())
                .replace("{arena}", name).replace("{time}", timeStr);
        Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(broadcastMsg));
        checkFinishCondition();
    }

    private void spawnVictoryFireworks(Location loc) {
        for (int i = 0; i < 5; i++) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    Location fireworkLoc = loc.clone().add(
                            ThreadLocalRandom.current().nextDouble(-3, 3),
                            ThreadLocalRandom.current().nextDouble(0, 2),
                            ThreadLocalRandom.current().nextDouble(-3, 3));
                    Firework fw = loc.getWorld().spawn(fireworkLoc, Firework.class);
                    FireworkMeta meta = fw.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder()
                            .withColor(Color.AQUA, Color.YELLOW, Color.WHITE)
                            .withFade(Color.BLUE)
                            .with(FireworkEffect.Type.BALL_LARGE)
                            .trail(true)
                            .flicker(true)
                            .build());
                    meta.setPower(1);
                    fw.setFireworkMeta(meta);
                }
            }.runTaskLater(plugin, i * 10L);
        }
    }

    public void respawnPlayer(Player p) {
        if (state != RaceState.ACTIVE)
            return;
        int idx = playerCheckpoints.getOrDefault(p.getUniqueId(), 0);
        Location loc = (idx == 0) ? (!spawns.isEmpty() ? spawns.getFirst() : lobby) : checkpoints.get(idx - 1);
        if (loc == null)
            return;
        if (playerBoats.containsKey(p.getUniqueId()))
            playerBoats.get(p.getUniqueId()).remove();
        markTeleportGrace(p.getUniqueId());
        p.teleport(loc);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        lastLocations.put(p.getUniqueId(), loc);
        Boat boat = Utils.spawnRandomBoat(loc);
        boat.getPersistentDataContainer().set(new NamespacedKey(plugin, "race_boat"), PersistentDataType.BYTE, (byte) 1);
        boat.getPersistentDataContainer().set(new NamespacedKey(plugin, "race_arena"), PersistentDataType.STRING, name);
        boat.addPassenger(p);
        boat.setInvulnerable(true);
        playerBoats.put(p.getUniqueId(), boat);
        updateNoCollisionTeam();
        syncGhostModeForPlayer(p, boat);
    }

    private List<UUID> calculateRankings() {
        List<UUID> rankList = new ArrayList<>(players);
        rankList.removeAll(eliminatedPlayers);

        // Pre-build index lookup map for finish order (O(1) lookups during sort)
        Map<UUID, Integer> finishIndexMap = new HashMap<>(finishOrder.size());
        for (int i = 0; i < finishOrder.size(); i++) {
            finishIndexMap.put(finishOrder.get(i), i);
        }

        // Pre-calculate squared distance to current target per player (O(1) lookups during sort)
        Map<UUID, Double> distanceMap = new HashMap<>(rankList.size());
        for (UUID u : rankList) {
            if (!finishIndexMap.containsKey(u)) {
                int cp = playerCheckpoints.getOrDefault(u, 0);
                distanceMap.put(u, getDistanceToTarget(u, cp));
            }
        }

        rankList.sort((u1, u2) -> {
            Integer f1 = finishIndexMap.get(u1);
            Integer f2 = finishIndexMap.get(u2);
            if (f1 != null && f2 != null) return Integer.compare(f1, f2);
            if (f1 != null) return -1;
            if (f2 != null) return 1;

            int l1 = playerLaps.getOrDefault(u1, 1), l2 = playerLaps.getOrDefault(u2, 1);
            if (l1 != l2) return Integer.compare(l2, l1);

            int c1 = playerCheckpoints.getOrDefault(u1, 0), c2 = playerCheckpoints.getOrDefault(u2, 0);
            if (c1 != c2) return Integer.compare(c2, c1);

            double d1 = distanceMap.getOrDefault(u1, Double.MAX_VALUE);
            double d2 = distanceMap.getOrDefault(u2, Double.MAX_VALUE);
            return Double.compare(d1, d2);
        });

        return rankList;
    }

    private double getDistanceToTarget(UUID uuid, int cpIndex) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null)
            return Double.MAX_VALUE;
        Location target;
        if (cpIndex < checkpoints.size())
            target = checkpoints.get(cpIndex);
        else if (finishCenter != null)
            target = finishCenter;
        else
            target = finishPos1;
        if (target == null || target.getWorld() == null || !p.getWorld().equals(target.getWorld()))
            return Double.MAX_VALUE;

        double dx = p.getLocation().getX() - target.getX();
        double dy = p.getLocation().getY() - target.getY();
        double dz = p.getLocation().getZ() - target.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void highlightNextTarget(Player p, UUID uuid) {
        int currentCpIndex = playerCheckpoints.getOrDefault(uuid, 0);
        Location target = null;
        if (currentCpIndex < checkpoints.size())
            target = checkpoints.get(currentCpIndex);
        else if (finishCenter != null)
            target = finishCenter;
        else
            target = finishPos1;
        if (target != null && target.getWorld() != null && target.getWorld().equals(p.getWorld()))
            p.spawnParticle(Particle.HAPPY_VILLAGER, target.getX(), target.getY() + 1.5, target.getZ(), 2, 0.2, 0.2, 0.2, 0);
    }

    private void createCage(Location spawn, UUID uuid) {
        Material cageMat = plugin.getPlayerCagePreference(uuid);
        Location base = spawn.clone();
        int size = plugin.cageSize;
        if (size == 2) {
            // 4x4 outer bounds, creating an inner 2x2 air space (0 to 1 in X and Z)
            for (int x = -1; x <= 2; x++) {
                for (int z = -1; z <= 2; z++) {
                    for (int y = 0; y <= 3; y++) {
                        if (y < 3 && (x == 0 || x == 1) && (z == 0 || z == 1)) {
                            continue;
                        }
                        Location b = base.clone().add(x, y, z);
                        Block block = b.getBlock();
                        if (block.isEmpty() || block.isPassable()) {
                            block.setType(cageMat);
                            glassBlocks.add(b);
                        }
                    }
                }
            }
        } else {
            // Default 5x5 outer bounds, creating an inner 3x3 air space (-1 to +1 in X and Z)
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = 0; y <= 3; y++) {
                        // 3x3 horizontal air space for y = 0, 1, 2 (giving ample room for boat and driver)
                        if (y < 3 && x >= -1 && x <= 1 && z >= -1 && z <= 1) {
                            continue;
                        }
                        Location b = base.clone().add(x, y, z);
                        Block block = b.getBlock();
                        if (block.isEmpty() || block.isPassable()) {
                            block.setType(cageMat);
                            glassBlocks.add(b);
                        }
                    }
                }
            }
        }
    }

    private void removeCages() {
        for (Location l : glassBlocks) {
            if (l.getWorld() != null) {
                l.getBlock().setType(Material.AIR);
            }
        }
        glassBlocks.clear();
    }

    public void savePlayerInventory(Player p) {
        if (p == null) return;
        UUID uuid = p.getUniqueId();
        if (savedInventories.containsKey(uuid)) return;

        savedInventories.put(uuid, cloneItemArray(p.getInventory().getContents()));
        savedArmor.put(uuid, cloneItemArray(p.getInventory().getArmorContents()));
        ItemStack offHand = p.getInventory().getItemInOffHand();
        savedOffHand.put(uuid, (offHand != null && offHand.getType() != Material.AIR) ? offHand.clone() : null);
        savedLevels.put(uuid, p.getLevel());
        savedExp.put(uuid, p.getExp());
        savedGameModes.put(uuid, p.getGameMode());
    }

    public void restorePlayerInventory(Player p) {
        if (p == null) return;
        UUID uuid = p.getUniqueId();
        if (!savedInventories.containsKey(uuid)) {
            removeRaceItems(p);
            return;
        }

        p.getInventory().clear();
        ItemStack[] contents = savedInventories.remove(uuid);
        ItemStack[] armor = savedArmor.remove(uuid);
        ItemStack offHand = savedOffHand.remove(uuid);
        Integer level = savedLevels.remove(uuid);
        Float exp = savedExp.remove(uuid);
        GameMode gm = savedGameModes.remove(uuid);

        applyInventory(p, contents, armor, offHand, level, exp, gm);

        // Deferred application fallback in case Multiverse-Inventories resets inventory on world teleport
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                applyInventory(p, contents, armor, offHand, level, exp, gm);
            }
        }, 2L);
    }

    private void applyInventory(Player p, ItemStack[] contents, ItemStack[] armor, ItemStack offHand, Integer level, Float exp, GameMode gm) {
        if (p == null || !p.isOnline()) return;
        if (contents != null) p.getInventory().setContents(cloneItemArray(contents));
        if (armor != null) p.getInventory().setArmorContents(cloneItemArray(armor));
        if (offHand != null) p.getInventory().setItemInOffHand(offHand.clone());
        if (level != null) p.setLevel(level);
        if (exp != null) p.setExp(exp);
        if (gm != null) p.setGameMode(gm);
        p.updateInventory();
    }

    private void removeRaceItems(Player p) {
        if (p == null || !p.isOnline()) return;
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;
            if (item.getType() == Material.COMPASS || item.getType() == Material.RED_DYE || item.getType() == Material.BARRIER || item.getType() == Material.CLOCK) {
                if (item.hasItemMeta()) {
                    var meta = item.getItemMeta();
                    if (meta.getPersistentDataContainer().has(new NamespacedKey(plugin, "race_menu"), PersistentDataType.BYTE)) {
                        p.getInventory().setItem(i, null);
                    } else if (meta.displayName() != null) {
                        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(meta.displayName());
                        if (plain.contains("Race Menu") || plain.contains("Reset Run") || plain.contains("Spectator") || plain.contains("Leave")) {
                            p.getInventory().setItem(i, null);
                        }
                    }
                }
            }
        }
    }

    private ItemStack[] cloneItemArray(ItemStack[] original) {
        if (original == null) return new ItemStack[0];
        ItemStack[] copy = new ItemStack[original.length];
        for (int i = 0; i < original.length; i++) {
            copy[i] = (original[i] != null && original[i].getType() != Material.AIR) ? original[i].clone() : null;
        }
        return copy;
    }

    private void syncGhostMode() {
        for (UUID uuid : playerBoats.keySet()) {
            Boat b = playerBoats.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && b != null)
                syncGhostModeForPlayer(p, b);
        }
    }

    private void syncGhostModeForPlayer(Player p, Boat b) {
        if (p == null) return;

        // Ensure collision-free scoreboard team membership
        for (UUID otherUUID : players) {
            Player otherP = Bukkit.getPlayer(otherUUID);
            if (otherP != null) {
                Team t = otherP.getScoreboard().getTeam("ibr_racers");
                if (t == null) {
                    t = otherP.getScoreboard().getTeam("ghost");
                }
                if (t != null && !t.hasEntry(p.getName())) {
                    t.addEntry(p.getName());
                }
            }
        }

        boolean ghostMode = "GHOST".equalsIgnoreCase(plugin.collisionMode);

        for (UUID otherUUID : players) {
            if (otherUUID.equals(p.getUniqueId())) continue;
            Player otherP = Bukkit.getPlayer(otherUUID);
            if (otherP == null || !otherP.isOnline()) continue;
            Boat otherBoat = playerBoats.get(otherUUID);

            if (ghostMode) {
                // Ghost mode explicitly hides opponent racers and boats (e.g. for solo time-trial qualifying)
                otherP.hideEntity(plugin, p);
                if (b != null && b.isValid()) otherP.hideEntity(plugin, b);
                p.hideEntity(plugin, otherP);
                if (otherBoat != null && otherBoat.isValid()) p.hideEntity(plugin, otherBoat);
            } else {
                // Default: ALL PLAYERS AND BOATS ARE 100% VISIBLE IN THE RACE!
                otherP.showEntity(plugin, p);
                if (b != null && b.isValid()) otherP.showEntity(plugin, b);
                p.showEntity(plugin, otherP);
                if (otherBoat != null && otherBoat.isValid()) p.showEntity(plugin, otherBoat);

                // Send OpenBoatUtils packet 27 to modded clients for native client-side no-collision
                if (plugin.hasOpenBoatUtils(p.getUniqueId())) plugin.sendOpenBoatUtilsNocol(p, true);
                if (plugin.hasOpenBoatUtils(otherUUID)) plugin.sendOpenBoatUtilsNocol(otherP, true);
            }
        }

        // Spectators always see all players and all boats
        for (UUID specUUID : spectators) {
            Player spec = Bukkit.getPlayer(specUUID);
            if (spec != null && spec.isOnline()) {
                spec.showEntity(plugin, p);
                if (b != null && b.isValid()) spec.showEntity(plugin, b);
            }
        }
    }

    public void restoreAllVisibility() {
        Set<UUID> all = new HashSet<>(players);
        all.addAll(spectators);
        for (UUID u1 : all) {
            Player p1 = Bukkit.getPlayer(u1);
            if (p1 == null || !p1.isOnline()) continue;
            for (UUID u2 : all) {
                if (u1.equals(u2)) continue;
                Player p2 = Bukkit.getPlayer(u2);
                if (p2 != null && p2.isOnline()) {
                    p1.showEntity(plugin, p2);
                }
                Boat b2 = playerBoats.get(u2);
                if (b2 != null && b2.isValid()) {
                    p1.showEntity(plugin, b2);
                }
            }
            if (plugin.hasOpenBoatUtils(u1)) {
                plugin.sendOpenBoatUtilsNocol(p1, false);
            }
        }
    }

    public void startMusic() {
        if (!plugin.musicEnabled)
            return;
        if (musicTask != null && !musicTask.isCancelled())
            musicTask.cancel();
        musicTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != RaceState.ACTIVE && state != RaceState.STARTING) {
                    this.cancel();
                    return;
                }
                for (UUID uuid : players) {
                    if (!finishOrder.contains(uuid)) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline())
                            p.playSound(p.getLocation(), plugin.musicSound, SoundCategory.MASTER, plugin.musicVolume,
                                    plugin.musicPitch);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, plugin.musicDuration * 20L);
    }

    public void stopMusic(Player p) {
        if (p != null)
            p.stopSound(plugin.musicSound, SoundCategory.MASTER);
    }

    public void stopAllMusic() {
        if (musicTask != null) {
            musicTask.cancel();
            musicTask = null;
        }
        for (UUID uuid : players)
            stopMusic(Bukkit.getPlayer(uuid));
    }

    private void checkAutoStart() {
        if (state != RaceState.LOBBY)
            return;
        if (players.size() >= minPlayers) {
            if (autoStartTask == null) {
                lobbyCountdown = autoStartDelay;
                autoStartTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (state != RaceState.LOBBY || players.size() < minPlayers) {
                            cancelAutoStart();
                            return;
                        }
                        if (lobbyCountdown <= 0) {
                            startRace();
                            cancel();
                            return;
                        }
                        if (lobbyCountdown == 60 || lobbyCountdown == 30 || lobbyCountdown == 10
                                || lobbyCountdown <= 5) {
                            for (UUID uuid : players) {
                                Player p = Bukkit.getPlayer(uuid);
                                if (p != null) {
                                    p.sendMessage(plugin.getMessage("race-starting").replaceText(
                                            b -> b.matchLiteral("{time}").replacement(String.valueOf(lobbyCountdown))));
                                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                                }
                            }
                        }
                        updateLobbyScoreboard();
                        lobbyCountdown--;
                    }
                }.runTaskTimer(plugin, 0L, 20L);
            }
        } else {
            cancelAutoStart();
        }
    }

    private void cancelAutoStart() {
        if (autoStartTask != null) {
            autoStartTask.cancel();
            autoStartTask = null;
            lobbyCountdown = -1;
            updateLobbyScoreboard();
        }
    }

    private void updateLobbyScoreboard() {
        if (state != RaceState.LOBBY)
            return;
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null)
                setupLobbyScoreboard(p);
        }
    }

    private void setupLobbyScoreboard(Player p) {
        ScoreboardManager m = Bukkit.getScoreboardManager();
        Scoreboard b = m.getNewScoreboard();
        Objective o = b.registerNewObjective("Lobby", Criteria.DUMMY, Component.text("§b§lICE BOAT RACING"));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        try {
            o.numberFormat(NumberFormat.blank());
        } catch (Throwable ignored) {
        }
        Team statusTeam = b.registerNewTeam("status");
        String statusTxt = (autoStartTask != null && lobbyCountdown >= 0) ? "§eStart: " + lobbyCountdown + "s"
                : "§fWaiting...";
        statusTeam.addEntry("§7");
        statusTeam.suffix(Component.text(statusTxt));
        o.getScore("§7--------------------").setScore(6);
        o.getScore("§eArena: §f" + name).setScore(5);
        o.getScore("§ePlayers: §f" + players.size() + "/" + minPlayers).setScore(4);
        o.getScore("§eStatus: ").setScore(3);
        o.getScore("§7").setScore(2);
        o.getScore("§7-------------------- ").setScore(1);
        p.setScoreboard(b);
    }

    private void setupRaceScoreboard(Player p) {
        ScoreboardManager m = Bukkit.getScoreboardManager();
        Scoreboard b = m.getNewScoreboard();
        Objective o = b.registerNewObjective("IceRace", Criteria.DUMMY, Component.text("§b§lICE BOAT RACING"));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        try {
            o.numberFormat(NumberFormat.blank());
        } catch (Throwable ignored) {
        }
        Team ghost = b.registerNewTeam("ghost");
        ghost.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        Team racers = b.registerNewTeam("ibr_racers");
        racers.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        Utils.createTeam(b, "stats", "§fTime: 00:00");
        o.getScore("§7--------------------").setScore(15);
        o.getScore("§eStats:").setScore(14);
        o.getScore("§f").setScore(13);
        o.getScore(" ").setScore(12);
        o.getScore("§e§lSTANDINGS").setScore(11);
        String[] rankKeys = { "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a" };
        for (int i = 0; i < 10; i++) {
            Utils.createTeam(b, "rank_" + (i + 1), "");
            o.getScore(rankKeys[i]).setScore(10 - i);
            Team t = b.getTeam("rank_" + (i + 1));
            if (t != null)
                t.addEntry(rankKeys[i]);
        }
        Team stats = b.getTeam("stats");
        if (stats != null)
            stats.addEntry("§f");
        p.setScoreboard(b);
    }

    private void updateRaceScoreboard(Player p, String time, double speed, int cp, int totalCps, int lap, int maxLaps,
            List<UUID> ranking) {
        Scoreboard b = p.getScoreboard();
        Team stats = b.getTeam("stats");
        if (stats != null) {
            String statText;
            if (time.equals("SPECTATING")) {
                statText = "§bSPECTATING";
            } else {
                statText = String.format("§f%s §7| §b%.0f km/h §7| §aCP: %d/%d", time, speed, cp, totalCps);
                if (type == RaceType.LAP || type == RaceType.ELIMINATION)
                    statText += String.format(" §7| §6L%d/%d", lap, maxLaps);
            }
            stats.suffix(Component.text(statText));
        }
        UUID leaderUUID = (!ranking.isEmpty()) ? ranking.get(0) : null;
        for (int i = 0; i < 10; i++) {
            Team t = b.getTeam("rank_" + (i + 1));
            if (t != null) {
                if (i < ranking.size()) {
                    UUID uuid = ranking.get(i);
                    Player rp = Bukkit.getPlayer(uuid);
                    String pName = (rp != null) ? rp.getName() : "Unknown";
                    int pLap = playerLaps.getOrDefault(uuid, 1);
                    String gapStr;
                    if (i == 0) {
                        gapStr = "§e1st";
                    } else {
                        long gap = calculateGapToLeader(uuid, leaderUUID);
                        if (gap == 0)
                            gapStr = "§7-.-";
                        else if (gap > 0)
                            gapStr = "§c+" + String.format("%.1f", gap / 1000.0) + "s";
                        else
                            gapStr = "§a" + String.format("%.1f", gap / 1000.0) + "s";
                    }
                    String entry;
                    if (uuid.equals(p.getUniqueId())) {
                        entry = String.format("§f%s §8// §aYou §eL%d", time, pLap);
                    } else {
                        entry = String.format("%s §8// §f%s §7L%d", gapStr, pName, pLap);
                    }
                    if (finishOrder.contains(uuid))
                        entry = "§a✔ " + pName + " §7(Finished)";
                    if (eliminatedPlayers.contains(uuid))
                        entry = "§c✘ " + pName + " §7(Eliminated)";
                    t.suffix(Component.text(entry));
                } else {
                    t.suffix(Component.text("§7---"));
                }
            }
        }
    }

    private long calculateGapToLeader(UUID playerUUID, UUID leaderUUID) {
        if (playerUUID == null || leaderUUID == null)
            return 0;
        if (playerUUID.equals(leaderUUID))
            return 0;
        int pLap = playerLaps.getOrDefault(playerUUID, 1);
        int pCp = playerCheckpoints.getOrDefault(playerUUID, 0);
        int totalCPs = checkpoints.size();
        int globalIndex = ((pLap - 1) * totalCPs) + pCp - 1;
        if (globalIndex < 0)
            return 0;
        Map<Integer, Long> pTimes = checkpointTimestamps.get(playerUUID);
        Map<Integer, Long> lTimes = checkpointTimestamps.get(leaderUUID);
        if (pTimes == null || lTimes == null)
            return 0;
        if (!pTimes.containsKey(globalIndex) || !lTimes.containsKey(globalIndex))
            return 0;
        return pTimes.get(globalIndex) - lTimes.get(globalIndex);
    }

    public boolean isSetupComplete() {
        return !spawns.isEmpty() && !checkpoints.isEmpty() && finishBox != null && lobby != null;
    }

    public List<String> getSetupStatus() {
        List<String> status = new ArrayList<>();
        status.add(spawns.isEmpty() ? "&c✘ Spawns: None set" : "&a✔ Spawns: &f" + spawns.size() + " set");
        status.add(checkpoints.isEmpty() ? "&c✘ Checkpoints: None set" : "&a✔ Checkpoints: &f" + checkpoints.size() + " set");
        status.add(finishBox == null ? "&c✘ Finish Line: Not set" : "&a✔ Finish Line: &fConfigured");
        status.add(lobby == null ? "&c✘ Pre-Race Lobby: Not set" : "&a✔ Pre-Race Lobby: &fConfigured");
        status.add(mainLobby == null ? "&e! Main Lobby: &fWorld spawn (default)" : "&a✔ Main Lobby: &fConfigured");
        status.add(leaderboardLocation == null ? "&e! Leaderboard: &fNot set" : "&a✔ Leaderboard: &fConfigured");
        return status;
    }
}
