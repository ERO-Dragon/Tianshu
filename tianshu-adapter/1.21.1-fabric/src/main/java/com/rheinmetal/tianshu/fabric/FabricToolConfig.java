package com.rheinmetal.tianshu.fabric;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The Fabric counterpart of the NeoForge config: one properties file written on first start so there is something to
 * edit.
 *
 * <p>Only {@code FULL}-tier tools appear here. Those are powers an operator hands over deliberately, and a server may
 * want only some of them; survival actions need no switch, because the autonomy ceiling is their gate.</p>
 */
public final class FabricToolConfig {

    private static final String COMMAND_RUN = "command_run";

    /** Level an approved command runs with; 0 keeps it a plain player's command. */
    private static final String COMMAND_PERMISSION_LEVEL = "command_permission_level";

    private static final Properties VALUES = new Properties();

    static {
        VALUES.setProperty(COMMAND_RUN, "false");
        VALUES.setProperty(COMMAND_PERMISSION_LEVEL, "0");
    }

    private FabricToolConfig() {
    }

    public static synchronized void load(Path gameDirectory) {
        Path file = gameDirectory.resolve("config").resolve("Tianshu").resolve("tools.properties");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException failure) {
            return;
        }
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                VALUES.load(reader);
            } catch (IOException ignored) {
                return;
            }
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            VALUES.store(writer, "FULL-tier tool switches. Defaults are off; changing them applies on next start.");
        } catch (IOException ignored) {
            // An unwritable config simply keeps the defaults.
        }
    }
    /** Whether the assistant may ask to run commands; approval still decides whether one runs. */
    public static boolean commandRun() {
        return Boolean.parseBoolean(VALUES.getProperty(COMMAND_RUN, "false").trim());
    }
    /** The permission level an approved command runs with, clamped to what a command source can carry. */
    public static int commandPermissionLevel() {
        String value = VALUES.getProperty(COMMAND_PERMISSION_LEVEL, "0").trim();
        try {
            return Math.max(0, Math.min(4, Integer.parseInt(value)));
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }
}
