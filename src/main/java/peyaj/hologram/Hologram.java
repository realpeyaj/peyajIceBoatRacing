package peyaj.hologram;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Hologram {

    private final JavaPlugin plugin;
    private final String id;
    private final NamespacedKey key;
    private Location location;
    private List<String> lines = new ArrayList<>();
    private UUID textDisplayUuid;
    private Component cachedComponent = null;

    public Hologram(JavaPlugin plugin, String id, Location location) {
        this.plugin = plugin;
        this.id = id;
        this.key = new NamespacedKey(plugin, "hologram_id");
        this.location = (location != null) ? location.clone() : null;
    }

    public String getId() {
        return id;
    }

    public Location getLocation() {
        return (location != null) ? location.clone() : null;
    }

    public NamespacedKey getKey() {
        return key;
    }

    public synchronized void setLocation(Location newLoc) {
        if (newLoc == null || newLoc.getWorld() == null) return;
        if (this.location != null && this.location.equals(newLoc)) {
            ensureSpawned();
            return;
        }

        Location oldLoc = this.location;
        this.location = newLoc.clone();

        TextDisplay display = getDisplayEntity();
        if (display != null && display.isValid()) {
            display.teleport(this.location);
        } else {
            display = getOrSpawnDisplay();
        }

        // Clean up any rogue or duplicate displays at the old location
        if (oldLoc != null && oldLoc.getWorld() != null) {
            cleanUpNearby(oldLoc, 6.0);
        }
    }

    public synchronized void setLines(List<String> lines) {
        List<String> newLines = (lines != null) ? new ArrayList<>(lines) : new ArrayList<>();
        this.lines = newLines;
        this.cachedComponent = buildComponent(this.lines);
        TextDisplay display = getDisplayEntity();
        if (display == null || !display.isValid()) {
            display = getOrSpawnDisplay();
        }
        if (display != null && display.isValid()) {
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.text(this.cachedComponent);
        }
    }

    public synchronized void ensureSpawned() {
        TextDisplay display = getDisplayEntity();
        if (display == null || !display.isValid()) {
            getOrSpawnDisplay();
        }
    }

    public UUID getTextDisplayUuid() {
        return textDisplayUuid;
    }

    public synchronized void remove() {
        TextDisplay display = getDisplayEntity();
        if (display != null && display.isValid()) {
            display.remove();
        }
        textDisplayUuid = null;
        if (location != null && location.getWorld() != null) {
            cleanUpNearby(location, 6.0);
        }
    }

    public synchronized void onChunkUnload() {
        TextDisplay display = getDisplayEntity();
        if (display != null && display.isValid()) {
            display.remove();
        }
        textDisplayUuid = null;
    }

    public synchronized TextDisplay getOrSpawnDisplay() {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        // Do NOT force-load chunks if unloaded; wait for ChunkLoadEvent
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return null;
        }

        // Try getting existing entity from stored UUID
        TextDisplay existing = getDisplayEntity();
        if (existing != null && existing.isValid()) {
            return existing;
        }

        // Clean up any stale, legacy, or duplicate TextDisplays near this location before spawning
        cleanUpNearby(location, 4.0);

        // Spawn a fresh non-persistent TextDisplay entity
        try {
            TextDisplay display = (TextDisplay) location.getWorld().spawnEntity(location, EntityType.TEXT_DISPLAY);
            // Non-persistent ensures Paper/Minecraft NEVER writes this entity to chunk region files on disk.
            display.setPersistent(false);
            display.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toLowerCase());
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setSeeThrough(true);
            display.setViewRange(2.0f);
            if (cachedComponent == null) {
                cachedComponent = buildComponent(lines);
            }
            display.text(cachedComponent);
            this.textDisplayUuid = display.getUniqueId();
            return display;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to spawn TextDisplay hologram for " + id + ": " + e.getMessage());
            return null;
        }
    }

    public void cleanUpNearby(Location loc, double radius) {
        if (loc == null || loc.getWorld() == null) return;
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;

        try {
            for (Entity nearby : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
                if (nearby instanceof TextDisplay td) {
                    String taggedId = td.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                    if (id.equalsIgnoreCase(taggedId)) {
                        // Protect our active entity from deletion
                        if (textDisplayUuid != null && td.getUniqueId().equals(textDisplayUuid)) {
                            continue;
                        }
                        td.remove();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public TextDisplay getDisplayEntity() {
        if (textDisplayUuid != null) {
            Entity entity = Bukkit.getEntity(textDisplayUuid);
            if (entity instanceof TextDisplay textDisplay && textDisplay.isValid()) {
                return textDisplay;
            }
        }
        return null;
    }

    private Component buildComponent(List<String> textLines) {
        if (textLines.isEmpty()) {
            return Component.empty();
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < textLines.size(); i++) {
            builder.append(textLines.get(i));
            if (i < textLines.size() - 1) {
                builder.append("\n");
            }
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(builder.toString());
    }
}
