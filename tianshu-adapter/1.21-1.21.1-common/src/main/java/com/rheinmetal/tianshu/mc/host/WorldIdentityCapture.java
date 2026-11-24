package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.api.game.WorldFacts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Captures Minecraft world identity on the client thread into a background-safe snapshot.
 *
 * <p>It reports facts in the platform's own vocabulary ({@code server_world}, {@code local_world}); which
 * module cares and what it calls them is not this class's business.</p>
 */
public final class WorldIdentityCapture {

    private final Path gameDirectory;
    private volatile WorldFacts facts = WorldFacts.UNKNOWN;

    public WorldIdentityCapture(Path gameDirectory) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
    }

    public void refresh() {
        facts = capture();
    }

    /**
     * Re-samples and keeps the result only when something about the world changed.
     *
     * <p>Cheap enough to call every tick: the sample reads the level, the player and the server entry, and a dimension
     * change is a change of {@code dimensionId} — which a "refresh only while unavailable" rule never noticed, so a
     * player who walked through a portal kept reporting the overworld until they rejoined.</p>
     */
    public void refreshIfChanged() {
        WorldFacts next = capture();
        if (!next.equals(facts)) {
            facts = next;
        }
    }

    public void clear() {
        facts = WorldFacts.UNKNOWN;
    }

    public WorldFacts current() {
        return facts;
    }

    public boolean isAvailable() {
        return facts.writable();
    }

    private WorldFacts capture() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return WorldFacts.UNKNOWN;
        }
        String dimension = minecraft.level.dimension().location().toString();
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            if (server.ip == null || server.ip.isBlank()) {
                return WorldFacts.UNKNOWN;
            }
            String address = server.ip.trim().toLowerCase(Locale.ROOT);
            String name = server.name == null || server.name.isBlank() ? address : server.name.trim();
            return new WorldFacts(WorldFacts.KIND_SERVER_WORLD, "server|" + address, name, dimension, true);
        }

        String levelName = localLevelName(minecraft);
        // The save folder is what makes two worlds distinct; the display name is only a label, and two saves
        // may share it. The stable identity therefore uses the folder id, while the label stays player-facing.
        String saveId = localSaveId(minecraft);
        if (saveId.isBlank()) {
            return WorldFacts.UNKNOWN;
        }
        String stableIdentity = "local|" + gameDirectory + "|" + saveId;
        return new WorldFacts(WorldFacts.KIND_LOCAL_WORLD, stableIdentity, levelName, dimension, true);
    }

    private String localSaveId(Minecraft minecraft) {
        try {
            if (minecraft.getSingleplayerServer() != null) {
                // The save folder is the stable part. Asking for LevelResource.ROOT returns "<save>/." on 1.21.1 —
                // getFileName() then yields "." for every world, which collapsed two saves onto one identity. The
                // level.dat's parent is the folder itself, whatever the resource name happens to expand to.
                Path levelData = minecraft.getSingleplayerServer()
                        .getWorldPath(net.minecraft.world.level.storage.LevelResource.LEVEL_DATA_FILE);
                if (levelData != null) {
                    Path folder = levelData.toAbsolutePath().normalize().getParent();
                    if (folder != null && folder.getFileName() != null) {
                        return folder.getFileName().toString().trim();
                    }
                }
                Path worldPath = minecraft.getSingleplayerServer()
                        .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                if (worldPath != null) {
                    Path folder = worldPath.toAbsolutePath().normalize();
                    if (folder.getFileName() != null) {
                        String name = folder.getFileName().toString().trim();
                        if (!name.isEmpty() && !name.equals(".")) {
                            return name;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private String localLevelName(Minecraft minecraft) {
        try {
            if (minecraft.getSingleplayerServer() != null) {
                String levelName = minecraft.getSingleplayerServer().getWorldData().getLevelName();
                if (levelName != null && !levelName.isBlank()) {
                    return levelName.trim();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "local_world";
    }
}
