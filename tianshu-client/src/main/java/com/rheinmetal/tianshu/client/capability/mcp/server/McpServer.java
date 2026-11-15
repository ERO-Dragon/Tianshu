package com.rheinmetal.tianshu.client.capability.mcp.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A local, read-only MCP endpoint: an external agent can list and read skills, resources and prompts from the
 * capability table. It never exposes game tools — those need a real player and the in-game policy chain.
 *
 * <p>Bound to loopback only and disabled by default; enabling it is an explicit config choice.</p>
 */
public final class McpServer implements AutoCloseable {

    private static final String READ_TOOL = "read_capability";
    // JSON-RPC 2.0 codes. -32700 is "the request was not JSON"; -32602 is "the JSON was fine but these parameters are
    // not"; -32000 is the implementation-defined server-error space.
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int INVALID_PARAMS = -32602;
    private static final int SERVER_ERROR = -32000;

    private final HttpServer http;
    private final LogSink logs;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tianshu-mcp-server");
        thread.setDaemon(true);
        return thread;
    });

    private McpServer(HttpServer http, LogSink logs) {
        this.http = http;
        this.logs = logs == null ? LogSink.SYSTEM : logs;
    }

    public static McpServer start(int port, LogSink logs) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        McpServer server = new McpServer(http, logs);
        http.createContext("/mcp", server::handle);
        http.setExecutor(server.executor);
        http.start();
        return server;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        http.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonElement id = null;
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(body);
            } catch (JsonParseException malformed) {
                // The only case that may answer "malformed JSON": nothing could be read out of the body at all, so
                // there is no id to attach the error to either.
                respond(exchange, error(null, PARSE_ERROR, "malformed JSON: " + malformed.getMessage()));
                return;
            }
            if (!parsed.isJsonObject()) {
                respond(exchange, error(null, INVALID_REQUEST, "invalid request"));
                return;
            }
            JsonObject request = parsed.getAsJsonObject();
            id = request.has("id") ? request.get("id") : null;
            if (id == null) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            String method = primitive(request, "method", "");
            JsonObject params = request.has("params") && request.get("params").isJsonObject()
                    ? request.getAsJsonObject("params") : new JsonObject();
            respond(exchange, success(id, dispatch(method, params)));
        } catch (CompletionException failed) {
            // A capability that failed or timed out. This used to arrive as -32700 "malformed JSON" with no id, so
            // the caller was told its JSON was wrong and could not even match the answer to its request.
            respondQuietly(exchange, failureResponse(id, failed));
        } catch (JsonParseException unexpected) {
            respondQuietly(exchange, failureResponse(id, unexpected));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            respondQuietly(exchange, error(id, INVALID_PARAMS, String.valueOf(invalid.getMessage())));
        } catch (RuntimeException unexpected) {
            logs.error("mcp server failed to handle " + (id == null ? "a request" : "request " + id), unexpected);
            respondQuietly(exchange, failureResponse(id, unexpected));
        }
    }

    /**
     * The reply for a failure that is not the caller's fault: a server error carried by the request id.
     *
     * <p>Package-visible so the classification can be tested without waiting out a capability timeout; that branch is
     * what used to answer "malformed JSON" with no id at all.</p>
     */
    static JsonObject failureResponse(JsonElement id, Throwable failure) {
        return error(id, SERVER_ERROR, describe(failure));
    }

    /** Answers a request whose body already went wrong; a failure to write is not worth a second exception. */
    private void respondQuietly(HttpExchange exchange, JsonObject payload) {
        try {
            respond(exchange, payload);
        } catch (IOException writeFailure) {
            logs.warn("mcp server could not write a response", writeFailure);
        }
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof TimeoutException) {
            return "the capability did not answer in time";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /**
     * Reads one optional string parameter.
     *
     * <p>Used for every parameter instead of {@code get("x").getAsString()}: a client that sends an object where a
     * string belongs then reads as "this parameter is missing" (and gets -32602 from the normal validation) instead of
     * throwing an {@code UnsupportedOperationException} that had to be classified as a server error.</p>
     */
    private static String primitive(JsonObject object, String key, String fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    /** The text of a capability envelope: its content, or the failure message when it has none. */
    private static String textOf(JsonObject content) {
        return content.has("text") ? primitive(content, "text", "") : primitive(content, "message", "");
    }

    private JsonObject dispatch(String method, JsonObject params) {
        return switch (method) {
            case "initialize" -> initialize();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            case "resources/list" -> resourcesList();
            case "resources/read" -> resourcesRead(params);
            case "prompts/list" -> promptsList();
            case "prompts/get" -> promptsGet(params);
            default -> throw new IllegalArgumentException("unknown method: " + method);
        };
    }

    private static JsonObject initialize() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");
        JsonObject info = new JsonObject();
        info.addProperty("name", "Tianshu");
        info.addProperty("version", "1.0");
        result.add("serverInfo", info);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        capabilities.add("resources", new JsonObject());
        capabilities.add("prompts", new JsonObject());
        result.add("capabilities", capabilities);
        return result;
    }

    private static JsonObject toolsList() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description", "Capability id, e.g. tianshu:game-basics");
        properties.add("id", id);
        JsonObject page = new JsonObject();
        page.addProperty("type", "integer");
        properties.add("page", page);
        schema.add("properties", properties);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", READ_TOOL);
        tool.addProperty("description", "Reads a skill, resource or prompt from Tianshu's capability table.");
        tool.add("inputSchema", schema);
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();
        tools.add(tool);
        result.add("tools", tools);
        return result;
    }

    private JsonObject toolsCall(JsonObject params) {
        String name = primitive(params, "name", "");
        if (!READ_TOOL.equals(name)) {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        JsonObject content = invoke(arguments);
        boolean failed = !content.has("ok") || !content.get("ok").getAsBoolean();
        JsonObject result = new JsonObject();
        JsonArray items = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", textOf(content));
        items.add(item);
        result.add("content", items);
        result.addProperty("isError", failed);
        return result;
    }

    private JsonObject resourcesList() {
        JsonArray resources = new JsonArray();
        for (CapabilityDescriptor descriptor : CapabilityCatalog.list(CapabilityKind.RESOURCE)) {
            JsonObject item = new JsonObject();
            item.addProperty("uri", uriOf(descriptor));
            item.addProperty("name", descriptor.id().toString());
            item.addProperty("description", descriptor.description());
            item.addProperty("mimeType", "text/markdown");
            resources.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("resources", resources);
        return result;
    }

    private JsonObject resourcesRead(JsonObject params) {
        String uri = primitive(params, "uri", "");
        CapabilityId id = idFromUri(uri);
        JsonObject content = invoke(id, new JsonObject());
        JsonObject item = new JsonObject();
        item.addProperty("uri", uri);
        item.addProperty("text", textOf(content));
        JsonArray contents = new JsonArray();
        contents.add(item);
        JsonObject result = new JsonObject();
        result.add("contents", contents);
        return result;
    }

    private JsonObject promptsList() {
        JsonArray prompts = new JsonArray();
        for (CapabilityDescriptor descriptor : CapabilityCatalog.list(CapabilityKind.PROMPT)) {
            JsonObject item = new JsonObject();
            item.addProperty("name", descriptor.id().toString());
            item.addProperty("description", descriptor.description());
            prompts.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("prompts", prompts);
        return result;
    }

    private JsonObject promptsGet(JsonObject params) {
        String name = primitive(params, "name", "");
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        JsonObject content = invoke(CapabilityId.parse(name), arguments);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", textOf(content));
        message.add("content", text);
        JsonArray messages = new JsonArray();
        messages.add(message);
        JsonObject result = new JsonObject();
        result.add("messages", messages);
        return result;
    }

    private JsonObject invoke(JsonObject arguments) {
        String id = primitive(arguments, "id", "");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return invoke(CapabilityId.parse(id), arguments);
    }

    private JsonObject invoke(CapabilityId id, JsonObject arguments) {
        CapabilityDescriptor descriptor = CapabilityCatalog.find(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown capability: " + id));
        if (descriptor.kind().equals(CapabilityKind.TOOL)) {
            throw new IllegalArgumentException("tools are not readable through MCP: " + id);
        }
        return CapabilityCatalog.invoke(id, arguments, ToolCaller.mod("mcp"), ToolSide.CLIENT)
                .orTimeout(15, TimeUnit.SECONDS).join();
    }

    private static String uriOf(CapabilityDescriptor descriptor) {
        return "capability://" + descriptor.id();
    }

    private static CapabilityId idFromUri(String uri) {
        String prefix = "capability://";
        if (!uri.startsWith(prefix)) {
            throw new IllegalArgumentException("uri must start with " + prefix);
        }
        return CapabilityId.parse(uri.substring(prefix.length()));
    }

    private static JsonObject success(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) {
            response.add("id", id);
        }
        response.add("error", error);
        return response;
    }

    private static void respond(HttpExchange exchange, JsonObject payload) throws IOException {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
