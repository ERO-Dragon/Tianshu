package com.rheinmetal.tianshu.neoforge.config;

import com.rheinmetal.tianshu.api.tool.BodyAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side switches for the actions a client may request.
 *
 * <p>Every action is off by default: installing the server component must not mean "the assistant can give items
 * and teleport players". The operator turns on exactly what they allow; the first version additionally requires
 * operator permission for each request.</p>
 */
public final class ServerActionConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue GIVE_ITEM;
    public static final ModConfigSpec.BooleanValue TELEPORT;
    public static final ModConfigSpec.BooleanValue SET_TIME;
    public static final ModConfigSpec.BooleanValue SET_WEATHER;
    public static final ModConfigSpec.BooleanValue SPAWN_ENTITY;
    public static final ModConfigSpec.BooleanValue BROADCAST;
    public static final ModConfigSpec.BooleanValue SETBLOCK;
    public static final ModConfigSpec.BooleanValue KILL;
    public static final ModConfigSpec.BooleanValue ENCHANT;
    public static final ModConfigSpec.BooleanValue DIFFICULTY;
    public static final ModConfigSpec.BooleanValue GAMERULE;
    public static final ModConfigSpec.BooleanValue XP;
    public static final ModConfigSpec.BooleanValue EFFECT;
    public static final ModConfigSpec.BooleanValue GAMEMODE;
    public static final ModConfigSpec.BooleanValue COMMAND_RUN;
    public static final ModConfigSpec.IntValue COMMAND_PERMISSION_LEVEL;
    public static final ModConfigSpec.ConfigValue<String> AUTONOMY_CEILING;

    public static final ModConfigSpec.ConfigValue<String> NPC_CEILING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("What the assistant may do on the server. Each switch is independent and off by default.")
                .push("actions");
        GIVE_ITEM = builder.comment("Give an item to the requesting player.").define("give_item", false);
        TELEPORT = builder.comment("Teleport the requesting player.").define("teleport", false);
        SET_TIME = builder.comment("Change the level's time.").define("set_time", false);
        SET_WEATHER = builder.comment("Change the level's weather.").define("set_weather", false);
        SPAWN_ENTITY = builder.comment("Spawn entities at the requesting player.").define("spawn_entity", false);
        BROADCAST = builder.comment("Broadcast one line to every player.").define("broadcast", false);
    GAMEMODE = builder.comment("Change the requesting player's game mode.").define("gamemode", false);
    EFFECT = builder.comment("Give or clear status effects on the requesting player.").define("effect", false);
    XP = builder.comment("Change the requesting player's experience level.").define("xp", false);
    SETBLOCK = builder.comment("Place one block in the requesting player's level.").define("setblock", false);
    KILL = builder.comment("Remove one entity in the requesting player's level.").define("kill", false);
    ENCHANT = builder.comment("Enchant the requesting player's main hand.").define("enchant", false);
    DIFFICULTY = builder.comment("Change the server difficulty.").define("difficulty", false);
    GAMERULE = builder.comment("Change a whitelisted game rule.").define("gamerule", false);
        builder.pop();
        builder.comment("One switch per FULL-tier tool: those are powers an operator hands over deliberately, and a server",
                        "may want only some of them. Survival actions need no switch — the ceiling below is their gate.")
                .push("tools");
        COMMAND_RUN = builder
                .comment("Let the assistant ASK to run a command. Nothing runs until an operator approves the exact line",
                        "with /tianshu approve <token>: one token runs one command once.")
                .define("command_run", false);
        COMMAND_PERMISSION_LEVEL = builder
                .comment("The permission level an approved command runs with: 0 = as a plain player (default),",
                        "2 = what an operator would use, 4 = anything. Raise it only if you mean to.")
                .defineInRange("command_permission_level", 0, 0, 4);
        builder.pop();
        builder.comment("How much an agent may do on this server, whatever the client configures for itself.")
                .push("autonomy");
        AUTONOMY_CEILING = builder
                .comment("Highest tier a server-side tool may require:",
                        "read_only = reads only; conservative = survival actions, no cheats (default);",
                        "full = teleport, weather, item grants, spawns, broadcasts.",
                        "A client cannot go above this, whatever it set for itself. Raise it only if you mean to")
                .define("ceiling", "conservative");
        builder.pop();
        builder.push("npc");
        NPC_CEILING = builder.comment("Maximum NPC action tier: survival (native abilities) or cheat.")
                .define("ceiling", "survival");
        builder.pop();
        SPEC = builder.build();
    }

    /** The switch that governs a whitelisted action, or {@code null} when the action is not one of them. */
    public static ModConfigSpec.BooleanValue switchFor(String action) {
        return switch (action) {
            case "give_item" -> GIVE_ITEM;
            case "teleport" -> TELEPORT;
            case "set_time" -> SET_TIME;
            case "set_weather" -> SET_WEATHER;
            case "spawn_entity" -> SPAWN_ENTITY;
            case "broadcast" -> BROADCAST;
            case "gamemode" -> GAMEMODE;
            case "effect" -> EFFECT;
            case "xp" -> XP;
            case "setblock" -> SETBLOCK;
            case "kill" -> KILL;
            case "enchant" -> ENCHANT;
            case "difficulty" -> DIFFICULTY;
            case "gamerule" -> GAMERULE;
            default -> null;
        };
    }

    public static BodyAutonomy bodyCeiling() {
        return BodyAutonomy.parse(NPC_CEILING.get());
    }

    /** The ceiling as the tool layer understands it; unreadable text falls back to the safe middle tier. */
    public static ToolAutonomy autonomyCeiling() {
        return ToolAutonomy.parse(AUTONOMY_CEILING.get(), ToolAutonomy.CONSERVATIVE);
    }

    /**
     * One line saying what this server will actually allow.
     *
     * <p>Logged once at server start. Without it, "I edited the file and nothing changed" is unresolvable from the
     * outside: the effective values and the file on disk can differ, and the reader has no way to tell which is in
     * force.</p>
     */
    public static String describe() {
        return "ceiling=" + AUTONOMY_CEILING.get() + " npc.ceiling=" + bodyCeiling()
                + " actions[teleport=" + TELEPORT.get() + " weather=" + SET_WEATHER.get() + " time=" + SET_TIME.get()
                + " give=" + GIVE_ITEM.get() + " spawn=" + SPAWN_ENTITY.get() + " broadcast=" + BROADCAST.get()
                + " gamemode=" + GAMEMODE.get() + " effect=" + EFFECT.get() + " xp=" + XP.get()
                + " setblock=" + SETBLOCK.get() + " kill=" + KILL.get() + " enchant=" + ENCHANT.get()
                + " difficulty=" + DIFFICULTY.get() + " gamerule=" + GAMERULE.get() + "]"
                + " command_run=" + COMMAND_RUN.get();
    }

    private ServerActionConfig() {
    }
}
