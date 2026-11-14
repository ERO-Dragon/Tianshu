package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The JSON-RPC side of MCP: handshake, method names, result unwrapping and error mapping. A transport only has to
 * move one request object and one response object.
 */
abstract class AbstractMcpClient implements McpConnection {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final AtomicLong ids = new AtomicLong();

    @Override
    public final void initialize() throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());
        JsonObject client = new JsonObject();
        client.addProperty("name", "Tianshu");
        client.addProperty("version", "1.0");
        params.add("clientInfo", client);
        request("initialize", params, true);
        notify("notifications/initialized");
    }

    @Override
    public final List<JsonObject> listTools() throws IOException, InterruptedException {
        return objects(request("tools/list", new JsonObject(), true), "tools");
    }

    @Override
    public final List<JsonObject> listResources() throws IOException, InterruptedException {
        return objects(request("resources/list", new JsonObject(), true), "resources");
    }

    @Override
    public final JsonObject readResource(String uri) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        return request("resources/read", params, true);
    }

    @Override
    public final List<JsonObject> listPrompts() throws IOException, InterruptedException {
        return objects(request("prompts/list", new JsonObject(), true), "prompts");
    }

    @Override
    public final JsonObject getPrompt(String name, JsonObject arguments) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments == null ? new JsonObject() : arguments);
        return request("prompts/get", params, true);
    }

    @Override
    public final JsonObject callTool(String name, JsonObject arguments) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments == null ? new JsonObject() : arguments);
        return request("tools/call", params, true);
    }

    /** Sends one request and returns its {@code result}; a JSON-RPC error becomes an exception. */
    protected final JsonObject request(String method, JsonObject params, boolean expectsResult)
            throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        if (expectsResult) {
            request.addProperty("id", ids.incrementAndGet());
        }
        request.addProperty("method", method);
        if (params != null) {
            request.add("params", params);
        }
        JsonObject response = exchange(request);
        if (!expectsResult || response == null) {
            return new JsonObject();
        }
        if (response.has("error")) {
            JsonObject error = response.getAsJsonObject("error");
            throw new McpRemoteException("MCP " + method + " failed: "
                    + (error.has("message") ? error.get("message").getAsString() : error.toString()));
        }
        return response.has("result") && response.get("result").isJsonObject()
                ? response.getAsJsonObject("result") : new JsonObject();
    }

    protected final void notify(String method) throws IOException, InterruptedException {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", method);
        sendNotification(notification);
    }

    /** Sends one request and returns the decoded response object (or {@code null} for a notification). */
    protected abstract JsonObject exchange(JsonObject request) throws IOException, InterruptedException;

    protected abstract void sendNotification(JsonObject notification) throws IOException, InterruptedException;

    private static List<JsonObject> objects(JsonObject result, String key) {
        List<JsonObject> values = new ArrayList<>();
        if (result != null && result.has(key) && result.get(key).isJsonArray()) {
            JsonArray array = result.getAsJsonArray(key);
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    values.add(element.getAsJsonObject());
                }
            }
        }
        return values;
    }
}
