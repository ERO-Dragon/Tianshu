package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * The capability table: one registry for tools, skills, resources, prompts and connectors (and whatever kind a
 * provider introduces next).
 *
 * <p>The table holds descriptors and handlers, never content. A consumer asks what exists, renders what it
 * understands, and calls back by id; the provider executes. Tools are mirrored here from {@link TianshuTools} so a
 * single query can see every capability, but tool execution still goes through the tool registry — one executor,
 * one policy path.</p>
 *
 * <p>Consumer-facing rules stay here rather than in every AI module: permission, side reachability and timeouts are
 * checked once at invocation.</p>
 *
 * <p>State is one immutable table per revision, swapped atomically. A consumer that reads the revision and the rows
 * must not see them disagree, and the earlier shape — a mutable map beside a separate counter — allowed exactly that:
 * a describe() call took the counter, another thread registered something, and the document claimed a version older
 * than its own content.</p>
 */
public final class CapabilityCatalog {

    private static final long DEFAULT_TIMEOUT_MILLIS = 5_000L;
    private static final String PROVIDER_KERNEL = "tianshu";

    /**
     * A consumer's view of the table: the revision and the rows it belongs to, read as one.
     *
     * @param revision    monotonic change counter, taken at the same instant as {@code descriptors}
     * @param descriptors every row, ordered by id
     */
    public record Snapshot(long revision, List<CapabilityDescriptor> descriptors) {
    }

    private record Entry(CapabilityDescriptor descriptor, CapabilityHandler handler) {
    }

    /** The stored state: what a consumer sees, plus the handlers it may not. */
    private static final class Table {
        private final long revision;
        private final List<CapabilityDescriptor> descriptors;
        private final Map<CapabilityId, Entry> entries;

        private Table(long revision, List<CapabilityDescriptor> descriptors, Map<CapabilityId, Entry> entries) {
            this.revision = revision;
            this.descriptors = descriptors;
            this.entries = entries;
        }

        private static Table empty() {
            return new Table(0L, List.of(), Map.of());
        }

        private Table with(Entry added) {
            Map<CapabilityId, Entry> next = new LinkedHashMap<>(entries);
            next.put(added.descriptor().id(), added);
            return new Table(revision + 1, ordered(next), Map.copyOf(next));
        }

        private Table without(CapabilityId id) {
            Map<CapabilityId, Entry> next = new LinkedHashMap<>(entries);
            next.remove(id);
            return new Table(revision + 1, ordered(next), Map.copyOf(next));
        }

        private Table cleared() {
            return new Table(revision + 1, List.of(), Map.of());
        }

        private static List<CapabilityDescriptor> ordered(Map<CapabilityId, Entry> entries) {
            List<CapabilityDescriptor> rows = new ArrayList<>(entries.size());
            for (Entry entry : entries.values()) {
                rows.add(entry.descriptor());
            }
            rows.sort(Comparator.comparing(CapabilityDescriptor::id));
            return List.copyOf(rows);
        }
    }

