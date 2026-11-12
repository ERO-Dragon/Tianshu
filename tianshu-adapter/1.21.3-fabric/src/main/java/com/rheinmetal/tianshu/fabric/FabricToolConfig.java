package com.rheinmetal.tianshu.fabric;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The Fabric counterpart of the server tool switches: one properties file, off by default. */
public final class FabricToolConfig {

    private static final String BLOCK_INTERACT = "block_interact";
    private static final String CONTAINER_TRANSFER = "container_transfer";

    private static final Properties VALUES = new Properties();

    static {
        VALUES.setProperty(BLOCK_INTERACT, "false");
        VALUES.setProperty(CONTAINER_TRANSFER, "false");
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

    public static boolean containerTransfer() {
        return Boolean.parseBoolean(VALUES.getProperty(CONTAINER_TRANSFER, "false").trim());
    }
}
