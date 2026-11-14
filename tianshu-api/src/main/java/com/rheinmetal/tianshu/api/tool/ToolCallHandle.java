package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * One in-flight JSON tool call. The request id is what the platform cancels by; local tools have no id and a
 * cancellation is simply a no-op on the already running future.
 */
public interface ToolCallHandle {

    String requestId();

    CompletableFuture<JsonObject> result();

    /** Best effort: the server is notified, but a tool already running on the server thread may finish anyway. */
    boolean cancel();
}