    private static final AtomicReference<Table> TABLE = new AtomicReference<>(Table.empty());
    private static final List<CapabilityChange.Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Where non-tool handlers run.
     *
     * <p>A handler is allowed to be synchronous — {@code McpBridge}, {@code TextCapabilityProvider} and
     * {@code SkillFileProvider} all do their I/O before returning a future — and the five-second budget could not
     * bound that, because {@code orTimeout} only governs a future that already exists. Running the invocation here
     * makes the budget real: the caller gets its future immediately, and a handler stuck on a dead socket costs a
     * worker instead of the game thread.</p>
     */
    private static final ExecutorService HANDLERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tianshu-capability");
        thread.setDaemon(true);
        return thread;
    });

    private CapabilityCatalog() {
    }

    /** Publishes a non-tool capability. Tools register through {@link TianshuTools#register}. */
    public static void register(CapabilityDescriptor descriptor, CapabilityHandler handler) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(handler, "handler");
        if (descriptor.kind().isTool()) {
            throw new IllegalArgumentException("tools are registered through TianshuTools, not the capability table");
        }
        Entry entry = new Entry(descriptor, handler);
        update(table -> {
            if (table.entries.containsKey(descriptor.id())) {
                throw new IllegalArgumentException("capability " + descriptor.id() + " is already registered");
            }
            return table.with(entry);
        });
        notifyListeners(CapabilityChange.Action.ADDED, descriptor);
    }

    /** Removes a capability; the entry disappears from every consumer's view immediately. */
    public static void unregister(CapabilityId id) {
        CapabilityDescriptor removed = remove(id);
        if (removed != null) {
            notifyListeners(CapabilityChange.Action.REMOVED, removed);
        }
    }

    /** Kernel use: drops a mirrored tool entry. Not part of the consumer contract. */
    public static void removeTool(CapabilityId id) {
        unregister(id);
    }

    /**
     * Subscribes to in-process changes and returns the unsubscriber. A listener must not throw across the registry;
     * a throwing listener is isolated so one consumer cannot break registration for everyone else.
     */
    public static Runnable subscribe(CapabilityChange.Listener listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** Monotonic change counter: consumers can poll it and refresh their view when it moves. */
    public static long revision() {
        return TABLE.get().revision;
    }

    /** The revision and the rows it belongs to, read as one. */
    public static Snapshot snapshot() {
        Table table = TABLE.get();
        return new Snapshot(table.revision, table.descriptors);
    }

    public static Optional<CapabilityDescriptor> find(CapabilityId id) {
        Entry entry = TABLE.get().entries.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.descriptor());
    }

    public static List<CapabilityDescriptor> list() {
        return TABLE.get().descriptors;
    }

    public static List<CapabilityDescriptor> list(CapabilityKind kind) {
        return list().stream().filter(descriptor -> descriptor.kind().equals(kind)).toList();
    }

    /**
     * Invokes one capability and returns the tool/agent envelope
     * ({@code {"ok":true,...}} / {@code {"ok":false,"code":...}}).
     *
     * <p>The handler runs on {@link #HANDLERS}, not on the calling thread: see that field's note on why the timeout
     * only means something this way.</p>
     */
    public static CompletableFuture<JsonObject> invoke(CapabilityId id, JsonObject args, ToolCaller caller,
                                                       ToolSide side) {
        Entry entry = TABLE.get().entries.get(id);
        if (entry == null) {
            return CompletableFuture.completedFuture(error("UNKNOWN_CAPABILITY", "no capability named " + id));
        }
        CapabilityDescriptor descriptor = entry.descriptor();
        if (descriptor.permission() == ToolPermission.OPERATOR && !caller.operator()) {
            return CompletableFuture.completedFuture(error("NO_PERMISSION", id + " needs an operator"));
        }
        if (descriptor.side() == ToolSide.SERVER && side == ToolSide.CLIENT && !TianshuTools.serverAvailable()) {
            return CompletableFuture.completedFuture(error("UNSUPPORTED_SIDE",
                    id + " needs the Tianshu server component"));
        }
        if (descriptor.kind().isTool()) {
            return TianshuTools.callJson(ToolId.parse(id.toString()), args, caller, side);
        }
        CapabilityHandler handler = entry.handler();
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        CapabilityContext context = new CapabilityContext(caller, side);
        return CompletableFuture.supplyAsync(() -> handler.invoke(context, safeArgs), HANDLERS)
                .thenCompose(invocation -> invocation)
                .orTimeout(timeoutOf(descriptor), TimeUnit.MILLISECONDS)
                .exceptionallyCompose(failure -> CompletableFuture.completedFuture(failureOf(failure)));
    }

    /** Kernel use: mirrors one tool into the table. Not part of the consumer contract. */
    public static void publishTool(TianshuTool<?, ?> tool) {
        Objects.requireNonNull(tool, "tool");
        JsonObject attributes = new JsonObject();
        if (tool.argsSchema() != null) {
            attributes.add("argsSchema", tool.argsSchema().deepCopy());
        }
        attributes.addProperty("category", tool.category().name().toLowerCase(java.util.Locale.ROOT));
        attributes.addProperty("enabled", tool.policy().enabledByDefault());
        attributes.addProperty("autonomy", tool.requiredAutonomy().name());
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                CapabilityId.parse(tool.id().toString()),
                CapabilityKind.TOOL,
                CapabilityBinding.LOCAL,
                tool.description(),
                tool.category().isServerSide() ? ToolSide.SERVER : ToolSide.CLIENT,
                tool.policy().permission(),
                PROVIDER_KERNEL,
                attributes
        );
        CapabilityChange.Action[] action = {CapabilityChange.Action.ADDED};
        // A tool may refresh its own row; what it must not do is take over a row owned by a non-tool capability, whose
        // handler would be dropped and whose id would start routing into the tool registry. Decided and swapped in one
        // step — the earlier get-then-put let a capability registered in between be overwritten.
        update(table -> {
            Entry existing = table.entries.get(descriptor.id());
            if (existing != null && existing.handler() != null) {
                throw new IllegalArgumentException(
                        "capability id " + descriptor.id() + " is already used by a non-tool capability");
            }
            action[0] = existing == null ? CapabilityChange.Action.ADDED : CapabilityChange.Action.UPDATED;
            return table.with(new Entry(descriptor, null));
        });
        notifyListeners(action[0], descriptor);
    }

    /** Kernel use: keeps the mirrored tool's enabled flag in step with {@link TianshuTools}. */
    public static void setToolEnabled(ToolId id, boolean enabled) {
        CapabilityId capabilityId = CapabilityId.parse(id.toString());
        Entry entry = TABLE.get().entries.get(capabilityId);
        if (entry == null || !entry.descriptor().kind().isTool()) {
            return;
        }
        JsonObject attributes = entry.descriptor().attributes().deepCopy();
        attributes.addProperty("enabled", enabled);
        CapabilityDescriptor updated = new CapabilityDescriptor(entry.descriptor().id(), entry.descriptor().kind(),
                entry.descriptor().binding(), entry.descriptor().description(), entry.descriptor().side(),
                entry.descriptor().permission(), entry.descriptor().provider(), attributes);
        update(table -> table.with(new Entry(updated, null)));
        notifyListeners(CapabilityChange.Action.UPDATED, updated);
    }

    /** Tests only. */
    public static void clear() {
        Table removed;
        Table empty;
        do {
            removed = TABLE.get();
            empty = removed.cleared();
        } while (!TABLE.compareAndSet(removed, empty));
        // The table is empty before anyone is told about it: a listener that consults the registry while handling
        // REMOVED used to find the very entry it was being told had gone.
        for (CapabilityDescriptor descriptor : removed.descriptors) {
            notifyListeners(CapabilityChange.Action.REMOVED, descriptor);
        }
    }

    /** Applies one change atomically; callers notify listeners afterwards. */
    private static void update(UnaryOperator<Table> change) {
        Table current;
        Table next;
        do {
            current = TABLE.get();
            next = change.apply(current);
        } while (!TABLE.compareAndSet(current, next));
    }

    /** @return the removed descriptor, or null when nothing was registered under that id */
    private static CapabilityDescriptor remove(CapabilityId id) {
        CapabilityDescriptor[] removed = new CapabilityDescriptor[1];
        update(table -> {
            Entry entry = table.entries.get(id);
            if (entry == null) {
                removed[0] = null;
                return table;
            }
            removed[0] = entry.descriptor();
            return table.without(id);
        });
        return removed[0];
    }

    private static void notifyListeners(CapabilityChange.Action action, CapabilityDescriptor descriptor) {
        for (CapabilityChange.Listener listener : LISTENERS) {
            try {
                listener.onChange(new CapabilityChange(action, descriptor));
            } catch (RuntimeException ignored) {
                // A consumer's bug must not break registration for everyone else.
            }
        }
    }

    private static long timeoutOf(CapabilityDescriptor descriptor) {
        JsonObject attributes = descriptor.attributes();
        if (attributes.has("timeoutMillis") && attributes.get("timeoutMillis").isJsonPrimitive()) {
            long value = attributes.get("timeoutMillis").getAsLong();
            if (value > 0) {
                return value;
            }
        }
        return DEFAULT_TIMEOUT_MILLIS;
    }

    private static JsonObject failureOf(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof TimeoutException) {
            return error("TIMEOUT", "the capability did not answer in time");
        }
        return error("FAILED", cause.getMessage() == null ? cause.toString() : cause.getMessage());
    }

    private static JsonObject error(String code, String message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", false);
        envelope.addProperty("code", code);
        envelope.addProperty("message", message == null ? code : message);
        return envelope;
    }
}