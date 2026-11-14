package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.ServerToolChannel;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolTransport;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The bridge from the registry to the server component.
 *
 * <p>It is installed once per process and stays installed: {@link #available()} asks the transport whether a
 * connection exists right now, so leaving a world or joining one without the server component simply makes server
 * tools invisible instead of leaving a stale channel behind.</p>
 *
 * <p>The platform's transport calls {@link #complete} / {@link #fail} when the answer arrives; the pending map is
 * the client half of the correlation.</p>
 */
public final class ConnectionToolChannel implements ServerToolChannel {

    private static final ConnectionToolChannel INSTANCE = new ConnectionToolChannel();
    private static final int MAX_PENDING = 64;

    private final Map<String, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();

    private ConnectionToolChannel() {
    }

    /** Installs the channel on the tool registry. Safe to call more than once. */
    public static void install() {
        TianshuTools.installServerChannel(INSTANCE);
    }

    /** Drops the channel and fails every pending call with {@code UNSUPPORTED_SIDE}. */
    public static void uninstall() {
        INSTANCE.failAll(new ToolException(ToolError.UNSUPPORTED_SIDE, "the server connection is closed"));
        TianshuTools.uninstallServerChannel();
    }

    /** The platform hands one result back. Unknown ids are ignored: they already timed out or were cancelled. */
    public static void complete(String requestId, JsonElement result) {
        INSTANCE.completePending(requestId, result);
    }

    /** The platform reports a failed call (refused, malformed, transport error). */
    public static void fail(String requestId, ToolError code, String message) {
        INSTANCE.failPending(requestId, code, message);
    }

    @Override
    public boolean available() {
        return TianshuRuntimePorts.find(ToolTransport.class).map(ToolTransport::available).orElse(false);
    }

    @Override
    public CompletableFuture<JsonElement> call(ToolId id, JsonElement args, ToolCaller caller, long timeoutMillis) {
        return call("tool-" + UUID.randomUUID(), id, args, caller, timeoutMillis);
    }

    @Override
    public CompletableFuture<JsonElement> call(String requestId, ToolId id, JsonElement args, ToolCaller caller,
                                               long timeoutMillis) {
        Optional<ToolTransport> transport = TianshuRuntimePorts.find(ToolTransport.class);
        if (transport.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.UNSUPPORTED_SIDE, "tool " + id + " needs the server component"));
        }
        if (pending.size() >= MAX_PENDING) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.FAILED, "too many tool calls are waiting for the server"));
        }
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        pending.put(requestId, future);
        future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((result, failure) -> pending.remove(requestId));
        try {
            transport.get().send(requestId, id, args, caller, timeoutMillis);
        } catch (RuntimeException failure) {
            pending.remove(requestId);
            future.completeExceptionally(
                    new ToolException(ToolError.FAILED, "could not send the tool request", failure));
        }
        return future;
    }

    @Override
    public boolean cancel(String requestId) {
        CompletableFuture<JsonElement> future = requestId == null ? null : pending.remove(requestId);
        if (future == null) {
            return false;
        }
        future.completeExceptionally(new ToolException(ToolError.CANCELLED, "the call was cancelled"));
        Optional<ToolTransport> transport = TianshuRuntimePorts.find(ToolTransport.class);
        if (transport.isEmpty()) {
            return true;
        }
        JsonObject args = new JsonObject();
        args.addProperty("requestId", requestId);
        try {
            transport.get().send("cancel-" + UUID.randomUUID(), ToolId.of("tianshu", "tool.cancel"), args,
                    ToolCaller.mod("tianshu"), 2_000L);
        } catch (RuntimeException ignored) {
            // The local future is already cancelled; the server simply keeps doing its best-effort work.
        }
        return true;
    }

    private void completePending(String requestId, JsonElement result) {
        CompletableFuture<JsonElement> future = requestId == null ? null : pending.remove(requestId);
        if (future != null) {
            future.complete(result);
        }
    }

    private void failPending(String requestId, ToolError code, String message) {
        CompletableFuture<JsonElement> future = requestId == null ? null : pending.remove(requestId);
        if (future != null) {
            future.completeExceptionally(new ToolException(code, message == null ? code.name() : message));
        }
    }

    private void failAll(ToolException failure) {
        for (String requestId : pending.keySet()) {
            CompletableFuture<JsonElement> future = pending.remove(requestId);
            if (future != null) {
                future.completeExceptionally(failure);
            }
        }
    }
}