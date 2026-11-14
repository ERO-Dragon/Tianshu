package com.rheinmetal.tianshu.client.capability.mcp.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The read-only MCP endpoint: initialize, tool list/call, resource list/read. */
class McpServerTest {

    private static final CapabilityId SKILL = CapabilityId.of("test", "server-skill");

    private McpServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        CapabilityCatalog.register(new CapabilityDescriptor(SKILL, CapabilityKind.SKILL, CapabilityBinding.LOCAL,
                        "a server skill", ToolSide.CLIENT, ToolPermission.EVERYONE, "test", new JsonObject()),
                (context, args) -> {
                    JsonObject envelope = new JsonObject();
                    envelope.addProperty("ok", true);
                    envelope.addProperty("text", "from the server");
                    return CompletableFuture.completedFuture(envelope);
                });
        server = McpServer.start(0);
    }

    @AfterEach
    void clean() {
        if (server != null) {
            server.close();
        }
        CapabilityCatalog.unregister(SKILL);
    }

    @Test
    void initializeAndToolCallOverHttp() throws Exception {
        JsonObject init = call("initialize", new JsonObject());
        assertTrue(init.has("result"));
        assertTrue(init.getAsJsonObject("result").getAsJsonObject("capabilities").has("tools"));

        JsonObject tools = call("tools/list", new JsonObject());
        assertEquals("read_capability", tools.getAsJsonObject("result").getAsJsonArray("tools")
                .get(0).getAsJsonObject().get("name").getAsString());

        JsonObject arguments = new JsonObject();
        arguments.addProperty("id", SKILL.toString());
        JsonObject params = new JsonObject();
        params.addProperty("name", "read_capability");
        params.add("arguments", arguments);
        JsonObject call = call("tools/call", params);
        assertEquals("from the server", call.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void resourcesListAndRead() throws Exception {
        JsonObject list = call("resources/list", new JsonObject());
        JsonArray resources = list.getAsJsonObject("result").getAsJsonArray("resources");
        assertEquals(0, resources.size(), "only resources are listed; a skill is not a resource");

        JsonObject params = new JsonObject();
        params.addProperty("uri", "capability://" + SKILL);
        JsonObject read = call("resources/read", params);
        assertEquals("from the server", read.getAsJsonObject("result").getAsJsonArray("contents")
                .get(0).getAsJsonObject().get("text").getAsString());
    }

    private JsonObject call(String method, JsonObject params) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", method);
        request.add("params", params);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}