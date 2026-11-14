package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonElement;

import java.util.concurrent.CompletableFuture;

/**
 * The bridge that a server-side tool travels over when the caller is on the client.
 *
 * <p>The platform installs one implementation when the server component is present; when it is absent the
 * registry answers {@link ToolError#UNSUPPORTED_SIDE} without trying. Keeping this as a port is what lets the
 * registry live in the platform-neutral API while the transport stays in the adapter.</p>
 */
public interface ServerToolChannel {

    /**
     * Whether a connection exists right now. The registry hides server tools while this is false, and answers
     * {@link ToolError#UNSUPPORTED_SIDE} for them, so a channel can stay installed across sessions.
     */
    default boolean available() {
        return true;
    }

    /**
     * Sends one call to the server component.
     *
     * @param id      the tool id
     * @param args    the JSON argument object
     * @param caller  who is asking
     * @param timeoutMillis the budget the registry already decided on
     * @return the JSON result, or a future failed with a {@link ToolException}
     */
    CompletableFuture<JsonElement> call(ToolId id, JsonElement args, ToolCaller caller, long timeoutMillis);

    /** Same call with a caller-chosen id, so it can be cancelled later. */
    default CompletableFuture<JsonElement> call(String requestId, ToolId id, JsonElement args, ToolCaller caller,
                                                long timeoutMillis) {
        return call(id, args, caller, timeoutMillis);
    }

    /** Best effort; true when a pending call was found and failed with {@link ToolError#CANCELLED}. */
    default boolean cancel(String requestId) {
        return false;
    }
}