package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connects the configured MCP servers and publishes their tools as normal Tianshu tools.
 *
 * <p>Connections happen on a daemon thread so a slow or offline server cannot stall client start; the bridge can be
 * closed while connecting, in which case anything registered afterwards is undone. Every failure is recorded in
 * {@link #problems()} instead of being thrown into the game loop.</p>
 */
public final class McpBridge implements AutoCloseable {

    private final List<McpClient> clients = new ArrayList<>();
    private final List<ToolId> tools = new ArrayList<>();
    private final List<CapabilityId> capabilities = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpBridge() {
    }

    /** Connects synchronously; tests and explicit reloads use this. */
    public static McpBridge install(List<McpServerSpec> specs) {
        McpBridge bridge = new McpBridge();
        bridge.connect(specs);
        return bridge;
    }

    /** Connects off the game thread; the returned bridge can be closed at any time. */
    public static McpBridge start(Path config) {
        McpBridge bridge = new McpBridge();
        Thread worker = new Thread(() -> bridge.connect(McpConfig.load(config)), "tianshu-mcp");
        worker.setDaemon(true);
        worker.start();
        return bridge;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public int toolCount() {
        return tools.size();
    }

    @Override
    public void close() {
        closed.set(true);
        synchronized (this) {
            for (ToolId id : tools) {
                TianshuTools.unregister(id);
            }
            tools.clear();
            for (CapabilityId id : capabilities) {
                CapabilityCatalog.unregister(id);
            }
            capabilities.clear();
            for (McpClient client : clients) {
                client.close();
            }
            clients.clear();
        }
    }

    private synchronized void connect(List<McpServerSpec> specs) {
        for (McpServerSpec spec : specs) {
            if (closed.get()) {
                return;
            }
            McpClient client = new McpClient(spec.url());
            try {
                client.initialize();
                List<JsonObject> remoteTools = client.listTools();
                for (JsonObject remote : remoteTools) {
                    if (closed.get()) {
                        client.close();
                        return;
                    }
                    register(spec, client, remote);
                }
                registerResources(spec, client);
                registerPrompts(spec, client);
                clients.add(client);
            } catch (Exception failure) {
                client.close();
                problems.add(spec.name() + ": " + failure.getMessage());
            }
        }
    }

    private void register(McpServerSpec spec, McpClient client, JsonObject remote) {
        String remoteName = remote.has("name") ? remote.get("name").getAsString() : "";
        if (remoteName.isBlank()) {
            return;
        }
        String description = remote.has("description") ? remote.get("description").getAsString() : remoteName;
        JsonObject schema = remote.has("inputSchema") && remote.get("inputSchema").isJsonObject()
                ? remote.getAsJsonObject("inputSchema") : null;
        ToolId id = toolId(spec.name(), remoteName);
        McpTool tool = new McpTool(spec.name(), id, client, remoteName, description, schema, spec.autonomy());
        try {
            TianshuTools.register(tool);
            tools.add(id);
        } catch (RuntimeException conflict) {
            problems.add(spec.name() + "/" + remoteName + ": " + conflict.getMessage());
        }
    }

    private void registerResources(McpServerSpec spec, McpClient client) {
        try {
            for (JsonObject remote : client.listResources()) {
                String uri = remote.has("uri") ? remote.get("uri").getAsString() : "";
                String name = remote.has("name") ? remote.get("name").getAsString() : uri;
                if (uri.isBlank() || name.isBlank()) {
                    continue;
                }
                CapabilityId id = capabilityId(spec.name(), "res", name);
                JsonObject attributes = new JsonObject();
                attributes.addProperty("name", name);
                attributes.addProperty("source", "mcp");
                attributes.addProperty("mediaType", remote.has("mimeType")
                        ? remote.get("mimeType").getAsString() : "text/plain");
                CapabilityDescriptor descriptor = new CapabilityDescriptor(id, CapabilityKind.RESOURCE,
                        CapabilityBinding.MCP, description(remote, name), ToolSide.CLIENT,
                        ToolPermission.EVERYONE, "tianshu-mcp", attributes);
                CapabilityCatalog.register(descriptor, (context, args) -> {
                    try {
                        return CompletableFuture.completedFuture(textEnvelope(readContents(client.readResource(uri))));
                    } catch (Exception failure) {
                        return CompletableFuture.completedFuture(errorEnvelope(failure));
                    }
                });
                capabilities.add(id);
            }
        } catch (Exception unsupported) {
            // A server may not implement resources; that is not a reason to lose its tools.
        }
    }

    private void registerPrompts(McpServerSpec spec, McpClient client) {
        try {
            for (JsonObject remote : client.listPrompts()) {
                String name = remote.has("name") ? remote.get("name").getAsString() : "";
                if (name.isBlank()) {
                    continue;
                }
                CapabilityId id = capabilityId(spec.name(), "prompt", name);
                JsonObject attributes = new JsonObject();
                attributes.addProperty("name", name);
                attributes.addProperty("source", "mcp");
                CapabilityDescriptor descriptor = new CapabilityDescriptor(id, CapabilityKind.PROMPT,
                        CapabilityBinding.MCP, description(remote, name), ToolSide.CLIENT,
                        ToolPermission.EVERYONE, "tianshu-mcp", attributes);
                CapabilityCatalog.register(descriptor, (context, args) -> {
                    try {
                        JsonObject result = client.getPrompt(name, args == null ? new JsonObject() : args);
                        return CompletableFuture.completedFuture(textEnvelope(readMessages(result)));
                    } catch (Exception failure) {
                        return CompletableFuture.completedFuture(errorEnvelope(failure));
                    }
                });
                capabilities.add(id);
            }
        } catch (Exception unsupported) {
            // Same as resources: prompts are optional.
        }
    }

    private static String description(JsonObject remote, String fallback) {
        return remote.has("description") ? remote.get("description").getAsString() : fallback;
    }

    private static String readContents(JsonObject result) {
        if (result != null && result.has("contents") && result.get("contents").isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement element : result.getAsJsonArray("contents")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("text")) {
                    text.append(element.getAsJsonObject().get("text").getAsString());
                }
            }
            return text.toString();
        }
        return "";
    }

    private static String readMessages(JsonObject result) {
        if (result != null && result.has("messages") && result.get("messages").isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement element : result.getAsJsonArray("messages")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject message = element.getAsJsonObject();
                if (message.has("content") && message.get("content").isJsonObject()) {
                    JsonObject content = message.getAsJsonObject("content");
                    if (content.has("text")) {
                        text.append(content.get("text").getAsString()).append('\n');
                    }
                }
            }
            return text.toString().strip();
        }
        return "";
    }

    private static JsonObject textEnvelope(String text) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        envelope.addProperty("text", text);
        return envelope;
    }

    private static JsonObject errorEnvelope(Exception failure) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", false);
        envelope.addProperty("code", "FAILED");
        envelope.addProperty("message", String.valueOf(failure.getMessage()));
        return envelope;
    }

    static CapabilityId capabilityId(String server, String kind, String name) {
        String raw = (server + "." + kind + "." + name).toLowerCase(Locale.ROOT);
        StringBuilder safe = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                    || character == '_' || character == '.' || character == '-';
            safe.append(allowed ? character : '_');
        }
        String id = safe.length() > 64 ? safe.substring(0, 64) : safe.toString();
        return CapabilityId.of("mcp", id);
    }

    static ToolId toolId(String server, String remote) {
        String raw = (server + "." + remote).toLowerCase(Locale.ROOT);
        StringBuilder safe = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                    || character == '_' || character == '.' || character == '-';
            safe.append(allowed ? character : '_');
        }
        String name = safe.length() > 64 ? safe.substring(0, 64) : safe.toString();
        return ToolId.of("mcp", name);
    }
}
