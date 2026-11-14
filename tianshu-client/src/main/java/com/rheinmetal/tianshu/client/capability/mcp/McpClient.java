package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal MCP client over streamable HTTP. It performs the standard handshake, lists tools and calls one;
 * resources and prompts can be added on the same transport later.
 *
 * <p>The client is deliberately synchronous and dependency-free (JDK HTTP + Gson): callers run it off the game
 * thread. An error is an exception, not a half-initialized session.</p>
 */
public final class McpClient implements AutoCloseable {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicLong ids = new AtomicLong();
    private volatile String sessionId;

    public McpClient(String url) {
        this.endpoint = URI.create(url);
    }

    public void initialize() throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());
        JsonObject client = new JsonObject();
        client.addProperty("name", "Tianshu");
        client.addProperty("version", "1.0");
        params.add("clientInfo", client);
        post("initialize", params, true);
        post("notifications/initialized", null, false);
    }

    public List<JsonObject> listTools() throws IOException, InterruptedException {
        JsonObject result = post("tools/list", new JsonObject(), true);
        List<JsonObject> tools = new ArrayList<>();
        if (result != null && result.has("tools") && result.get("tools").isJsonArray()) {
            JsonArray array = result.getAsJsonArray("tools");
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    tools.add(element.getAsJsonObject());
                }
            }
        }
        return tools;
    }

    public JsonObject callTool(String name, JsonObject arguments) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments == null ? new JsonObject() : arguments);
        return post("tools/call", params, true);
    }

    @Override
    public void close() {
        // HTTP has no live connection to release; the session ends when the client is dropped.
    }

    private JsonObject post(String method, JsonObject params, boolean expectsResult)
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8));
        String session = sessionId;
        if (session != null) {
            builder.header("Mcp-Session-Id", session);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
        if (response.statusCode() >= 300) {
            throw new IOException("MCP " + method + " answered HTTP " + response.statusCode());
        }
        if (!expectsResult || response.body().isBlank()) {
            return null;
        }
        JsonElement payload = parsePayload(response);
        if (payload == null || !payload.isJsonObject()) {
            throw new IOException("MCP " + method + " returned no JSON object");
        }
        JsonObject object = payload.getAsJsonObject();
        if (object.has("error")) {
            JsonObject error = object.getAsJsonObject("error");
            throw new IOException("MCP " + method + " failed: "
                    + (error.has("message") ? error.get("message").getAsString() : error.toString()));
        }
        return object.has("result") && object.get("result").isJsonObject()
                ? object.getAsJsonObject("result") : new JsonObject();
    }

    /** Streamable HTTP may answer JSON or a single SSE frame; accept both. */
    private static JsonElement parsePayload(HttpResponse<String> response) {
        String body = response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/event-stream")) {
            for (String line : body.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("data:")) {
                    String data = trimmed.substring(5).strip();
                    if (!data.isEmpty() && !"[DONE]".equals(data)) {
                        return JsonParser.parseString(data);
                    }
                }
            }
            return null;
        }
        return JsonParser.parseString(body);
    }
}
