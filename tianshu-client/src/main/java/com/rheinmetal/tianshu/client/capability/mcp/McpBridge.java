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
 * <p>A failed server does not disappear: it stays in {@link #status()} with its last error, and a watchdog retries
 * with exponential backoff (manual {@link #reconnect(String)} forces one). A transport failure observed by a tool
 * call marks that server disconnected; a remote method rejection ({@link McpRemoteException}) does not.</p>
 */
public final class McpBridge implements McpStatus, AutoCloseable {

    private static final long WATCHDOG_MILLIS = 15_000L;
    private static final long BASE_BACKOFF_MILLIS = 15_000L;
    private static final long MAX_BACKOFF_MILLIS = 300_000L;

    private final List<McpConnection> clients = new ArrayList<>();
    private final List<ToolId> tools = new ArrayList<>();
    private final List<CapabilityId> capabilities = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<String, McpServerStatus> statuses = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tianshu-mcp");
        thread.setDaemon(true);
        return thread;
    });

    private volatile List<McpServerSpec> specs = List.of();
    private volatile long nextAttemptMillis;
    private int consecutiveFailures;

    private McpBridge() {
    }

    /** Connects synchronously; tests and explicit reloads use this. */
    public static McpBridge install(List<McpServerSpec> specs) {
        McpBridge bridge = new McpBridge();
        bridge.specs = List.copyOf(specs);
        bridge.prepareStatuses();
        bridge.connectAll();
        return bridge;
    }

    /** Connects off the game thread and keeps retrying failed servers in the background. */
    public static McpBridge start(Path config) {
        McpBridge bridge = new McpBridge();
        bridge.specs = McpConfig.load(config);
        bridge.prepareStatuses();
        bridge.scheduler.scheduleWithFixedDelay(bridge::watchdog, 0, WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
        return bridge;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public int toolCount() {
        return tools.size();
    }

    @Override
    public synchronized List<McpServerStatus> status() {
        return List.copyOf(statuses.values());
    }

    @Override
    public void reconnect(String name) {
        nextAttemptMillis = 0;
        if (!scheduler.isShutdown()) {
            scheduler.execute(this::connectAll);
        } else {
            connectAll();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        synchronized (this) {
            cleanup();
            for (Map.Entry<String, McpServerStatus> entry : statuses.entrySet()) {
                McpServerStatus current = entry.getValue();
                entry.setValue(new McpServerStatus(current.name(), current.type(), false, 0, current.lastError()));
            }
        }
    }

    private synchronized void connectAll() {
        if (closed.get()) {
            return;
        }
        cleanup();
        consecutiveFailures = 0;
        for (McpServerSpec spec : specs) {
            if (closed.get()) {
                return;
            }
            if (!connectOne(spec)) {
                consecutiveFailures++;
            }
        }
        if (consecutiveFailures == 0) {
            nextAttemptMillis = 0;
        } else {
            long backoff = Math.min(MAX_BACKOFF_MILLIS,
                    BASE_BACKOFF_MILLIS << Math.min(consecutiveFailures, 4));
            nextAttemptMillis = System.currentTimeMillis() + backoff;
        }
    }

    private boolean connectOne(McpServerSpec spec) {
        McpConnection client = null;
        try {
            client = spec.type() == McpServerSpec.Type.STDIO
                    ? new McpStdioClient(spec.command())
                    : new McpHttpClient(spec.url());
            client.initialize();
            int toolCount = 0;
            for (JsonObject remote : client.listTools()) {
                if (closed.get()) {
                    client.close();
                    return false;
                }
                if (register(spec, client, remote)) {
                    toolCount++;
                }
            }
            registerResources(spec, client);
            registerPrompts(spec, client);
            clients.add(client);
            updateStatus(spec, true, toolCount, "");
            return true;
        } catch (Exception failure) {
            if (client != null) {
                client.close();
            }
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            problems.add(spec.name() + ": " + message);
            updateStatus(spec, false, 0, message);
            return false;
        }
    }

    private synchronized void cleanup() {
        for (ToolId id : tools) {
            TianshuTools.unregister(id);
        }
        tools.clear();
        for (CapabilityId id : capabilities) {
            CapabilityCatalog.unregister(id);
        }
        capabilities.clear();
        for (McpConnection client : clients) {
            client.close();
        }
        clients.clear();
        for (Map.Entry<String, McpServerStatus> entry : statuses.entrySet()) {
            McpServerStatus current = entry.getValue();
            entry.setValue(new McpServerStatus(current.name(), current.type(), false, 0, current.lastError()));
        }
    }

    private void watchdog() {
        if (closed.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now < nextAttemptMillis) {
                return;
            }
            if (statuses.values().stream().allMatch(McpServerStatus::connected)) {
                return;
            }
        }
        connectAll();
    }

    private void onTransportFailure(String server) {
        synchronized (this) {
            for (McpServerSpec spec : specs) {
                if (spec.name().equals(server)) {
                    updateStatus(spec, false, 0, "connection lost");
                    break;
                }
            }
            nextAttemptMillis = System.currentTimeMillis();
        }
        if (!scheduler.isShutdown()) {
            scheduler.execute(this::connectAll);
        }
    }

    private void prepareStatuses() {
        statuses.clear();
        for (McpServerSpec spec : specs) {
            statuses.put(spec.name(), new McpServerStatus(spec.name(), spec.type(), false, 0, ""));
        }
    }

    private void updateStatus(McpServerSpec spec, boolean connected, int toolCount, String error) {
        statuses.put(spec.name(), new McpServerStatus(spec.name(), spec.type(), connected, toolCount, error));
    }

    private boolean register(McpServerSpec spec, McpConnection client, JsonObject remote) {
        String remoteName = remote.has("name") ? remote.get("name").getAsString() : "";
        if (remoteName.isBlank()) {
            return false;
        }
        String description = remote.has("description") ? remote.get("description").getAsString() : remoteName;
        JsonObject schema = remote.has("inputSchema") && remote.get("inputSchema").isJsonObject()
                ? remote.getAsJsonObject("inputSchema") : null;
        ToolId id = toolId(spec.name(), remoteName);
        McpTool tool = new McpTool(spec.name(), id, client, remoteName, description, schema, spec.autonomy(),
                this::onTransportFailure);
        try {
            TianshuTools.register(tool);
            tools.add(id);
            return true;
        } catch (RuntimeException conflict) {
            problems.add(spec.name() + "/" + remoteName + ": " + conflict.getMessage());
            return false;
        }
    }

    private void registerResources(McpServerSpec spec, McpConnection client) {
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

    private void registerPrompts(McpServerSpec spec, McpConnection client) {
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