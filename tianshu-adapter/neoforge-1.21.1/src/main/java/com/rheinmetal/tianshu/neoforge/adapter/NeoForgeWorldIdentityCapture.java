package com.rheinmetal.tianshu.neoforge.adapter;

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
public final class NeoForgeWorldIdentityCapture {

    private final Path gameDirectory;
    private volatile WorldFacts facts = WorldFacts.UNKNOWN;

    public NeoForgeWorldIdentityCapture(Path gameDirectory) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
    }

    public void refresh() {
        facts = capture();
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
            String address = server.ip == null ? "unknown" : server.ip.trim().toLowerCase(Locale.ROOT);
            String name = server.name == null || server.name.isBlank() ? address : server.name.trim();
            return new WorldFacts(WorldFacts.KIND_SERVER_WORLD, "server|" + address, name, dimension, true);
        }

        String levelName = localLevelName(minecraft);
        String stableIdentity = "local|" + gameDirectory + "|" + levelName;
        return new WorldFacts(WorldFacts.KIND_LOCAL_WORLD, stableIdentity, levelName, dimension, true);
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
