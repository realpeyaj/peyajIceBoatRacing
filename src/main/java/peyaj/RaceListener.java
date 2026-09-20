package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleBlockCollisionEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import peyaj.arena.RaceState;
import peyaj.cosmetics.EditMode;

public class RaceListener implements Listener {

    private final IceBoatRacing plugin;

    public RaceListener(IceBoatRacing plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))
            return;

        // Cancel all inventory clicks for racers to prevent moving lobby/spectator
        // items
        if (plugin.isRacer(p.getUniqueId())) {
            e.setCancelled(true);
        }

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getItemMeta() == null)
            return;

        if (clicked.getType() == Material.COMPASS) {
            boolean isRaceMenu = false;
            if (clicked.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "race_menu"), PersistentDataType.BYTE)) {
                isRaceMenu = true;
            } else if (clicked.getItemMeta().displayName() != null) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(clicked.getItemMeta().displayName());
                if (plain.contains("Race Menu")) {
                    isRaceMenu = true;
                }
            }
            if (isRaceMenu) {
                e.setCancelled(true);
                p.closeInventory();
                plugin.guiManager.openMainMenu(p);
                return;
            }
        }

        if (clicked.getType() == Material.RED_DYE) {
            String displayName = clicked.getItemMeta().displayName() != null
                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(clicked.getItemMeta().displayName())
                    : "";
            if (displayName.contains("Reset Run")) {
                RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
                if (arena != null && arena.isTimeTrial()) {
                    arena.resetTimeTrial(p);
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
            if (arena != null && arena.getState() == RaceState.ACTIVE) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onFoodLevelChange(org.bukkit.event.entity.FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) {
            RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
            if (arena != null && arena.getState() != RaceState.LOBBY) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (plugin.isRacer(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        if (plugin.isRacer(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (plugin.isRacer(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (plugin.isRacer(p.getUniqueId())) {
            String cmd = e.getMessage().toLowerCase();
            if (!cmd.startsWith("/race") && !cmd.startsWith("/iceboat") && !cmd.startsWith("/checkpoint")
                    && !cmd.startsWith("/cp") && !cmd.startsWith("/stuck")) {
                if (!p.hasPermission("race.admin")) {
                    e.setCancelled(true);
                    p.sendMessage(Component.text("You cannot use other commands while in a race!", NamedTextColor.RED));
                }
            }
        }
    }

    @EventHandler
    public void onPlayerTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (plugin.isRacer(e.getPlayer().getUniqueId())) {
            if (e.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL ||
                    e.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!plugin.inputMode.containsKey(p.getUniqueId()))
            return;

        String mode = plugin.inputMode.remove(p.getUniqueId());
        e.setCancelled(true);
        String input = e.getMessage();

        if (mode.equals("create_arena")) {
            String name = input.replace(" ", "_");
            if (name.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> p.sendMessage(Component.text("Arena name cannot be empty!", NamedTextColor.RED)));
                return;
            }
            if (plugin.getArena(name) != null) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> p.sendMessage(Component.text("Arena already exists!", NamedTextColor.RED)));
                return;
            }

            RaceArena arena = new RaceArena(name, plugin);
            plugin.addArena(name, arena);
            plugin.editorArena.put(p.getUniqueId(), name);
            plugin.editorMode.put(p.getUniqueId(), EditMode.SPAWN);

            Bukkit.getScheduler().runTask(plugin, () -> {
                p.sendMessage(Component.text("Arena '" + name + "' created! Use the Race Wand to set it up.",
                        NamedTextColor.GREEN));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
            });
        }
    }

    // Replaced isIce with direct teleports

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBoatMove(VehicleMoveEvent e) {
        if (!(e.getVehicle() instanceof Boat boat))
            return;
        if (boat.getPassengers().isEmpty() || !(boat.getPassengers().get(0) instanceof Player p))
            return;

        RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
        if (arena != null && arena.getState() == RaceState.STARTING) {
            boat.setVelocity(new Vector(0, 0, 0));
            Location from = e.getFrom();
            Location to = e.getTo();
            if (from.getX() != to.getX() || from.getZ() != to.getZ() || from.getY() != to.getY()) {
                boat.teleport(from);
            }
            return;
        }

        // Ensure step height is set for Paper native smooth stepping
        try {
            boat.getClass().getMethod("setStepHeight", float.class).invoke(boat, 1.25f);
        } catch (Throwable ignored) {
        }

        Location from = e.getFrom();
        Location to = e.getTo();

        Vector velocity = boat.getVelocity();
        double speed = velocity.clone().setY(0).length();

        double moveDist = from.distanceSquared(to);
        if (speed < 0.02 && moveDist < 0.0001)
            return;

        Vector direction = boat.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 0.0001)
            return;
        direction.normalize();

        Location boatLoc = boat.getLocation();

        // Check 1 to 2 ticks ahead for step-ups (staircase gliding)
        double[] offsets = { 0.6, 1.2, 1.2 + speed * 0.8 };
        boolean isClimbing = false;

        for (double offset : offsets) {
            Location check = boatLoc.clone().add(direction.clone().multiply(offset));
            Block wallBlock = check.getBlock();

            if (!wallBlock.getType().isSolid())
                continue;

            // Restrict smooth staircase climbing exclusively to ICE block types
            if (!Utils.isIceBlock(wallBlock.getType()))
                continue;

            Block aboveWall = wallBlock.getRelative(org.bukkit.block.BlockFace.UP);
            Block twoAboveWall = aboveWall.getRelative(org.bukkit.block.BlockFace.UP);

            if (!aboveWall.getType().isSolid() && !twoAboveWall.getType().isSolid()) {
                double diffY = (wallBlock.getY() + 1.0) - boatLoc.getY();

                // Smooth staircase climb (up to 1.25 blocks per step)
                if (diffY > 0.05 && diffY <= 1.25) {
                    double climbY = Math.min(0.25, diffY * 0.2 + 0.1);
                    double forwardSpeed = Math.max(speed, 0.35);

                    boat.setVelocity(direction.clone().multiply(forwardSpeed).setY(climbY));
                    isClimbing = true;
                    break;
                }
            }
        }

        // Stick smoothly to block top after climb (staircase effect)
        if (!isClimbing && velocity.getY() > 0.05) {
            Block below = boatLoc.clone().subtract(0, 0.1, 0).getBlock();
            if (below.getType().isSolid() && Utils.isIceBlock(below.getType())) {
                boat.setVelocity(direction.clone().multiply(Math.max(speed, 0.3)).setY(0));
            }
        }
    }

    @EventHandler
    public void onBoatCollision(VehicleBlockCollisionEvent e) {
        if (!(e.getVehicle() instanceof Boat boat))
            return;
        if (boat.getPassengers().isEmpty() || !(boat.getPassengers().get(0) instanceof Player))
            return;

        Block block = e.getBlock();
        // Restrict collision climbing strictly to ICE block types
        if (!Utils.isIceBlock(block.getType()))
            return;

        Vector toBlock = block.getLocation().add(0.5, 0, 0.5).toVector()
                .subtract(boat.getLocation().toVector()).setY(0);
        if (toBlock.lengthSquared() < 0.0001)
            return;
        Vector direction = toBlock.normalize();

        Block aboveBlock = block.getRelative(org.bukkit.block.BlockFace.UP);
        Block twoAbove = aboveBlock.getRelative(org.bukkit.block.BlockFace.UP);

        if (!aboveBlock.getType().isSolid() && !twoAbove.getType().isSolid()) {
            double diffY = (block.getY() + 1.0) - boat.getLocation().getY();

            if (diffY > 0 && diffY <= 1.25) {
                Vector vel = boat.getVelocity();
                double horizontalSpeed = Math.max(0.45, vel.clone().setY(0).length());
                boat.setVelocity(direction.clone().multiply(horizontalSpeed).setY(0.2));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleEntityCollision(VehicleEntityCollisionEvent e) {
        Vehicle vehicle = e.getVehicle();
        org.bukkit.entity.Entity entity = e.getEntity();

        boolean isRaceCollision = false;

        if (vehicle instanceof Boat b && plugin.isRaceBoat(b)) {
            isRaceCollision = true;
        } else if (entity instanceof Boat b && plugin.isRaceBoat(b)) {
            isRaceCollision = true;
        } else if (vehicle.getPassengers().stream().anyMatch(p -> p instanceof Player pl && plugin.isRacer(pl.getUniqueId()))) {
            isRaceCollision = true;
        } else if (entity instanceof Player pl && plugin.isRacer(pl.getUniqueId())) {
            isRaceCollision = true;
        }

        if (isRaceCollision) {
            e.setCancelled(true);
            e.setCollisionCancelled(true);
            e.setPickupCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (e.getVehicle() instanceof Boat b && plugin.isRaceBoat(b)) {
            org.bukkit.entity.Entity entered = e.getEntered();
            // Disallow entering if boat already has a driver, or if the entity is not an active racer
            if (!b.getPassengers().isEmpty()) {
                e.setCancelled(true);
            } else if (entered instanceof Player p) {
                if (!plugin.isRacer(p.getUniqueId())) {
                    e.setCancelled(true);
                }
            } else {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p))
            return;
        if (!(e.getDismounted() instanceof Boat))
            return;

        RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
        if (arena == null)
            return;
        if ((arena.getState() == RaceState.ACTIVE || arena.getState() == RaceState.STARTING) && !arena.isSpectator(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onVehicleDamage(org.bukkit.event.vehicle.VehicleDamageEvent e) {
        if (e.getVehicle() instanceof Boat boat && !boat.getPassengers().isEmpty()) {
            if (boat.getPassengers().get(0) instanceof Player p) {
                if (plugin.isRacer(p.getUniqueId())) {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onVehicleDestroy(org.bukkit.event.vehicle.VehicleDestroyEvent e) {
        if (e.getVehicle() instanceof Boat boat && !boat.getPassengers().isEmpty()) {
            if (boat.getPassengers().get(0) instanceof Player p) {
                if (plugin.isRacer(p.getUniqueId())) {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
        if (arena != null) {
            arena.removePlayer(p);
        }
        plugin.editorArena.remove(p.getUniqueId());
        plugin.editorMode.remove(p.getUniqueId());
        plugin.activeVisualizers.remove(p.getUniqueId());
        plugin.inputMode.remove(p.getUniqueId());
        plugin.openBoatUtilsPlayers.remove(p.getUniqueId());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();

        if (item == null || item.getItemMeta() == null)
            return;

        // Check for race wand
        if (item.getItemMeta().getPersistentDataContainer().has(plugin.guiManager.raceWandKey,
                PersistentDataType.BYTE)) {
            e.setCancelled(true);
            handleWand(p, e.getAction(), e.getClickedBlock());
            return;
        }

        // Check for race menu compass
        if (item.getType() == Material.COMPASS && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            boolean isRaceMenu = false;
            if (item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "race_menu"), PersistentDataType.BYTE)) {
                isRaceMenu = true;
            } else if (item.getItemMeta().displayName() != null) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(item.getItemMeta().displayName());
                if (plain.contains("Race Menu")) {
                    isRaceMenu = true;
                }
            }
            if (isRaceMenu) {
                e.setCancelled(true);
                plugin.guiManager.openMainMenu(p);
                return;
            }
        }

        // Check for reset run item
        if (item.getType() == Material.RED_DYE) {
            String displayName = item.getItemMeta().displayName() != null
                    ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(item.getItemMeta().displayName())
                    : "";
            if (displayName.contains("Reset Run")) {
                RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
                if (arena != null && arena.isTimeTrial()) {
                    e.setCancelled(true);
                    arena.resetTimeTrial(p);
                }
            }
        }

        // Spectator items
        if (plugin.isRacer(p.getUniqueId())) {
            RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
            if (arena != null && arena.isSpectator(p.getUniqueId())) {
                e.setCancelled(true);
                if (item.getType() == Material.CLOCK && e.getAction().name().contains("RIGHT")) {
                    arena.cycleSpectatorMode(p);
                } else if (item.getType() == Material.BARRIER && e.getAction().name().contains("RIGHT")) {
                    arena.removePlayer(p);
                }
            }
        }
    }

    private void handleWand(Player p, Action action, Block clickedBlock) {
        String arenaName = plugin.editorArena.get(p.getUniqueId());
        EditMode mode = plugin.editorMode.get(p.getUniqueId());

        if (arenaName == null || mode == null) {
            p.sendMessage(Component.text("Select an arena to edit from the Admin Panel first!", NamedTextColor.RED));
            return;
        }

        RaceArena arena = plugin.getArena(arenaName);
        if (arena == null) {
            p.sendMessage(Component.text("Arena '" + arenaName + "' not found.", NamedTextColor.RED));
            return;
        }

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (p.isSneaking() && clickedBlock != null) {
                // Shift+Right-Click: Remove point
                handleRemovePoint(p, arena, mode, clickedBlock);
            } else {
                // Right-Click: Cycle mode
                EditMode next = mode.next();
                plugin.editorMode.put(p.getUniqueId(), next);
                p.sendMessage(Component.text("Wand mode: " + next.displayName, next.color));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            }
            return;
        }

        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            // Left-Click: Add point
            Location loc = clickedBlock != null ? clickedBlock.getLocation().add(0.5, 1, 0.5) : p.getLocation();

            switch (mode) {
                case SPAWN -> {
                    arena.addSpawn(loc);
                    p.sendMessage(Component.text("Added Spawn #" + arena.getSpawns().size(), NamedTextColor.GREEN));
                }
                case CHECKPOINT -> {
                    arena.addCheckpoint(loc);
                    p.sendMessage(
                            Component.text("Added Checkpoint #" + arena.getCheckpoints().size(), NamedTextColor.RED));
                }
                case FINISH_1 -> {
                    arena.setFinishLine(loc, arena.getFinishPos2());
                    p.sendMessage(Component.text("Set Finish Position 1", NamedTextColor.AQUA));
                }
                case FINISH_2 -> {
                    arena.setFinishLine(arena.getFinishPos1(), loc);
                    p.sendMessage(Component.text("Set Finish Position 2", NamedTextColor.AQUA));
                }
                case LOBBY -> {
                    arena.setLobby(loc);
                    p.sendMessage(Component.text("Set Pre-Race Lobby", NamedTextColor.GOLD));
                }
                case MAIN_LOBBY -> {
                    arena.setMainLobby(loc);
                    p.sendMessage(Component.text("Set Main Lobby (Post-Race)", NamedTextColor.YELLOW));
                }
                case LEADERBOARD -> {
                    arena.setLeaderboardLocation(loc);
                    p.sendMessage(Component.text("Set Leaderboard Hologram Position", NamedTextColor.LIGHT_PURPLE));
                }
            }
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
            plugin.saveArenas();
        }
    }

    private void handleRemovePoint(Player p, RaceArena arena, EditMode mode, Block clickedBlock) {
        Location loc = clickedBlock.getLocation();
        boolean removed = false;

        switch (mode) {
            case SPAWN -> removed = arena.removeNodeAtBlock(arena.getSpawns(), loc);
            case CHECKPOINT -> removed = arena.removeNodeAtBlock(arena.getCheckpoints(), loc);
            default -> p.sendMessage(Component.text("Can only remove spawns and checkpoints!", NamedTextColor.RED));
        }

        if (removed) {
            p.sendMessage(Component.text("Removed point!", NamedTextColor.YELLOW));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            plugin.saveArenas();
        } else {
            p.sendMessage(Component.text("No point found at that location.", NamedTextColor.GRAY));
        }
    }
}