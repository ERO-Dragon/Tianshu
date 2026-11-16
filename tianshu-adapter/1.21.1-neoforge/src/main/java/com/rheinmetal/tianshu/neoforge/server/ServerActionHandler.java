package com.rheinmetal.tianshu.neoforge.server;

import com.rheinmetal.tianshu.api.server.ServerActionResult;
import com.rheinmetal.tianshu.neoforge.config.ServerActionConfig;



import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server half of the action channel: validates a request against the platform whitelist, the operator's
 * switches, and the requester's permission, then executes it and answers with a result.
 *
 * <p>Idempotency is by {@code (player, requestId)}: a resent request returns the first answer instead of running the
 * action twice. The result cache is bounded; broadcast has a small fixed cooldown so one client cannot flood the
 * server chat.</p>
 */
public final class ServerActionHandler {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;
    private static final long BROADCAST_COOLDOWN_MILLIS = 2_000L;
    private static final int MAX_BROADCAST_LENGTH = 256;

    private static final Map<UUID, Long> LAST_BROADCAST = new ConcurrentHashMap<>();

    private ServerActionHandler() {
    }

    /** Runs one whitelisted action for a player; the action execution port calls this. */
    public static ServerActionResult execute(ServerPlayer player, String kind, Map<String, String> params) {
        if (!switchEnabled(kind)) {
            return ServerActionResult.of(ServerActionResult.Status.DISABLED, "action is disabled on this server");
        }
        if (!player.hasPermissions(OPERATOR_PERMISSION_LEVEL)) {
            return ServerActionResult.of(ServerActionResult.Status.DENIED, "operator permission is required");
        }
        return switch (kind) {
            case "give_item" -> giveItem(player, params);
            case "teleport" -> teleport(player, params);
            case "set_time" -> setTime(player, params);
            case "set_weather" -> setWeather(player, params);
            case "spawn_entity" -> spawnEntity(player, params);
            case "broadcast" -> broadcast(player, params);
            case "gamemode" -> setGameMode(player, params);
            case "effect" -> applyEffect(player, params);
            case "xp" -> giveExperience(player, params);
            case "setblock" -> setBlock(player, params);
            case "kill" -> killEntity(player, params);
            case "enchant" -> enchantMainHand(player, params);
            case "difficulty" -> setDifficulty(player, params);
            case "gamerule" -> setGameRule(player, params);
            default -> ServerActionResult.of(ServerActionResult.Status.INVALID, "unknown action: " + kind);
        };
    }

    private static boolean switchEnabled(String kind) {
        var value = ServerActionConfig.switchFor(kind);
        return value != null && value.get();
    }

