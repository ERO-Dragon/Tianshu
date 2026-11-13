package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server half of the tool channel, independent of the loader and the packet shape.
 *
 * <p>Adapters only decode a packet, build a {@link ToolCaller} from the real player and send the returned reply
 * back; parsing, permission, throttling, execution, the envelope and idempotency live here once instead of in 40
 * near-identical handlers.</p>
 *
 * <p>A {@code (player, requestId)} pair is executed once: a retried request returns the same reply for a short
 * window, so a lost answer cannot run a world change twice.</p>
 */
public final class ServerToolDispatch {

    private static final long REPLAY_WINDOW_NANOS = 120_000_000_000L;
    private static final int MAX_CACHED_REPLIES = 512;

    /** What the adapter puts on the wire; already fitted to {@link ToolWireLimits}. */
    public record Reply(String requestId, boolean ok, String valueJson, String errorCode, String message) {

        static Reply of(String requestId, boolean ok, String valueJson, String errorCode, String message) {
            ToolWireLimits.Fitted fitted = ToolWireLimits.fitResult(ok, valueJson, errorCode, message);
            return new Reply(ToolWireLimits.clamp(requestId, ToolWireLimits.MAX_REQUEST_ID), fitted.ok(),
                    fitted.valueJson(), fitted.errorCode(), fitted.message());
        }

        static Reply failure(String requestId, ToolError code, String message) {
            return of(requestId, false, "", code.name(), message);
        }
    }

    private record Cached(Reply reply, long nanos) {
    }

    private static final Map<String, Cached> COMPLETED = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Reply>> IN_FLIGHT = new ConcurrentHashMap<>();

    private ServerToolDispatch() {
    }

    public static CompletableFuture<Reply> respond(ToolCaller caller, String requestId, String toolId,
                                                   String argsJson) {
        String key = caller.id() + "|" + (requestId == null ? "" : requestId);
        Reply replay = completed(key);
        if (replay != null) {
            return CompletableFuture.completedFuture(replay);
        }
        CompletableFuture<Reply> running = IN_FLIGHT.get(key);
        if (running != null) {
            return running;
        }
        CompletableFuture<Reply> future = execute(caller, requestId, toolId, argsJson);
        CompletableFuture<Reply> previous = IN_FLIGHT.putIfAbsent(key, future);
        if (previous != null) {
            return previous;
        }
        future.whenComplete((reply, failure) -> {
            IN_FLIGHT.remove(key, future);
            if (reply != null) {
                remember(key, reply);
            }
        });
        return future;
    }

    /** Tests and a server restart use this; the replay window is not meant to outlive the session. */
    public static void clear() {
        COMPLETED.clear();
        IN_FLIGHT.clear();
    }

    private static CompletableFuture<Reply> execute(ToolCaller caller, String requestId, String toolId,
                                                    String argsJson) {
        ToolId id;
        try {
            id = ToolId.parse(toolId);
        } catch (RuntimeException malformed) {
            return CompletableFuture.completedFuture(
                    Reply.failure(requestId, ToolError.UNKNOWN_TOOL, "malformed tool id"));
        }
        JsonElement args;
        try {
            args = JsonParser.parseString(argsJson == null ? "{}" : argsJson);
        } catch (RuntimeException malformed) {
            return CompletableFuture.completedFuture(
                    Reply.failure(requestId, ToolError.INVALID_ARGS, "malformed arguments"));
        }
        try {
            return TianshuTools.callJson(id, args, caller, ToolSide.SERVER)
                    .handle((envelope, failure) -> failure == null
                            ? fromEnvelope(requestId, envelope)
                            : Reply.failure(requestId, ToolError.FAILED, describe(failure)));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(Reply.failure(requestId, ToolError.FAILED, describe(failure)));
        }
    }

    private static Reply fromEnvelope(String requestId, JsonObject envelope) {
        if (envelope == null) {
            return Reply.failure(requestId, ToolError.FAILED, "the tool produced no answer");
        }
        if (envelope.has("ok") && envelope.get("ok").getAsBoolean()) {
            JsonElement result = envelope.get("result");
            return Reply.of(requestId, true, result == null ? "null" : result.toString(), "", "");
        }
        String code = envelope.has("code") ? envelope.get("code").getAsString() : ToolError.FAILED.name();
        String message = envelope.has("message") ? envelope.get("message").getAsString() : code;
        // TianshuTools already turns a thrown failure into an envelope; keep only its first line so a stack
        // never reaches the model or the client.
        return Reply.of(requestId, false, "", code, firstLine(message));
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        String line = message.split("\n", 2)[0];
        return ToolWireLimits.clamp(line, 200);
    }

    /** One line, no stack: the model and the client only need to know what class of failure it was. */
    private static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        String firstLine = message.split("\n", 2)[0];
        return cause.getClass().getSimpleName() + ": " + ToolWireLimits.clamp(firstLine, 200);
    }

    private static Reply completed(String key) {
        Cached cached = COMPLETED.get(key);
        if (cached == null) {
            return null;
        }
        if (System.nanoTime() - cached.nanos() > REPLAY_WINDOW_NANOS) {
            COMPLETED.remove(key, cached);
            return null;
        }
        return cached.reply();
    }

    private static void remember(String key, Reply reply) {
        COMPLETED.put(key, new Cached(reply, System.nanoTime()));
        if (COMPLETED.size() > MAX_CACHED_REPLIES) {
            long now = System.nanoTime();
            COMPLETED.entrySet().removeIf(entry -> now - entry.getValue().nanos() > REPLAY_WINDOW_NANOS);
        }
    }
}
