package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.server.ServerActionResult;
import com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionResultPacket;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server half of the action channel: validates a request against the platform whitelist, the operator's
 * switches and the requester's permission, then executes it and answers with a result.
 *
 * <p>Idempotency is by {@code (player, requestId)}: a resent request returns the first answer instead of running the
 * action twice. The result cache is bounded; broadcast has a small fixed cooldown so one client cannot flood the
 * server chat. Every answer travels back on the same channel the request arrived on, and a player whose connection
 * is already gone is simply not answered.</p>
 */
public final class FabricServerActionHandler {

    private static final int OPERATOR_PERMISSION_LEVEL = 2;
    private static final long BROADCAST_COOLDOWN_MILLIS = 2_000L;
    private static final int MAX_BROADCAST_LENGTH = 256;

    private static final Map<String, ServerActionResult> RESULTS = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ServerActionResult> eldest) {
                    return size() > 512;
                }
            });
    private static final Map<UUID, Long> LAST_BROADCAST = new ConcurrentHashMap<>();

    private FabricServerActionHandler() {
    }

    /**
     * Registers the request receiver and the catalog announcement.
     *
     * <p>Both run on every distribution: in single player the integrated server is a server, and the same path has
     * to work there.</p>
     */
    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ServerActionRequestPacket.TYPE, (packet, context) ->
                context.server().execute(() -> respond(context.player(), packet)));
        // The server announces its action catalog when a player joins, so the client never has to guess.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            ServerPlayNetworking.send(player, new ServerActionCatalogPacket(FabricServerActionConfig.enabledActions()));
        });
    }

    private static void respond(ServerPlayer player, ServerActionRequestPacket packet) {
        if (player == null) {
            return;
        }
        ServerActionResult result = run(player, packet);
        if (player.connection == null || !ServerPlayNetworking.canSend(player, ServerActionResultPacket.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new ServerActionResultPacket(
                packet.requestId(), result.status(), result.message()));
    }

    private static ServerActionResult run(ServerPlayer player, ServerActionRequestPacket packet) {
        String key = player.getUUID() + "|" + packet.requestId();
        ServerActionResult done = RESULTS.get(key);
        if (done != null) {
            return done;
        }
        ServerActionResult result = execute(player, packet.kind(), packet.params());
        RESULTS.put(key, result);
        return result;
    }

    private static ServerActionResult execute(ServerPlayer player, String kind, Map<String, String> params) {
        if (!FabricServerActionConfig.enabled(kind)) {
            return ServerActionResult.of(ServerActionResult.Status.DISABLED, "action is disabled on this server");
        }
        if (!isOperator(player)) {
            return ServerActionResult.of(ServerActionResult.Status.DENIED, "operator permission is required");
        }
        return switch (kind) {
            case "give_item" -> giveItem(player, params);
            case "teleport" -> teleport(player, params);
            case "set_time" -> setTime(player, params);
            case "set_weather" -> setWeather(player, params);
            case "spawn_entity" -> spawnEntity(player, params);
            case "broadcast" -> broadcast(player, params);
            default -> ServerActionResult.of(ServerActionResult.Status.INVALID, "unknown action: " + kind);
        };
    }

    private static boolean isOperator(ServerPlayer player) {
        // 1.21.9 dropped Entity.getServer(), and the operator list now keys on NameAndId rather than a GameProfile.
        MinecraftServer server = player.level().getServer();
        return server != null && server.getPlayerList().isOp(new NameAndId(player.getGameProfile()));
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
            return ServerActionResult.of(ServerActionResult.Status.INVALID,
                    "time must be day/noon/night/midnight/ticks:<n>");
        }
        ServerLevel level = player.level();
        level.setDayTime(ticks);
        return ServerActionResult.ok("time set to " + ticks);
    }

    private static ServerActionResult setWeather(ServerPlayer player, Map<String, String> params) {
        String weather = params.getOrDefault("value", "");
        ServerLevel level = player.level();
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
        ServerLevel level = player.level();
        for (int index = 0; index < count; index++) {
            Entity entity = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (entity == null) {
                return ServerActionResult.of(ServerActionResult.Status.FAILED, "entity could not be created");
            }
            // 1.21.5 renamed the "place without interpolation" call from moveTo to snapTo.
            entity.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
            level.addFreshEntity(entity);
        }
        return ServerActionResult.ok("spawned " + count + " " + id);
    }

    private static ServerActionResult broadcast(ServerPlayer player, Map<String, String> params) {
        String text = params.getOrDefault("text", "").trim();
        if (text.isEmpty() || text.length() > MAX_BROADCAST_LENGTH) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID,
                    "broadcast text must be 1.." + MAX_BROADCAST_LENGTH + " chars");
        }
        long now = System.currentTimeMillis();
        Long last = LAST_BROADCAST.get(player.getUUID());
        if (last != null && now - last < BROADCAST_COOLDOWN_MILLIS) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "broadcast is cooling down");
        }
        LAST_BROADCAST.put(player.getUUID(), now);
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "server is not available");
        }
        server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
        return ServerActionResult.ok("broadcast sent");
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