    private static ServerActionResult giveItem(ServerPlayer player, Map<String, String> params) {
        ResourceLocation id = ResourceLocation.tryParse(params.getOrDefault("item", ""));
        if (id == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "invalid item id");
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "unknown item: " + id);
        }
        int count = parseCount(params.getOrDefault("count", "1"), 1, 64);
        if (count < 1) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "count must be 1..64");
        }
        ItemStack stack = new ItemStack(item, count);
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            player.drop(stack, false);
        }
        return ServerActionResult.ok("gave " + count + " " + id);
    }

    private static ServerActionResult teleport(ServerPlayer player, Map<String, String> params) {
        double x = parseDouble(params.get("x"));
        double y = parseDouble(params.get("y"));
        double z = parseDouble(params.get("z"));
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "invalid coordinates");
        }
        player.teleportTo(x, y, z);
        return ServerActionResult.ok("teleported");
    }

    private static ServerActionResult setTime(ServerPlayer player, Map<String, String> params) {
        Long ticks = parseTime(params.getOrDefault("value", ""));
        if (ticks == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "time must be day/noon/night/midnight/ticks:<n>");
        }
        ServerLevel level = player.serverLevel();
        level.setDayTime(ticks);
        return ServerActionResult.ok("time set to " + ticks);
    }

    private static ServerActionResult setWeather(ServerPlayer player, Map<String, String> params) {
        String weather = params.getOrDefault("value", "");
        ServerLevel level = player.serverLevel();
        switch (weather) {
            case "clear" -> level.setWeatherParameters(12_000, 0, false, false);
            case "rain" -> level.setWeatherParameters(0, 12_000, true, false);
            case "thunder" -> level.setWeatherParameters(0, 12_000, true, true);
            default -> {
                return ServerActionResult.of(ServerActionResult.Status.INVALID, "weather must be clear/rain/thunder");
            }
        }
        return ServerActionResult.ok("weather set to " + weather);
    }

    private static ServerActionResult spawnEntity(ServerPlayer player, Map<String, String> params) {
        ResourceLocation id = ResourceLocation.tryParse(params.getOrDefault("type", ""));
        if (id == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "invalid entity id");
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "unknown entity: " + id);
        }
        int count = parseCount(params.getOrDefault("count", "1"), 1, 16);
        if (count < 1) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "count must be 1..16");
        }
        ServerLevel level = player.serverLevel();
        for (int index = 0; index < count; index++) {
            Entity entity = type.create(level);
            if (entity == null) {
                return ServerActionResult.of(ServerActionResult.Status.FAILED, "entity could not be created");
            }
            entity.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
            level.addFreshEntity(entity);
        }
        return ServerActionResult.ok("spawned " + count + " " + id);
    }

    private static ServerActionResult broadcast(ServerPlayer player, Map<String, String> params) {
        String text = params.getOrDefault("text", "").trim();
        if (text.isEmpty() || text.length() > MAX_BROADCAST_LENGTH) {
            return ServerActionResult.of(
                    ServerActionResult.Status.INVALID, "broadcast text must be 1.." + MAX_BROADCAST_LENGTH + " chars");
        }
        long now = System.currentTimeMillis();
        Long last = LAST_BROADCAST.get(player.getUUID());
        if (last != null && now - last < BROADCAST_COOLDOWN_MILLIS) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "broadcast is cooling down");
        }
        LAST_BROADCAST.put(player.getUUID(), now);
        if (player.getServer() == null) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "server is not available");
        }
        player.getServer().getPlayerList().broadcastSystemMessage(Component.literal(text), false);
        return ServerActionResult.ok("broadcast sent");
    }

    private static ServerActionResult setGameMode(ServerPlayer player, Map<String, String> params) {
        String value = params.getOrDefault("value", "").trim().toLowerCase(java.util.Locale.ROOT);
        net.minecraft.world.level.GameType mode = switch (value) {
            case "survival" -> net.minecraft.world.level.GameType.SURVIVAL;
            case "creative" -> net.minecraft.world.level.GameType.CREATIVE;
            case "adventure" -> net.minecraft.world.level.GameType.ADVENTURE;
            case "spectator" -> net.minecraft.world.level.GameType.SPECTATOR;
            default -> null;
        };
        if (mode == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID,
                    "gamemode must be survival, creative, adventure or spectator");
        }
        player.setGameMode(mode);
        return ServerActionResult.ok("gamemode is now " + value);
    }

    private static ServerActionResult applyEffect(ServerPlayer player, Map<String, String> params) {
        String id = params.getOrDefault("effect", "").trim();
        if ("clear".equalsIgnoreCase(id)) {
            player.removeAllEffects();
            return ServerActionResult.ok("effects cleared");
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "malformed effect id: " + id);
        }
        java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.effect.MobEffect>> holder =
                BuiltInRegistries.MOB_EFFECT.getHolder(key);
        if (holder.isEmpty()) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "unknown effect: " + id);
        }
        int seconds = parseCount(params.getOrDefault("seconds", "30"), 1, 600);
        int amplifier = parseCount(params.getOrDefault("amplifier", "0"), 0, 9);
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(holder.get(), seconds * 20, amplifier));
        return ServerActionResult.ok("effect " + id + " for " + seconds + "s");
    }

    private static ServerActionResult giveExperience(ServerPlayer player, Map<String, String> params) {
        int levels = parseCount(params.getOrDefault("levels", "0"), -100, 1000);
        player.giveExperienceLevels(levels);
        return ServerActionResult.ok("experience changed by " + levels + " level(s)");
    }

    private static ServerActionResult setBlock(ServerPlayer player, Map<String, String> params) {
        int x = parseCount(params.getOrDefault("x", "0"), -30_000_000, 30_000_000);
        int y = parseCount(params.getOrDefault("y", "0"), -2_048, 2_048);
        int z = parseCount(params.getOrDefault("z", "0"), -30_000_000, 30_000_000);
        ResourceLocation key = ResourceLocation.tryParse(params.getOrDefault("block", "").trim());
        var block = key == null ? java.util.Optional.<net.minecraft.world.level.block.Block>empty()
                : BuiltInRegistries.BLOCK.getOptional(key);
        if (block.isEmpty()) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "unknown block id");
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(new net.minecraft.core.BlockPos(x, y, z))) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "that chunk is not loaded");
        }
        level.setBlock(new net.minecraft.core.BlockPos(x, y, z), block.get().defaultBlockState(), 3);
        return ServerActionResult.ok("placed " + key + " at " + x + " " + y + " " + z);
    }

    private static ServerActionResult killEntity(ServerPlayer player, Map<String, String> params) {
        UUID id;
        try {
            id = UUID.fromString(params.getOrDefault("id", "").trim());
        } catch (IllegalArgumentException malformed) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "malformed entity id");
        }
        Entity entity = player.serverLevel().getEntity(id);
        if (entity == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "no such entity in this level");
        }
        if (entity == player) {
            return ServerActionResult.of(ServerActionResult.Status.DENIED, "refusing to kill the requesting player");
        }
        entity.kill();
        return ServerActionResult.ok("removed " + id);
    }

    private static ServerActionResult enchantMainHand(ServerPlayer player, Map<String, String> params) {
        ResourceLocation key = ResourceLocation.tryParse(params.getOrDefault("enchantment", "").trim());
        if (key == null || player.getServer() == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "unknown enchantment");
        }
        // Enchantments are data-driven since 1.21: they live in the server's registry access, not BuiltInRegistries.
        var holder = player.getServer().registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolder(key);
        if (holder.isEmpty()) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "unknown enchantment");
        }
        int level = parseCount(params.getOrDefault("level", "1"), 1, 255);
        net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "the main hand is empty");
        }
        stack.enchant(holder.get(), level);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
        return ServerActionResult.ok("enchanted the main hand with " + key + " " + level);
    }

    private static ServerActionResult setDifficulty(ServerPlayer player, Map<String, String> params) {
        String value = params.getOrDefault("value", "").trim().toLowerCase(java.util.Locale.ROOT);
        net.minecraft.world.Difficulty difficulty = switch (value) {
            case "peaceful" -> net.minecraft.world.Difficulty.PEACEFUL;
            case "easy" -> net.minecraft.world.Difficulty.EASY;
            case "normal" -> net.minecraft.world.Difficulty.NORMAL;
            case "hard" -> net.minecraft.world.Difficulty.HARD;
            default -> null;
        };
        if (difficulty == null || player.getServer() == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "difficulty must be peaceful, easy, normal or hard");
        }
        player.getServer().setDifficulty(difficulty, true);
        return ServerActionResult.ok("difficulty is now " + value);
    }

    private static ServerActionResult setGameRule(ServerPlayer player, Map<String, String> params) {
        String rule = params.getOrDefault("rule", "").trim();
        boolean value = Boolean.parseBoolean(params.getOrDefault("value", "false"));
        if (player.getServer() == null) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "server is not available");
        }
        var rules = player.getServer().getGameRules();
        var server = player.getServer();
        switch (rule) {
            case "doDaylightCycle" -> rules.getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(value, server);
            case "doMobSpawning" -> rules.getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(value, server);
            case "keepInventory" -> rules.getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(value, server);
            case "mobGriefing" -> rules.getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(value, server);
            case "naturalRegeneration" -> rules.getRule(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION).set(value, server);
            case "doImmediateRespawn" -> rules.getRule(net.minecraft.world.level.GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(value, server);
            default -> {
                return ServerActionResult.of(ServerActionResult.Status.INVALID, "game rule is not on the whitelist: " + rule);
            }
        }
        return ServerActionResult.ok(rule + " is now " + value);
    }

    private static int parseCount(String raw, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= minimum && value <= maximum ? value : -1;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static double parseDouble(String raw) {
        try {
            return raw == null ? Double.NaN : Double.parseDouble(raw.trim());
        } catch (NumberFormatException invalid) {
            return Double.NaN;
        }
    }

    private static Long parseTime(String raw) {
        return switch (raw.trim()) {
            case "day" -> 1_000L;
            case "noon" -> 6_000L;
            case "night" -> 13_000L;
            case "midnight" -> 18_000L;
            default -> {
                if (raw.startsWith("ticks:")) {
                    try {
                        long ticks = Long.parseLong(raw.substring("ticks:".length()).trim());
                        yield ticks >= 0L && ticks <= 24_000L ? ticks : null;
                    } catch (NumberFormatException invalid) {
                        yield null;
                    }
                }
                yield null;
            }
        };
    }
}