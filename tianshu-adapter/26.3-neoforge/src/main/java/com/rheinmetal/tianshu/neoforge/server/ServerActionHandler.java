package com.rheinmetal.tianshu.neoforge.server;

import com.rheinmetal.tianshu.api.server.ServerActionResult;
import com.rheinmetal.tianshu.neoforge.config.ServerActionConfig;
import com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionResultPacket;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
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

    private ServerActionHandler() {
    }

    /** The server announces its action catalog when a player joins, so the client never has to guess. */
    public static void register(IEventBus gameBus) {
        gameBus.addListener(ServerActionHandler::onPlayerLogin);
    }

    private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.connection == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new ServerActionCatalogPacket(enabledActions()));
    }

    private static List<String> enabledActions() {
        List<String> actions = new ArrayList<>(6);
        if (ServerActionConfig.GIVE_ITEM.get()) {
            actions.add("give_item");
        }
        if (ServerActionConfig.TELEPORT.get()) {
            actions.add("teleport");
        }
        if (ServerActionConfig.SET_TIME.get()) {
            actions.add("set_time");
        }
        if (ServerActionConfig.SET_WEATHER.get()) {
            actions.add("set_weather");
        }
        if (ServerActionConfig.SPAWN_ENTITY.get()) {
            actions.add("spawn_entity");
        }
        if (ServerActionConfig.BROADCAST.get()) {
            actions.add("broadcast");
        }
        return actions;
    }

    /** Entry point registered with the payload registrar; the work runs on the server thread. */
    public static void handle(ServerActionRequestPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> respond(player, packet));
    }

    private static void respond(ServerPlayer player, ServerActionRequestPacket packet) {
        ServerActionResult result = run(player, packet);
        if (player.connection == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new ServerActionResultPacket(
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
        if (!switchEnabled(kind)) {
            return ServerActionResult.of(ServerActionResult.Status.DISABLED, "action is disabled on this server");
        }
        // 1.21.11 replaced numeric permission levels with named permissions; the old level-2 gate is gamemaster.
        if (!player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
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

    private static boolean switchEnabled(String kind) {
        var value = switch (kind) {
            case "give_item" -> ServerActionConfig.GIVE_ITEM;
            case "teleport" -> ServerActionConfig.TELEPORT;
            case "set_time" -> ServerActionConfig.SET_TIME;
            case "set_weather" -> ServerActionConfig.SET_WEATHER;
            case "spawn_entity" -> ServerActionConfig.SPAWN_ENTITY;
            case "broadcast" -> ServerActionConfig.BROADCAST;
            default -> null;
        };
        return value != null && value.get();
    }

    private static ServerActionResult giveItem(ServerPlayer player, Map<String, String> params) {
        Identifier id = Identifier.tryParse(params.getOrDefault("item", ""));
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
            // 26.3 asks the caller what the drop is: this runs on the server thread, so no client-side prediction
            // applies and the stack is authoritative immediately.
            player.drop(stack, false, Prediction.SERVER_ONLY);
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

    /**
     * 26.1 moved the day/time counter out of the level and into per-dimension world clocks
     * ({@code Level.clockManager()} / {@code MinecraftServer.clockManager()}). A named time is a named marker of the
     * dimension's default clock, which is what the vanilla {@code /time set} command does; a raw tick count is the
     * clock's absolute total.
     */
    private static ServerActionResult setTime(ServerPlayer player, Map<String, String> params) {
        String raw = params.getOrDefault("value", "").trim();
        // 1.21.6 removed serverLevel(); ServerPlayer overrides level() with the ServerLevel-typed result.
        ServerLevel level = player.level();
        if (level.getServer() == null) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "server is not available");
        }
        // The dimension owns which clock is "the time"; a dimension without a default clock has no day cycle at all.
        Holder<WorldClock> clock = level.dimensionTypeRegistration().value().defaultClock().orElse(null);
        if (clock == null) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "this dimension has no default clock");
        }
        ServerClockManager clocks = level.getServer().clockManager();
        ResourceKey<ClockTimeMarker> marker = switch (raw) {
            case "day" -> ClockTimeMarkers.DAY;
            case "noon" -> ClockTimeMarkers.NOON;
            case "night" -> ClockTimeMarkers.NIGHT;
            case "midnight" -> ClockTimeMarkers.MIDNIGHT;
            default -> null;
        };
        if (marker != null) {
            // 26.3 turned the boolean into MoveResult so "already there" and "no such marker" stop looking alike.
            ServerClockManager.MoveResult moved = clocks.moveToTimeMarker(clock, marker);
            if (moved == ServerClockManager.MoveResult.NO_TIME_MARKER_FOUND) {
                return ServerActionResult.of(ServerActionResult.Status.FAILED, "time marker is not defined: " + raw);
            }
            // MOVED and NOT_MOVED (already at that marker) both leave the clock where the caller asked for.
            return ServerActionResult.ok("time set to " + raw);
        }
        Long ticks = parseTicks(raw);
        if (ticks == null) {
            return ServerActionResult.of(ServerActionResult.Status.INVALID, "time must be day/noon/night/midnight/ticks:<n>");
        }
        clocks.setTotalTicks(clock, ticks);
        return ServerActionResult.ok("time set to " + ticks);
    }

    /**
     * 26.1 moved the weather cycle into {@code WeatherData} and the write path onto the server: the vanilla
     * {@code /weather} command calls {@code MinecraftServer.setWeatherParameters}, which is the same four-slot
     * mutation the level used to offer and is still the one call that also syncs it to every client.
     */
    private static ServerActionResult setWeather(ServerPlayer player, Map<String, String> params) {
        String weather = params.getOrDefault("value", "");
        ServerLevel level = player.level();
        if (level.getServer() == null) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "server is not available");
        }
        MinecraftServer server = level.getServer();
        switch (weather) {
            case "clear" -> server.setWeatherParameters(12_000, 0, false, false);
            case "rain" -> server.setWeatherParameters(0, 12_000, true, false);
            case "thunder" -> server.setWeatherParameters(0, 12_000, true, true);
            default -> {
                return ServerActionResult.of(ServerActionResult.Status.INVALID, "weather must be clear/rain/thunder");
            }
        }
        return ServerActionResult.ok("weather set to " + weather);
    }

    private static ServerActionResult spawnEntity(ServerPlayer player, Map<String, String> params) {
        Identifier id = Identifier.tryParse(params.getOrDefault("type", ""));
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
            entity.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
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
        if (player.level().getServer() == null) {
            return ServerActionResult.of(ServerActionResult.Status.FAILED, "server is not available");
        }
        player.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal(text), false);
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

    /**
     * The raw-tick form of the time parameter. The named forms are clock markers now, not tick literals: with world
     * clocks the offset of "noon" is a property of the dimension's timeline, so the request no longer carries one.
     */
    private static Long parseTicks(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.startsWith("ticks:")) {
            return null;
        }
        try {
            long ticks = Long.parseLong(trimmed.substring("ticks:".length()).trim());
            return ticks >= 0L && ticks <= 24_000L ? ticks : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
