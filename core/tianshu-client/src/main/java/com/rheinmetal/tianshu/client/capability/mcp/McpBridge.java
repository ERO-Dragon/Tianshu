package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connects the configured MCP servers, publishes their tools as normal Tianshu tools, and exposes health.
 *
 * <p>Each server is independent: a failed server keeps its state, a watchdog retries only that server with
 * exponential backoff, and the others stay connected. A transport failure observed by a tool call marks its server
 * disconnected; a remote method rejection ({@link McpRemoteException}) does not.</p>
 */
public final class McpBridge implements McpStatus, AutoCloseable {

    private static final long WATCHDOG_MILLIS = 15_000L;
    private static final long BASE_BACKOFF_MILLIS = 15_000L;
    private static final long MAX_BACKOFF_MILLIS = 300_000L;
    private static final int MAX_PROBLEMS = 64;

    private final Map<String, ServerState> states = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tianshu-mcp");
        thread.setDaemon(true);
        return thread;
    });

    private McpBridge() {
    }

    /** Connects synchronously; tests and explicit reloads use this. */
    public static McpBridge install(List<McpServerSpec> specs) {
        McpBridge bridge = new McpBridge();
        bridge.prepare(specs);
        bridge.connectAll();
        return bridge;
    }

    /** Connects off the game thread and keeps retrying failed servers in the background. */
    public static McpBridge start(Path config) {
        McpBridge bridge = new McpBridge();
        bridge.prepare(McpConfig.load(config));
        bridge.scheduler.scheduleWithFixedDelay(bridge::watchdog, 0, WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
        return bridge;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public int toolCount() {
        int total = 0;
        synchronized (this) {
            for (ServerState state : states.values()) {
                total += state.tools.size();
            }
        }
        return total;
    }

    @Override
    public synchronized List<McpServerStatus> status() {
        List<McpServerStatus> snapshot = new ArrayList<>(states.size());
        for (ServerState state : states.values()) {
            snapshot.add(new McpServerStatus(state.spec.name(), state.spec.type(), state.connected,
                    state.tools.size(), state.lastError));
        }
        return List.copyOf(snapshot);
    }

    @Override
    public void reconnect(String name) {
        if (!scheduler.isShutdown()) {
            scheduler.execute(() -> reconnectNow(name));
        } else {
            reconnectNow(name);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        synchronized (this) {
            for (ServerState state : states.values()) {
                cleanup(state);
            }
        }
    }

    private void prepare(List<McpServerSpec> specs) {
        synchronized (this) {
            states.clear();
            for (McpServerSpec spec : specs) {
                states.put(spec.name(), new ServerState(spec));
            }
        }
    }

    private synchronized void reconnectNow(String name) {
        if (closed.get()) {
            return;
        }
        ServerState target = name == null ? null : states.get(name);
        if (target != null) {
            connect(target);
            return;
        }
        connectAll();
    }

    private synchronized void connectAll() {
        if (closed.get()) {
            return;
        }
        for (ServerState state : states.values()) {
            connect(state);
        }
    }

    private void connect(ServerState state) {
        if (closed.get()) {
            return;
        }
        cleanup(state);
        McpConnection client = null;
        try {
            client = state.spec.type() == McpServerSpec.Type.STDIO
                    ? new McpStdioClient(state.spec.command())
                    : new McpHttpClient(state.spec.url());
            client.initialize();
            for (JsonObject remote : client.listTools()) {
                if (closed.get()) {
                    client.close();
                    return;
                }
                register(state, client, remote);
            }
            registerResources(state, client);
            registerPrompts(state, client);
            state.client = client;
            state.connected = true;
            state.failures = 0;
            state.nextAttemptMillis = 0;
            state.lastError = "";
        } catch (Exception failure) {
            if (client != null) {
                client.close();
            }
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            state.connected = false;
            state.client = null;
            state.lastError = message;
            state.failures++;
            state.nextAttemptMillis = System.currentTimeMillis()
                    + Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS << Math.min(state.failures, 4));
            problem(state.spec.name() + ": " + message);
        }
    }

    private void cleanup(ServerState state) {
        for (ToolId id : state.tools) {
            TianshuTools.unregister(id);
        }
        state.tools.clear();
        for (CapabilityId id : state.capabilities) {
            CapabilityCatalog.unregister(id);
        }
        state.capabilities.clear();
        if (state.client != null) {
            state.client.close();
            state.client = null;
        }
        state.connected = false;
    }

    private void watchdog() {
        if (closed.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (this) {
            for (ServerState state : states.values()) {
                if (!state.connected && now >= state.nextAttemptMillis) {
                    connect(state);
                }
            }
        }
    }

    private synchronized void onTransportFailure(String server) {
        ServerState state = states.get(server);
        if (state == null) {
            return;
        }
        state.lastError = "connection lost";
        state.nextAttemptMillis = System.currentTimeMillis();
        cleanup(state);
        if (!scheduler.isShutdown()) {
            scheduler.execute(() -> reconnectNow(server));
        }
    }

    private void problem(String message) {
        problems.add(message);
        while (problems.size() > MAX_PROBLEMS) {
            problems.remove(0);
        }
    }

    private boolean register(ServerState state, McpConnection client, JsonObject remote) {
        String remoteName = remote.has("name") ? remote.get("name").getAsString() : "";
        if (remoteName.isBlank()) {
            return false;
        }
        String description = remote.has("description") ? remote.get("description").getAsString() : remoteName;
        JsonObject schema = remote.has("inputSchema") && remote.get("inputSchema").isJsonObject()
                ? remote.getAsJsonObject("inputSchema") : null;
        ToolId id = toolId(state.spec.name(), remoteName);
        McpTool tool = new McpTool(state.spec.name(), id, client, remoteName, description, schema,
                state.spec.autonomy(), this::onTransportFailure);
        try {
            TianshuTools.register(tool);
            state.tools.add(id);
            return true;
        } catch (RuntimeException conflict) {
            problem(state.spec.name() + "/" + remoteName + ": " + conflict.getMessage());
            return false;
        }
    }

    private void registerResources(ServerState state, McpConnection client) {
        try {
            for (JsonObject remote : client.listResources()) {
                String uri = remote.has("uri") ? remote.get("uri").getAsString() : "";
                String name = remote.has("name") ? remote.get("name").getAsString() : uri;
                if (uri.isBlank() || name.isBlank()) {
                    continue;
                }
                CapabilityId id = capabilityId(state.spec.name(), "res", name);
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
                state.capabilities.add(id);
            }
        } catch (Exception unsupported) {
            // A server may not implement resources; that is not a reason to lose its tools.
        }
    }

    private void registerPrompts(ServerState state, McpConnection client) {
        try {
            for (JsonObject remote : client.listPrompts()) {
                String name = remote.has("name") ? remote.get("name").getAsString() : "";
                if (name.isBlank()) {
                    continue;
                }
                CapabilityId id = capabilityId(state.spec.name(), "prompt", name);
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
                state.capabilities.add(id);
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

    private static final class ServerState {
        private final McpServerSpec spec;
        private final List<ToolId> tools = new ArrayList<>();
        private final List<CapabilityId> capabilities = new ArrayList<>();
        private McpConnection client;
        private boolean connected;
        private String lastError = "";
        private int failures;
        private long nextAttemptMillis;

        private ServerState(McpServerSpec spec) {
            this.spec = spec;
        }
    }
}