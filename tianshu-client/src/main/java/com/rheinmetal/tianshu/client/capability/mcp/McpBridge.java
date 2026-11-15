package com.rheinmetal.tianshu.client.capability.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.LogSink;
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Connects the configured MCP servers, publishes their tools as normal Tianshu tools, and exposes health.
 *
 * <p>Each server is independent: a failed server keeps its state, a watchdog retries only that server with
 * exponential backoff, and the others stay connected. A transport failure observed by a tool call marks its server
 * disconnected; a remote method rejection ({@link McpRemoteException}) does not.</p>
 *
 * <p>Independence is enforced by the locking, not just by the data model. Every server owns its own lock, so connecting
 * one (a handshake plus three list calls, each with a 15 s budget) cannot block another server's retry, a tool call, or
 * the health snapshot. The former single monitor around the whole bridge meant one slow server froze {@link #status()},
 * {@link #toolCount()} and {@link #close()} for as long as its handshake took.</p>
 */
public final class McpBridge implements McpStatus, AutoCloseable {

    private static final long WATCHDOG_MILLIS = 15_000L;
    private static final long BASE_BACKOFF_MILLIS = 15_000L;
    private static final long MAX_BACKOFF_MILLIS = 300_000L;
    private static final int MAX_PROBLEMS = 64;
    /** Longest a connector thread may be busy before the pool needs another one. */
    private static final int MAX_CONNECTOR_THREADS = 16;
    /** Cap on the "already told the log about this" table; the window restarts when it fills up. */
    private static final int MAX_TRACKED_PROBLEMS = 128;
    private static final long PROBLEM_LOG_INTERVAL_MILLIS = 60_000L;
    /** How long {@link #close()} waits for an in-flight connect before leaving it to its own {@code closed} checks. */
    private static final long CLOSE_LOCK_WAIT_MILLIS = 1_000L;
    /** Longest identifier either registry accepts from us; a longer remote name is shortened, not clipped blind. */
    private static final int MAX_ID_LENGTH = 64;

    private final LogSink logs;
    private final Map<String, ServerState> states;
    private final List<String> problems = new ArrayList<>();
    /** Last time each problem text reached the log; guarded by {@code problems}. */
    private final Map<String, Long> problemLoggedAt = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Watchdog timer only: it decides *when* a server is due and hands the work on. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tianshu-mcp");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Where a connect runs: one thread per configured server, so a slow handshake delays only its own server.
     *
     * <p>A single shared thread was the other half of the head-of-line problem — a reconnect for server A held the
     * only slot while server B's retry waited behind it.</p>
     */
    private final ExecutorService connectors;

    /**
     * Where a remote tool call runs.
     *
     * <p>A dedicated pool rather than the common {@code ForkJoinPool}: an MCP call blocks on the network, and on a
     * client the common pool only has core-count-minus-one threads, so a couple of slow servers used to stall
     * unrelated work that happened to share it. Bounded on purpose — the call budget is already limited by
     * {@code ToolConcurrency}, and an unbounded pool would turn a slow server into thread growth.</p>
     */
    private final ExecutorService calls = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "tianshu-mcp-call");
        thread.setDaemon(true);
        return thread;
    });

    private McpBridge(List<McpServerSpec> specs, LogSink logSink) {
        this.logs = logSink == null ? LogSink.SYSTEM : logSink;
        Map<String, ServerState> prepared = new LinkedHashMap<>();
        for (McpServerSpec spec : specs) {
            if (prepared.containsKey(spec.name())) {
                // Silently keeping the last one would make a copy-pasted block behave like a deletion.
                problem(spec.name() + ": configured more than once; only the first entry is used");
                continue;
            }
            prepared.put(spec.name(), new ServerState(spec));
        }
        this.states = Collections.unmodifiableMap(prepared);
        int threads = Math.min(MAX_CONNECTOR_THREADS, Math.max(1, states.size()));
        this.connectors = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "tianshu-mcp-connect");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Connects synchronously; tests and explicit reloads use this. */
    public static McpBridge install(List<McpServerSpec> specs) {
        return install(specs, LogSink.SYSTEM);
    }

    public static McpBridge install(List<McpServerSpec> specs, LogSink logs) {
        McpBridge bridge = new McpBridge(specs, logs);
        bridge.connectAllNow();
        return bridge;
    }

    /** Connects off the game thread and keeps retrying failed servers in the background. */
    public static McpBridge start(Path config, LogSink logs) {
        McpBridge bridge = new McpBridge(McpConfig.load(config, logs), logs);
        bridge.scheduler.scheduleWithFixedDelay(bridge::watchdog, 0, WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
        return bridge;
    }

    @Override
    public List<String> problems() {
        synchronized (problems) {
            return List.copyOf(problems);
        }
    }

    public int toolCount() {
        int total = 0;
        for (ServerState state : states.values()) {
            total += state.tools.size();
        }
        return total;
    }

    @Override
    public List<McpServerStatus> status() {
        List<McpServerStatus> snapshot = new ArrayList<>(states.size());
        for (ServerState state : states.values()) {
            snapshot.add(state.snapshot());
        }
        return List.copyOf(snapshot);
    }

    @Override
    public void reconnect(String name) {
        ServerState target = name == null ? null : states.get(name);
        if (target == null) {
            for (ServerState state : states.values()) {
                submitConnect(state);
            }
            return;
        }
        submitConnect(target);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        connectors.shutdownNow();
        calls.shutdownNow();
        for (ServerState state : states.values()) {
            // Bounded on purpose: this runs on the client thread during shutdown, and a handshake in flight must not
            // hold the exit for its full 15 s budget. A connect we cannot outrun sees `closed` and closes its own
            // client, so skipping the lock loses nothing but a little promptness.
            boolean locked = false;
            try {
                locked = state.lock.tryLock(CLOSE_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!locked) {
                logs.warn("mcp: " + state.spec.name() + " was still connecting at shutdown; it will close itself");
                continue;
            }
            try {
                cleanupLocked(state);
            } finally {
                state.lock.unlock();
            }
        }
    }

    private void connectAllNow() {
        for (ServerState state : states.values()) {
            state.lock.lock();
            try {
                connectLocked(state);
            } finally {
                state.lock.unlock();
            }
        }
    }

    /** Claims this server's connect slot and runs one attempt on the connector pool. */
    private void submitConnect(ServerState state) {
        if (closed.get() || connectors.isShutdown()) {
            return;
        }
        if (!state.connectScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            connectors.execute(() -> {
                try {
                    state.lock.lock();
                    try {
                        connectLocked(state);
                    } finally {
                        state.lock.unlock();
                    }
                } finally {
                    state.connectScheduled.set(false);
                }
            });
        } catch (RejectedExecutionException shutdownRace) {
            state.connectScheduled.set(false);
        }
    }

    /** Caller must hold {@code state.lock}. */
    private void connectLocked(ServerState state) {
        if (closed.get()) {
            return;
        }
        cleanupLocked(state);
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
                registerLocked(state, client, remote);
            }
            registerResources(state, client);
            registerPrompts(state, client);
            if (closed.get()) {
                client.close();
                return;
            }
            state.client = client;
            state.connected = true;
            state.failures = 0;
            state.nextAttemptMillis = 0L;
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
                    + Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS << Math.min(state.failures, 5));
            problem(state.spec.name() + ": " + message);
        }
    }

    /** Caller must hold {@code state.lock}. */
    private void cleanupLocked(ServerState state) {
        for (ToolId id : state.tools) {
            TianshuTools.unregister(id);
        }
        state.tools = List.of();
        for (CapabilityId id : state.capabilities) {
            CapabilityCatalog.unregister(id);
        }
        state.capabilities = List.of();
        McpConnection client = state.client;
        state.client = null;
        state.connected = false;
        if (client != null) {
            client.close();
        }
    }

    private void watchdog() {
        if (closed.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ServerState state : states.values()) {
            if (!state.connected && now >= state.nextAttemptMillis) {
                submitConnect(state);
            }
        }
    }

    private void onTransportFailure(String server) {
        ServerState state = states.get(server);
        if (state == null || state.connectScheduled.get()) {
            return;
        }
        state.lock.lock();
        try {
            state.lastError = "connection lost";
            state.nextAttemptMillis = 0L;
            cleanupLocked(state);
        } finally {
            state.lock.unlock();
        }
        submitConnect(state);
    }

    /**
     * Records a problem and tells the log about it, rate-limited per message.
     *
     * <p>Before this, {@code problems} was written by six call sites and read by nobody: a mistyped command, a server
     * that never starts, and a tool whose id collides all looked like "no tool appeared". The rate limit matters
     * because an unreachable server is retried with backoff for the whole session and would otherwise repeat the same
     * line every 15 s.</p>
     */
    private void problem(String message) {
        boolean report;
        synchronized (problems) {
            problems.add(message);
            while (problems.size() > MAX_PROBLEMS) {
                problems.remove(0);
            }
            long now = System.currentTimeMillis();
            Long last = problemLoggedAt.get(message);
            report = last == null || now - last >= PROBLEM_LOG_INTERVAL_MILLIS;
            if (report) {
                if (problemLoggedAt.size() >= MAX_TRACKED_PROBLEMS) {
                    problemLoggedAt.clear();
                }
                problemLoggedAt.put(message, now);
            }
        }
        if (report) {
            logs.warn("mcp: " + message);
        }
    }

    /** Caller must hold {@code state.lock}. */
    private void registerLocked(ServerState state, McpConnection client, JsonObject remote) {
        // Every field is read defensively: a server that writes one name as an object must cost us that tool, not the
        // whole server — the parse used to sit outside the try below and took the connection down with it.
        String remoteName = primitive(remote, "name", "");
        if (remoteName.isBlank()) {
            problem(state.spec.name() + ": an entry in tools/list has no usable name");
            return;
        }
        String description = primitive(remote, "description", remoteName);
        JsonObject schema = remote.has("inputSchema") && remote.get("inputSchema").isJsonObject()
                ? remote.getAsJsonObject("inputSchema") : null;
        ToolId id = toolId(state.spec.name(), remoteName);
        McpTool tool = new McpTool(state.spec.name(), id, client, remoteName, description, schema,
                state.spec.autonomy(), this::onTransportFailure, calls);
        try {
            TianshuTools.register(tool);
            state.tools = append(state.tools, id);
        } catch (RuntimeException conflict) {
            problem(state.spec.name() + "/" + remoteName + ": " + conflict.getMessage());
        }
    }

    /** Caller must hold {@code state.lock}. */
    private void registerResources(ServerState state, McpConnection client) throws IOException, InterruptedException {
        try {
            for (JsonObject remote : client.listResources()) {
                String uri = primitive(remote, "uri", "");
                String name = primitive(remote, "name", uri);
                if (uri.isBlank() || name.isBlank()) {
                    continue;
                }
                CapabilityId id = capabilityId(state.spec.name(), "res", name);
                JsonObject attributes = new JsonObject();
                attributes.addProperty("name", name);
                attributes.addProperty("source", "mcp");
                attributes.addProperty("mediaType", primitive(remote, "mimeType", "text/plain"));
                CapabilityDescriptor descriptor = new CapabilityDescriptor(id, CapabilityKind.RESOURCE,
                        CapabilityBinding.MCP, description(remote, name), ToolSide.CLIENT,
                        ToolPermission.EVERYONE, "tianshu-mcp", attributes);
                CapabilityCatalog.register(descriptor, (context, args) -> {
                    try {
                        return CompletableFuture.completedFuture(textEnvelope(readContents(client.readResource(uri))));
                    } catch (McpRemoteException rejected) {
                        // The server answered: that one read was refused, the connection itself is fine.
                        return CompletableFuture.completedFuture(errorEnvelope(rejected));
                    } catch (IOException | InterruptedException transport) {
                        // The read never reached the server. Rebuild instead of keeping a server that still claims to
                        // be connected and then times out on every later read.
                        if (transport instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        onTransportFailure(state.spec.name());
                        return CompletableFuture.completedFuture(errorEnvelope(transport));
                    } catch (RuntimeException malformed) {
                        return CompletableFuture.completedFuture(errorEnvelope(malformed));
                    }
                });
                state.capabilities = append(state.capabilities, id);
            }
        } catch (McpRemoteException unsupported) {
            // A server may not implement resources; that is not a reason to lose its tools.
        }
    }

    /** Caller must hold {@code state.lock}. */
    private void registerPrompts(ServerState state, McpConnection client) throws IOException, InterruptedException {
        try {
            for (JsonObject remote : client.listPrompts()) {
                String name = primitive(remote, "name", "");
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
                    } catch (McpRemoteException rejected) {
                        return CompletableFuture.completedFuture(errorEnvelope(rejected));
                    } catch (IOException | InterruptedException transport) {
                        if (transport instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        onTransportFailure(state.spec.name());
                        return CompletableFuture.completedFuture(errorEnvelope(transport));
                    } catch (RuntimeException malformed) {
                        return CompletableFuture.completedFuture(errorEnvelope(malformed));
                    }
                });
                state.capabilities = append(state.capabilities, id);
            }
        } catch (McpRemoteException unsupported) {
            // Same as resources: prompts are optional.
        }
    }

    private static <T> List<T> append(List<T> current, T added) {
        List<T> next = new ArrayList<>(current.size() + 1);
        next.addAll(current);
        next.add(added);
        return List.copyOf(next);
    }

    private static String description(JsonObject remote, String fallback) {
        return primitive(remote, "description", fallback);
    }

    /** Reads one optional string; a value of the wrong JSON shape is treated as absent, not as a fatal error. */
    private static String primitive(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    private static String readContents(JsonObject result) {
        if (result != null && result.has("contents") && result.get("contents").isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement element : result.getAsJsonArray("contents")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("text")) {
                    text.append(primitive(element.getAsJsonObject(), "text", ""));
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
                    text.append(primitive(message.getAsJsonObject("content"), "text", "")).append('\n');
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
        return CapabilityId.of("mcp", stableName(server + "." + kind + "." + name));
    }

    static ToolId toolId(String server, String remote) {
        return ToolId.of("mcp", stableName(server + "." + remote));
    }

    /**
     * Turns a remote name into an id segment, shortening rather than clipping.
     *
     * <p>Plain truncation at {@value #MAX_ID_LENGTH} made two long names that share a prefix collide, and the only
     * symptom was a problem line nobody read — the second tool simply never appeared. The digest is taken over the
     * original text (before case folding and replacement), so two names that sanitize to the same prefix still end up
     * with different ids.</p>
     */
    private static String stableName(String raw) {
        StringBuilder safe = new StringBuilder(raw.length());
        String lowered = raw.toLowerCase(Locale.ROOT);
        for (int index = 0; index < lowered.length(); index++) {
            char character = lowered.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                    || character == '_' || character == '.' || character == '-';
            safe.append(allowed ? character : '_');
        }
        if (safe.length() <= MAX_ID_LENGTH) {
            return safe.toString();
        }
        String digest = digest(raw);
        return safe.substring(0, MAX_ID_LENGTH - digest.length() - 1) + "-" + digest;
    }

    /** Stable across runs and JVMs (unlike identity hashes), which keeps an id usable in a saved reference. */
    private static String digest(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < text.length(); index++) {
            hash ^= text.charAt(index);
            hash *= 0x100000001b3L;
        }
        String hex = Long.toHexString(hash);
        return hex.length() > 8 ? hex.substring(hex.length() - 8) : hex;
    }

    private static final class ServerState {
        private final McpServerSpec spec;
        /** This server's connect/cleanup lock; never held while touching another server. */
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicBoolean connectScheduled = new AtomicBoolean();
        /** Immutable snapshots so health reads cost nothing and never wait for a handshake. */
        private volatile List<ToolId> tools = List.of();
        private volatile List<CapabilityId> capabilities = List.of();
        private volatile McpConnection client;
        private volatile boolean connected;
        private volatile String lastError = "";
        private volatile long nextAttemptMillis;
        private int failures;

        private ServerState(McpServerSpec spec) {
            this.spec = spec;
        }

        private McpServerStatus snapshot() {
            return new McpServerStatus(spec.name(), spec.type(), connected, tools.size(), lastError);
        }
    }
}