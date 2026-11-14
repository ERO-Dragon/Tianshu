package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolAutonomyGate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real HTTP round-trip against a local fake MCP server, plus the tool bridge. */
class McpClientTest {

    private HttpServer server;
    private String url;
    private final AtomicBoolean sessionSeen = new AtomicBoolean();

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
        ToolAutonomyGate.reset();
    }

    @Test
    void handshakeListAndCallOverHttp() throws Exception {
        McpClient client = new McpClient(url);
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
    void bridgePublishesRemoteToolsAndRemovesThemOnClose() throws Exception {
        ToolAutonomyGate.setMode(ToolAutonomy.FULL);
        McpBridge bridge = McpBridge.install(List.of(new McpServerSpec("srv", url, ToolAutonomy.FULL)));
        try {
            assertEquals(1, bridge.toolCount(), bridge.problems().toString());
            ToolId id = McpBridge.toolId("srv", "echo");
            JsonObject result = TianshuTools.callJson(id, new JsonObject(), ToolCaller.agent(false), ToolSide.CLIENT)
                    .get(5, TimeUnit.SECONDS);
            assertTrue(result.get("ok").getAsBoolean(), result.toString());
            assertEquals("hello", result.getAsJsonObject("result").getAsJsonArray("content")
                    .get(0).getAsJsonObject().get("text").getAsString());
        } finally {
            bridge.close();
        }
        assertTrue(TianshuTools.find(McpBridge.toolId("srv", "echo")) == null);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String method = request.has("method") ? request.get("method").getAsString() : "";
        if (exchange.getRequestHeaders().containsKey("Mcp-Session-Id")) {
            sessionSeen.set(true);
        }
        if ("initialize".equals(method)) {
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
        if ("tools/call".equals(method)) {
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