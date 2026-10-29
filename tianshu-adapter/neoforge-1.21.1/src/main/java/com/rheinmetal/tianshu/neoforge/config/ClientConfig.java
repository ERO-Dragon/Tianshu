package com.rheinmetal.tianshu.neoforge.config;

import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsAccess;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The kernel's own settings.
 *
 * <p>Only what the kernel itself owns: the debug switch and the directory the voice resource layer materialises
 * into. Every feature module keeps its settings in its own file under
 * {@code config/Tianshu/module/<moduleId>.properties}, so adding a module option never edits this class.</p>
 */
public class ClientConfig implements GlobalDebugSettingsAccess {

    private final Path gameConfigDirectory;

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("debug");
        DEBUG_ENABLED = builder.define("enabled", false);
        builder.pop();

        SPEC = builder.build();
    }

    public ClientConfig(Path gameDirectory) {
        this.gameConfigDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve("config")
                .resolve("Tianshu");
    }

    @Override
    public boolean isDebugEnabled() {
        return TianshuBuildProfile.debugBuild() && DEBUG_ENABLED.get();
    }

    @Override
    public void setDebugEnabled(boolean enabled) {
        DEBUG_ENABLED.set(enabled);
    }

    public Path getRootPath() {
        return getGameConfigDir().resolve("module");
    }

    public Path getGameConfigDir() {
        return gameConfigDirectory;
    }

    @Override
    public void save() {
        SPEC.save();
    }
}
