package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;
import java.util.Objects;
import java.nio.file.Path;

/** Captures Minecraft world identity on the client thread into a background-safe provider. */
public final class NeoForgeWorldIdentityCapture {
    private final NeoForgeAXWorldIdentityProvider provider;
    private final Path gameDirectory;

    public NeoForgeWorldIdentityCapture(NeoForgeAXWorldIdentityProvider provider, Path gameDirectory) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory").toAbsolutePath().normalize();
    }

    public void refresh() {
        provider.update(capture());
    }

    public void clear() {
        provider.clear();
    }

    public boolean isAvailable() {
        return provider.currentWorldIdentity().writable();
    }

    private AXScopeSnapshot capture() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return AXScopeSnapshot.unknown();
        }
        String dimension = minecraft.level.dimension().location().toString();
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            String address = server.ip == null ? "unknown" : server.ip.trim().toLowerCase(Locale.ROOT);
            String name = server.name == null || server.name.isBlank() ? address : server.name.trim();
            return new AXScopeSnapshot(AXScopeKind.SERVER_WORLD, "server|" + address, name, dimension, true);
        }

        String levelName = localLevelName(minecraft);
        String stableIdentity = "local|" + gameDirectory + "|" + levelName;
        return new AXScopeSnapshot(AXScopeKind.LOCAL_WORLD, stableIdentity, levelName, dimension, true);
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
