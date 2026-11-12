package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonElement;

/**
 * The platform's wire to the server component, installed through {@code TianshuRuntimePorts}.
 *
 * <p>It carries one call and no policy: the registry decides whether a server tool may run, the transport only
 * moves the request. Results come back through the client channel ({@code ConnectionToolChannel}) rather than a
 * return value, because the answer arrives on another thread.</p>
 */
public interface ToolTransport {

    /** Whether the transport currently reaches a server component. */
    default boolean available() {
        return true;
    }

    void send(String requestId, ToolId id, JsonElement args, ToolCaller caller, long timeoutMillis);
}
