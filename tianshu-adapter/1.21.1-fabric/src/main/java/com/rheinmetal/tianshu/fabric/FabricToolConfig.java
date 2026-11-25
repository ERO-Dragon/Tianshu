package com.rheinmetal.tianshu.fabric;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The Fabric counterpart of the NeoForge tool switches: one properties file, off by default, written on first start
 * so there is something to edit.
 */
public final class FabricToolConfig {

    private static final String BLOCK_INTERACT = "block_interact";
    private static final String CRAFT_RECIPE = "craft_recipe";
    private static final String CONTAINER_TRANSFER = "container_transfer";
    private static final String BLOCK_MACHINE = "block_machine";
    private static final String BODY_ACTIONS = "body_actions";
    private static final String BLOCK_PLACE = "block_place";
    private static final String COMMAND_RUN = "command_run";

    /** Level an approved command runs with; 0 keeps it a plain player's command. */
    private static final String COMMAND_PERMISSION_LEVEL = "command_permission_level";

    private static final Properties VALUES = new Properties();

    static {
        VALUES.setProperty(BLOCK_INTERACT, "false");
        VALUES.setProperty(CRAFT_RECIPE, "false");
        VALUES.setProperty(CONTAINER_TRANSFER, "false");
        VALUES.setProperty(BLOCK_MACHINE, "false");
        VALUES.setProperty(BODY_ACTIONS, "false");
        VALUES.setProperty(BLOCK_PLACE, "false");
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
            VALUES.store(writer, "Server-side tool switches. Defaults are off; changing them applies on next start.");
        } catch (IOException ignored) {
            // An unwritable config simply keeps the defaults.
        }
    }

    public static boolean blockInteract() {
        return Boolean.parseBoolean(VALUES.getProperty(BLOCK_INTERACT, "false").trim());
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

    public static boolean craftRecipe() {
        return Boolean.parseBoolean(VALUES.getProperty(CRAFT_RECIPE, "false").trim());
    }

    public static boolean containerTransfer() {
        return Boolean.parseBoolean(VALUES.getProperty(CONTAINER_TRANSFER, "false").trim());
    }

    public static boolean blockMachine() {
        return Boolean.parseBoolean(VALUES.getProperty(BLOCK_MACHINE, "false").trim());
    }

    public static boolean bodyActions() {
        return Boolean.parseBoolean(VALUES.getProperty(BODY_ACTIONS, "false").trim());
    }

    public static boolean blockPlace() {
        return Boolean.parseBoolean(VALUES.getProperty(BLOCK_PLACE, "false").trim());
    }
}