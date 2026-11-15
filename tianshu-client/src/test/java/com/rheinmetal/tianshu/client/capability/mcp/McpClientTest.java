package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real HTTP round-trip against a local fake MCP server, plus the tool bridge. */
class McpClientTest {

    private HttpServer server;
    private String url;
    private final AtomicBoolean sessionSeen = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger initializations =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Emits a tools/list entry whose {@code name} is an object: legal JSON, unusable as a tool. */
    private volatile boolean malformedToolList;
    /** Answers tools/call with HTTP 400 plus a JSON-RPC error: the server refusing one call, not a dead connection. */
    private volatile boolean rejectCallsWith400;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void handshakeListAndCallOverHttp() throws Exception {
        McpHttpClient client = new McpHttpClient(url);
        client.initialize();
        List<JsonObject> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).get("name").getAsString());

        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", "hi");
        JsonObject result = client.callTool("echo", arguments);
        assertEquals("hello", result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString());
        assertTrue(sessionSeen.get(), "the client must reuse the session id it was given");
    }

    @Test
    void anUnreachableServerIsReportedInsteadOfThrowing() {
        McpBridge bridge = McpBridge.install(List.of(
                McpServerSpec.http("down", "http://127.0.0.1:1/mcp", ToolAutonomy.FULL)));
        try {
            assertEquals(1, bridge.status().size());
            assertFalse(bridge.status().get(0).connected());
            assertTrue(!bridge.status().get(0).lastError().isBlank());
            assertEquals(0, bridge.toolCount());
        } finally {
            bridge.close();
        }
    }

    @Test
    void reconnectingOneServerLeavesTheOtherAlone() throws Exception {
        McpBridge bridge = McpBridge.install(List.of(
                McpServerSpec.http("a", url, ToolAutonomy.FULL),
                McpServerSpec.http("b", url, ToolAutonomy.FULL)));
        try {
            assertEquals(2, initializations.get());
            int before = initializations.get();

            bridge.reconnect("a");
            for (int attempt = 0; attempt < 100 && initializations.get() == before; attempt++) {
                Thread.sleep(10);
            }
            Thread.sleep(200);

            assertEquals(before + 1, initializations.get(), "only the named server should reconnect");
            assertTrue(bridge.status().stream().allMatch(McpServerStatus::connected));
        } finally {
            bridge.close();
        }
    }

    @Test
    void aFailedResourceReadRebuildsTheConnection() throws Exception {
        McpBridge bridge = McpBridge.install(List.of(McpServerSpec.http("srv", url, ToolAutonomy.CONSERVATIVE)));
        try {
            int before = initializations.get();

            JsonObject envelope = CapabilityCatalog.invoke(McpBridge.capabilityId("srv", "res", "broken"),
                            new JsonObject(), ToolCaller.agent(false), ToolSide.CLIENT)
                    .get(5, TimeUnit.SECONDS);
            assertFalse(envelope.get("ok").getAsBoolean(), envelope.toString());

            for (int attempt = 0; attempt < 200 && initializations.get() == before; attempt++) {
                Thread.sleep(10);
            }
            assertTrue(initializations.get() > before,
                    "a read that never reached the server must rebuild the connection, not leave it 'connected' "
                            + "and timing out for the rest of the session");
        } finally {
            bridge.close();
        }
    }

    @Test
    void bridgePublishesRemoteToolsAndRemovesThemOnClose() throws Exception {
        McpBridge bridge = McpBridge.install(List.of(McpServerSpec.http("srv", url, ToolAutonomy.FULL)));
        try {
            assertEquals(1, bridge.toolCount(), bridge.problems().toString());
            assertEquals(1, bridge.status().size());
            assertTrue(bridge.status().get(0).connected());
            assertEquals(1, bridge.status().get(0).toolCount());
            ToolId id = McpBridge.toolId("srv", "echo");

            // The server is configured FULL, so a caller below that tier must not reach it. This is the axis that
            // used to be process-wide — and, stuck at its default, the reason a normally configured MCP server was
            // invisible to every caller.
            JsonObject refused = TianshuTools.callJson(id, new JsonObject(), ToolCaller.agent(false), ToolSide.CLIENT)
                    .get(5, TimeUnit.SECONDS);
            assertFalse(refused.get("ok").getAsBoolean(), refused.toString());
            assertEquals("NO_PERMISSION", refused.get("code").getAsString());

            ToolCaller full = ToolCaller.agent("module.test", false, ToolAutonomy.FULL);
            JsonObject result = TianshuTools.callJson(id, new JsonObject(), full, ToolSide.CLIENT)
                    .get(5, TimeUnit.SECONDS);
            assertTrue(result.get("ok").getAsBoolean(), result.toString());
            assertEquals("hello", result.getAsJsonObject("result").getAsJsonArray("content")
                    .get(0).getAsJsonObject().get("text").getAsString());

            CapabilityId resource = McpBridge.capabilityId("srv", "res", "memo");
            assertEquals("resource body", CapabilityCatalog.invoke(resource, new JsonObject(),
                    full, ToolSide.CLIENT).get(5, TimeUnit.SECONDS)
                    .get("text").getAsString());
            CapabilityId prompt = McpBridge.capabilityId("srv", "prompt", "greet");
            assertEquals("Hello from MCP", CapabilityCatalog.invoke(prompt, new JsonObject(),
                    full, ToolSide.CLIENT).get(5, TimeUnit.SECONDS)
                    .get("text").getAsString());
        } finally {
            bridge.close();
        }
        assertTrue(TianshuTools.find(McpBridge.toolId("srv", "echo")) == null);
        assertTrue(CapabilityCatalog.find(McpBridge.capabilityId("srv", "res", "memo")).isEmpty());
        assertFalse(bridge.status().get(0).connected());
    }

    @Test
    void aServerConfiguredWithoutATierIsUsableByAPlainCaller() throws Exception {
        // No "autonomy" in the config: the spec's default tier is the conservative one, which is exactly what a plain
        // caller carries. This is P0-2 pinned from the other side — the configuration that used to register tools
        // nobody could see or call, while looking perfectly valid.
        McpBridge bridge = McpBridge.install(List.of(McpServerSpec.fromJson(JsonParser.parseString(
                "{\"name\":\"srv\",\"url\":\"" + url + "\"}").getAsJsonObject())));
        try {
            ToolId id = McpBridge.toolId("srv", "echo");
            ToolCaller plain = ToolCaller.player("p", false);

            assertTrue(TianshuTools.catalog(plain, ToolSide.CLIENT).stream().anyMatch(spec -> spec.id().equals(id)),
                    "a default-configured server must be visible to a default caller");
            JsonObject result = TianshuTools.callJson(id, new JsonObject(), plain, ToolSide.CLIENT)
                    .get(5, TimeUnit.SECONDS);
            assertTrue(result.get("ok").getAsBoolean(), result.toString());
        } finally {
            bridge.close();
        }
    }

    @Test
    void oneMalformedRemoteToolCostsOnlyThatTool() {
        malformedToolList = true;
        McpBridge bridge = McpBridge.install(List.of(McpServerSpec.http("srv", url, ToolAutonomy.FULL)));
        try {
            assertTrue(bridge.status().get(0).connected(),
                    "a legal-but-unusable entry must not take the server down: " + bridge.problems());
            assertEquals(1, bridge.toolCount(), bridge.problems().toString());
            assertFalse(bridge.problems().isEmpty(), "the skipped entry must still be reported");
        } finally {
            bridge.close();
        }
    }

    @Test
    void aRejectedCallKeepsTheConnectionAlive() throws Exception {
        rejectCallsWith400 = true;
        McpBridge bridge = McpBridge.install(List.of(McpServerSpec.http("srv", url, ToolAutonomy.FULL)));
        try {
            int before = initializations.get();
            ToolCaller full = ToolCaller.agent("module.test", false, ToolAutonomy.FULL);

            JsonObject result = TianshuTools.callJson(McpBridge.toolId("srv", "echo"), new JsonObject(),
                    full, ToolSide.CLIENT).get(5, TimeUnit.SECONDS);

            assertFalse(result.get("ok").getAsBoolean(), result.toString());
            assertTrue(result.get("message").getAsString().contains("not acceptable"), result.toString());
            Thread.sleep(200);
            assertEquals(before, initializations.get(),
                    "HTTP 400 carrying a JSON-RPC error is a refused call, not a dead server");
            assertTrue(bridge.status().get(0).connected());
            assertEquals(1, bridge.toolCount());
        } finally {
            bridge.close();
        }
    }

    @Test
    void twoLongRemoteNamesThatShareAPrefixKeepDistinctIds() {
        String prefix = "a".repeat(80);

        ToolId first = McpBridge.toolId("srv", prefix + "-one");
        ToolId second = McpBridge.toolId("srv", prefix + "-two");

        assertFalse(first.equals(second), "clipping at 64 characters made these the same tool");
        assertTrue(first.name().length() <= 64, first.name());
        assertEquals("srv.echo", McpBridge.toolId("srv", "echo").name(),
                "a name that fits must stay readable and unchanged");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String method = request.has("method") ? request.get("method").getAsString() : "";
        if (exchange.getRequestHeaders().containsKey("Mcp-Session-Id")) {
            sessionSeen.set(true);
        }
        if ("initialize".equals(method)) {
            initializations.incrementAndGet();
            exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
            respond(exchange, request, result("initialize"));
            return;
        }
        if ("notifications/initialized".equals(method)) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        if ("tools/list".equals(method)) {
            JsonObject result = new JsonObject();
            JsonArray tools = new JsonArray();
            if (malformedToolList) {
                JsonObject malformed = new JsonObject();
                JsonObject nameAsObject = new JsonObject();
                nameAsObject.addProperty("value", "echo");
                malformed.add("name", nameAsObject);
                tools.add(malformed);
            }
            JsonObject tool = new JsonObject();
            tool.addProperty("name", "echo");
            tool.addProperty("description", "Echoes the text");
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            tool.add("inputSchema", schema);
            tools.add(tool);
            result.add("tools", tools);
            respond(exchange, request, result);
            return;
        }
        if ("resources/list".equals(method)) {
            JsonObject result = new JsonObject();
            JsonArray resources = new JsonArray();
            JsonObject resource = new JsonObject();
            resource.addProperty("uri", "memo://one");
            resource.addProperty("name", "memo");
            resource.addProperty("description", "A note");
            resource.addProperty("mimeType", "text/plain");
            resources.add(resource);
            JsonObject broken = new JsonObject();
            broken.addProperty("uri", "broken://one");
            broken.addProperty("name", "broken");
            broken.addProperty("mimeType", "text/plain");
            resources.add(broken);
            result.add("resources", resources);
            respond(exchange, request, result);
            return;
        }
        if ("resources/read".equals(method)) {
            String requested = request.has("params") && request.getAsJsonObject("params").has("uri")
                    ? request.getAsJsonObject("params").get("uri").getAsString() : "";
            if (requested.startsWith("broken")) {
                // No response at all: this is a read that never reached a server able to answer it.
                exchange.close();
                return;
            }
            JsonObject result = new JsonObject();
            JsonArray contents = new JsonArray();
            JsonObject content = new JsonObject();
            content.addProperty("uri", "memo://one");
            content.addProperty("text", "resource body");
            contents.add(content);
            result.add("contents", contents);
            respond(exchange, request, result);
            return;
        }
        if ("prompts/list".equals(method)) {
            JsonObject result = new JsonObject();
            JsonArray prompts = new JsonArray();
            JsonObject prompt = new JsonObject();
            prompt.addProperty("name", "greet");
            prompt.addProperty("description", "Greets");
            prompts.add(prompt);
            result.add("prompts", prompts);
            respond(exchange, request, result);
            return;
        }
        if ("prompts/get".equals(method)) {
            JsonObject result = new JsonObject();
            JsonArray messages = new JsonArray();
            JsonObject message = new JsonObject();
            JsonObject content = new JsonObject();
            content.addProperty("type", "text");
            content.addProperty("text", "Hello from MCP");
            message.add("content", content);
            messages.add(message);
            result.add("messages", messages);
            respond(exchange, request, result);
            return;
        }
        if ("tools/call".equals(method)) {
            if (rejectCallsWith400) {
                JsonObject error = new JsonObject();
                error.addProperty("code", -32602);
                error.addProperty("message", "the argument \"text\" is not acceptable");
                JsonObject response = new JsonObject();
                response.addProperty("jsonrpc", "2.0");
                if (request.has("id")) {
                    response.add("id", request.get("id"));
                }
                response.add("error", error);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            JsonObject result = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("type", "text");
            item.addProperty("text", "hello");
            content.add(item);
            result.add("content", content);
            respond(exchange, request, result);
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private static JsonObject result(String method) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");
        JsonObject info = new JsonObject();
        info.addProperty("name", "fake");
        info.addProperty("version", "1");
        result.add("serverInfo", info);
        result.add("capabilities", new JsonObject());
        return result;
    }

    private static void respond(HttpExchange exchange, JsonObject request, JsonObject result) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (request.has("id")) {
            response.add("id", request.get("id"));
        }
        response.add("result", result);
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}