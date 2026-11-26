package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.tool.BodyAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The server-side action switches on Fabric.
 *
 * <p>Every action is off by default: installing the component must not hand a model the ability to change the
 * world, so an operator turns on exactly what they want. NeoForge gets comments, backups and a per-save override
 * from its config system; Fabric has none of that, so this keeps the same shape in
 * {@code config/Tianshu/server.properties} and writes the defaults out on first start so there is a file to edit.</p>
 *
 * <p>Unlike the NeoForge side there is no per-save override yet: one file, one server process.</p>
 */
public final class FabricServerActionConfig {

    /** The whitelist, in the order the catalog announces it. */
    private static final List<String> ACTIONS = List.of(
            "give_item", "teleport", "set_time", "set_weather", "spawn_entity", "broadcast",
            "gamemode", "effect", "xp", "setblock", "kill", "enchant", "difficulty", "gamerule");

    private static final Map<String, Boolean> SWITCHES = new LinkedHashMap<>();

    /** Key of the operator's ceiling; read_only, conservative (default) or full. */
    private static final String AUTONOMY_CEILING_KEY = "autonomy.ceiling";

    private static final String NPC_CEILING_KEY = "npc.ceiling";
    private static volatile BodyAutonomy npcCeiling = BodyAutonomy.SURVIVAL;

    /** The operator's ceiling on server-side tools; loaded as text so a typo cannot fail startup. */
    private static volatile String ceiling = "conservative";

    static {
        for (String action : ACTIONS) {
            SWITCHES.put(action, false);
        }
    }

    private FabricServerActionConfig() {
    }

    /** Reads the switches, writing the defaults first when the file does not exist yet. */
    public static synchronized void load(Path gameDirectory) {
        Path file = gameDirectory.resolve("config").resolve("Tianshu").resolve("server.properties");
        Properties values = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(reader);
            } catch (IOException | IllegalArgumentException unreadable) {
                // The defaults are the safe answer: a file that cannot be read enables nothing.
                values.clear();
            }
        } else {
            writeDefaults(file, values);
        }
        for (String action : ACTIONS) {
            SWITCHES.put(action, Boolean.parseBoolean(values.getProperty(key(action), "false").trim()));
        }
        npcCeiling = BodyAutonomy.parse(values.getProperty(NPC_CEILING_KEY));
        ceiling = values.getProperty(AUTONOMY_CEILING_KEY, "conservative");
    }

    /**
     * The highest tier a server-side tool may require, whatever the client configured for itself.
     *
     * <p>Unreadable text falls back to the safe middle tier, matching the NeoForge default.</p>
     */
    public static ToolAutonomy autonomyCeiling() {
        return ToolAutonomy.parse(ceiling, ToolAutonomy.CONSERVATIVE);
    }

    public static BodyAutonomy bodyCeiling() {
        return npcCeiling;
    }

    public static boolean enabled(String action) {
        return Boolean.TRUE.equals(SWITCHES.get(action));
    }

    private static void writeDefaults(Path file, Properties values) {
        for (String action : ACTIONS) {
            values.setProperty(key(action), "false");
        }
        values.setProperty(NPC_CEILING_KEY, "survival");
        values.setProperty(AUTONOMY_CEILING_KEY, "conservative");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                values.store(writer, "Tianshu server actions (all off by default)");
            }
        } catch (IOException unwritable) {
            // Nothing actionable: the switches stay at their defaults, which is "all off".
        }
    }

    private static String key(String action) {
        return "action." + action + ".enabled";
    }
}
