package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
