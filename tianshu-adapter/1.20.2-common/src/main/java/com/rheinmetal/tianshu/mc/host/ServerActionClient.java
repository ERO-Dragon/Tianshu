package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.api.server.ClientServerActionPort;
import com.rheinmetal.tianshu.api.server.ServerActionResult;
import com.rheinmetal.tianshu.mc.event.ServerActionStatus;
import com.rheinmetal.tianshu.mc.network.PayloadSender;
import com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionResultPacket;

import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Sends action requests to the optional server component and matches the answers by request id.
 *
 * <p>A request that cannot be sent, or whose answer never arrives, completes with a {@code FAILED} result instead of
 * hanging forever. The future is the only client-side state; nothing is applied locally.</p>
 *
 * <p>One instance per session belongs to the composition root: the network registration delivers answers to it and
 * the event layer reads its snapshots, so neither side needs a static of its own.</p>
 */
public final class ServerActionClient implements ClientServerActionPort, ServerActionStatus {

    private static final long ANSWER_TIMEOUT_SECONDS = 5L;

    private final PayloadSender sender;
    private final Map<String, CompletableFuture<ServerActionResult>> pending = new ConcurrentHashMap<>();
    private volatile Set<String> enabledActions = Set.of();
    private volatile boolean catalogReceived;

    public ServerActionClient(PayloadSender sender) {
        this.sender = Objects.requireNonNull(sender, "sender");
    }

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

    /**
     * The client half of the result payload.
     *
     * @param clientThread how this loader runs work on the client thread (the payload arrives on the network thread)
     */
    public void receiveResult(ServerActionResultPacket packet, Consumer<Runnable> clientThread) {
        CompletableFuture<ServerActionResult> awaiting = pending.remove(packet.requestId());
        if (awaiting == null) {
            return;
        }
        ServerActionResult result = new ServerActionResult(packet.status(), packet.message());
        clientThread.accept(() -> awaiting.complete(result));
    }

    /** The server's action catalog for this session. */
    public void receiveCatalog(ServerActionCatalogPacket packet, Consumer<Runnable> clientThread) {
        Set<String> actions = Set.copyOf(packet.enabledActions());
        clientThread.accept(() -> {
            enabledActions = actions;
            catalogReceived = true;
        });
    }

    @Override
    public String enabledActionsSnapshot() {
        return enabledActions.toString();
    }

    @Override
    public boolean catalogReceivedSnapshot() {
        return catalogReceived;
    }

    /** Leaving the world drops the catalog and fails any request whose answer can no longer arrive. */
    @Override
    public void reset() {
        enabledActions = Set.of();
        catalogReceived = false;
        pending.forEach((requestId, future) -> future.complete(
                ServerActionResult.of(ServerActionResult.Status.FAILED, "disconnected before the server answered")));
        pending.clear();
    }

    private CompletableFuture<ServerActionResult> send(String kind, Map<String, String> params) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return CompletableFuture.completedFuture(
                    ServerActionResult.of(ServerActionResult.Status.FAILED, "not connected to a server"));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<ServerActionResult> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            sender.toServer(new ServerActionRequestPacket(requestId, kind, params));
        } catch (RuntimeException failure) {
            pending.remove(requestId);
            return CompletableFuture.completedFuture(
                    ServerActionResult.of(ServerActionResult.Status.FAILED, "request could not be sent"));
        }
        CompletableFuture<ServerActionResult> bounded = future
                .orTimeout(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(failure -> ServerActionResult.of(
                        ServerActionResult.Status.FAILED, "the server did not answer"));
        bounded.whenComplete((result, failure) -> pending.remove(requestId, future));
        return bounded;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
