package com.rheinmetal.tianshu.neoforge.config;

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
        builder.pop();
        SPEC = builder.build();
    }

    private ServerActionConfig() {
    }
}
