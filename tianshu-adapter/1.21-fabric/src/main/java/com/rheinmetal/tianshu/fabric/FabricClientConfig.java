package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsAccess;
import com.rheinmetal.tianshu.mc.config.TianshuBuildProfile;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * The kernel's own settings on Fabric.
 *
 * <p>NeoForge hands its config system a {@code ModConfigSpec} and gets comments, backups and a GUI for free.
 * Fabric has no such system, so this writes the one switch the kernel owns — the debug channel — to
 * {@code config/Tianshu/client.properties}. It lives under the module directory on purpose: the root
 * {@code config/} belongs to files a loader manages, and this is not one.</p>
 *
 * <p>The gate is the same as on NeoForge: a release build ignores the switch entirely, because
 * {@link TianshuBuildProfile#debugBuild()} is false there. A player who edits the file gets nothing but a
 * settings page that has no debug section.</p>
 */
public final class FabricClientConfig implements GlobalDebugSettingsAccess {

    private static final String DEBUG_ENABLED = "debug.enabled";

    private final Path file;
    private final Properties values = new Properties();
    private boolean debugEnabled;

    public FabricClientConfig(Path gameDirectory) {
        Path configDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve("config")
                .resolve("Tianshu");
        this.file = configDirectory.resolve("client.properties");
        load();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IOException unreadable) {
            // A config that cannot be read is treated as absent: the defaults are the honest answer, and the
            // diagnostics router is not built yet at this point in the bootstrap.
            return;
        }
        debugEnabled = Boolean.parseBoolean(values.getProperty(DEBUG_ENABLED, "false").trim());
    }

    @Override
    public boolean isDebugEnabled() {
        return TianshuBuildProfile.debugBuild() && debugEnabled;
    }

    @Override
    public void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        values.setProperty(DEBUG_ENABLED, Boolean.toString(enabled));
    }

    @Override
    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                values.store(writer, "Tianshu client settings (Fabric)");
            }
        } catch (IOException unwritable) {
            // Nothing sensible to do: the value is already in memory for this session, and the next start
            // falls back to the default. Failing the client over a config write would be worse.
        }
    }
}
