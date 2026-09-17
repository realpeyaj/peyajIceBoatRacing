package peyaj.hologram;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import peyaj.IceBoatRacing;
import peyaj.RaceArena;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HologramManager implements Listener {

    private final IceBoatRacing plugin;
    private final NamespacedKey hologramKey;
    private final Map<String, Hologram> holograms = new ConcurrentHashMap<>();

    public HologramManager(IceBoatRacing plugin) {
        this.plugin = plugin;
        this.hologramKey = new NamespacedKey(plugin, "hologram_id");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public Hologram getHologram(String id) {
        if (id == null) return null;
        return holograms.get(id.toLowerCase());
    }

    public Hologram createOrUpdateHologram(String id, Location location, List<String> lines) {
        if (id == null || location == null) return null;
        String key = id.toLowerCase();
        Hologram holo = holograms.computeIfAbsent(key, k -> new Hologram(plugin, id, location));
        holo.setLocation(location);
        holo.setLines(lines);
        holo.ensureSpawned();
        return holo;
    }

    public Hologram moveOrUpdateHologram(String id, Location oldLoc, Location newLoc, List<String> lines) {
        if (id == null || newLoc == null) return null;
        String key = id.toLowerCase();
        if (oldLoc != null && !oldLoc.equals(newLoc)) {
            cleanUpHologramAt(id, oldLoc);
        }
        Hologram holo = holograms.computeIfAbsent(key, k -> new Hologram(plugin, id, newLoc));
        holo.setLocation(newLoc);
        holo.setLines(lines);
        holo.ensureSpawned();
        return holo;
    }

    public void removeHologram(String id) {
        if (id == null) return;
        Hologram holo = holograms.remove(id.toLowerCase());
        if (holo != null) {
            holo.remove();
        }
    }

    public void cleanUpHologramAt(String id, Location loc) {
        if (id == null || loc == null || loc.getWorld() == null) return;
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
        Hologram holo = getHologram(id);
        java.util.UUID activeUuid = (holo != null) ? holo.getTextDisplayUuid() : null;
        try {
            for (Entity nearby : loc.getWorld().getNearbyEntities(loc, 6.0, 6.0, 6.0)) {
                if (nearby instanceof TextDisplay td) {
                    String taggedId = td.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
                    if (id.equalsIgnoreCase(taggedId)) {
                        // Protect active display entity from being deleted
                        if (activeUuid != null && td.getUniqueId().equals(activeUuid)) {
                            continue;
                        }
                        td.remove();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void removeAll() {
        for (Hologram holo : holograms.values()) {
            holo.remove();
        }
        holograms.clear();
    }

    public int purgeAllOrphanedHolograms() {
        int purged = 0;
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay td : world.getEntitiesByClass(TextDisplay.class)) {
                String taggedId = td.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
                if (taggedId != null && taggedId.toLowerCase().startsWith("race_lb_")) {
                    String arenaName = taggedId.substring("race_lb_".length());
                    RaceArena arena = plugin.getArena(arenaName);
                    if (arena == null || arena.getLeaderboardLocation() == null) {
                        td.remove();
                        purged++;
                        continue;
                    }
                    Location lbLoc = arena.getLeaderboardLocation();
                    if (!td.getWorld().equals(lbLoc.getWorld()) || td.getLocation().distanceSquared(lbLoc) > 4.0) {
                        td.remove();
                        purged++;
                        continue;
                    }
                    // At current location: check if this is an active non-persistent display or duplicate
                    Hologram holo = holograms.get(taggedId.toLowerCase());
                    if (holo != null && holo.getDisplayEntity() != null) {
                        if (!holo.getDisplayEntity().getUniqueId().equals(td.getUniqueId())) {
                            td.remove();
                            purged++;
                        }
                    } else if (td.isPersistent()) {
                        // Legacy persistent entity from v3.2.1: purge it so clean non-persistent display replaces it
                        td.remove();
                        purged++;
                    }
                }
            }
        }
        if (purged > 0) {
            plugin.getLogger().info("Purged " + purged + " duplicate or orphaned leaderboard hologram(s).");
        }
        return purged;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (Entity entity : event.getEntities()) {
            if (entity instanceof TextDisplay td) {
                String taggedId = td.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
                if (taggedId != null && taggedId.toLowerCase().startsWith("race_lb_")) {
                    Hologram holo = getHologram(taggedId);
                    if (holo != null && holo.getTextDisplayUuid() != null && td.getUniqueId().equals(holo.getTextDisplayUuid())) {
                        continue; // Protect active in-memory hologram
                    }
                    if (td.isPersistent()) {
                        td.remove();
                    }
                }
            }
        }
        for (RaceArena arena : plugin.getArenas().values()) {
            Location lbLoc = arena.getLeaderboardLocation();
            if (lbLoc != null && lbLoc.getWorld() != null
                    && lbLoc.getWorld().equals(chunk.getWorld())
                    && (lbLoc.getBlockX() >> 4) == chunk.getX()
                    && (lbLoc.getBlockZ() >> 4) == chunk.getZ()) {
                arena.updateLeaderboardHologram();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (RaceArena arena : plugin.getArenas().values()) {
            Location lbLoc = arena.getLeaderboardLocation();
            if (lbLoc != null && lbLoc.getWorld() != null
                    && lbLoc.getWorld().equals(chunk.getWorld())
                    && (lbLoc.getBlockX() >> 4) == chunk.getX()
                    && (lbLoc.getBlockZ() >> 4) == chunk.getZ()) {
                arena.updateLeaderboardHologram();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        for (RaceArena arena : plugin.getArenas().values()) {
            Location lbLoc = arena.getLeaderboardLocation();
            if (lbLoc != null && lbLoc.getWorld() != null
                    && lbLoc.getWorld().equals(chunk.getWorld())
                    && (lbLoc.getBlockX() >> 4) == chunk.getX()
                    && (lbLoc.getBlockZ() >> 4) == chunk.getZ()) {
                Hologram holo = getHologram("race_lb_" + arena.getName());
                if (holo != null) {
                    holo.onChunkUnload();
                }
            }
        }
    }
}
