package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.api.server.ClientServerActionPort;
import com.rheinmetal.tianshu.api.server.ServerActionResult;
import com.rheinmetal.tianshu.neoforge.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.neoforge.network.ServerActionRequestPacket;
import com.rheinmetal.tianshu.neoforge.network.ServerActionResultPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Sends action requests to the optional server component and matches the answers by request id.
 *
 * <p>A request that cannot be sent, or whose answer never arrives, completes with a {@code FAILED} result instead of
 * hanging forever. The future is the only client-side state; nothing is applied locally.</p>
 */
public final class NeoForgeServerActionClient implements ClientServerActionPort {

    private static final long ANSWER_TIMEOUT_SECONDS = 5L;
    private static final Map<String, CompletableFuture<ServerActionResult>> PENDING = new ConcurrentHashMap<>();
    private static volatile Set<String> enabledActions = Set.of();
    private static volatile boolean catalogReceived;

    @Override
    public Set<String> availableActions() {
        return enabledActions;
    }

    @Override
    public boolean serverActionsAvailable() {
        return catalogReceived;
    }

    @Override
    public CompletableFuture<ServerActionResult> giveItem(String itemId, int count) {
        return send("give_item", Map.of("item", safe(itemId), "count", Integer.toString(count)));
    }

    @Override
    public CompletableFuture<ServerActionResult> teleport(double x, double y, double z) {
        return send("teleport", Map.of("x", Double.toString(x), "y", Double.toString(y), "z", Double.toString(z)));
    }

    @Override
    public CompletableFuture<ServerActionResult> setTime(String time) {
        return send("set_time", Map.of("value", safe(time)));
    }

    @Override
    public CompletableFuture<ServerActionResult> setWeather(String weather) {
        return send("set_weather", Map.of("value", safe(weather)));
    }

    @Override
    public CompletableFuture<ServerActionResult> spawnEntity(String entityTypeId, int count) {
        return send("spawn_entity", Map.of("type", safe(entityTypeId), "count", Integer.toString(count)));
    }

    @Override
    public CompletableFuture<ServerActionResult> broadcast(String text) {
        return send("broadcast", Map.of("text", safe(text)));
    }

    /** The client half of the result payload; runs on the client thread. */
    public static void handleResult(ServerActionResultPacket packet, IPayloadContext context) {
        CompletableFuture<ServerActionResult> pending = PENDING.remove(packet.requestId());
        if (pending == null) {
            return;
        }
        context.enqueueWork(() -> pending.complete(new ServerActionResult(packet.status(), packet.message())));
    }

    /** The server's action catalog for this session. */
    public static void handleCatalog(ServerActionCatalogPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            enabledActions = Set.copyOf(packet.enabledActions());
            catalogReceived = true;
        });
    }

    /** Leaving the world drops the catalog and fails any request whose answer can no longer arrive. */
    public static void reset() {
        enabledActions = Set.of();
        catalogReceived = false;
        PENDING.forEach((requestId, future) -> future.complete(
                ServerActionResult.of(ServerActionResult.Status.FAILED, "disconnected before the server answered")));
        PENDING.clear();
    }

    private CompletableFuture<ServerActionResult> send(String kind, Map<String, String> params) {
        if (Minecraft.getInstance() == null || Minecraft.getInstance().getConnection() == null) {
            return CompletableFuture.completedFuture(
                    ServerActionResult.of(ServerActionResult.Status.FAILED, "not connected to a server"));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<ServerActionResult> future = new CompletableFuture<>();
        PENDING.put(requestId, future);
        try {
            PacketDistributor.sendToServer(new ServerActionRequestPacket(requestId, kind, params));
        } catch (RuntimeException failure) {
            PENDING.remove(requestId);
            return CompletableFuture.completedFuture(
                    ServerActionResult.of(ServerActionResult.Status.FAILED, "request could not be sent"));
        }
        CompletableFuture<ServerActionResult> bounded = future
                .orTimeout(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(failure -> ServerActionResult.of(
                        ServerActionResult.Status.FAILED, "the server did not answer"));
        bounded.whenComplete((result, failure) -> PENDING.remove(requestId, future));
        return bounded;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
