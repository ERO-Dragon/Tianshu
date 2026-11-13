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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 */
public final class CapabilityCatalog {

    private static final long DEFAULT_TIMEOUT_MILLIS = 5_000L;
    private static final String PROVIDER_KERNEL = "tianshu";

    private record Entry(CapabilityDescriptor descriptor, CapabilityHandler handler) {
    }

    private static final Map<CapabilityId, Entry> ENTRIES = new ConcurrentHashMap<>();

    private CapabilityCatalog() {
    }

    /** Publishes a non-tool capability. Tools register through {@link TianshuTools#register}. */
    public static void register(CapabilityDescriptor descriptor, CapabilityHandler handler) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(handler, "handler");
        if (descriptor.kind().isTool()) {
            throw new IllegalArgumentException("tools are registered through TianshuTools, not the capability table");
        }
        Entry existing = ENTRIES.putIfAbsent(descriptor.id(), new Entry(descriptor, handler));
        if (existing != null) {
            throw new IllegalArgumentException("capability " + descriptor.id() + " is already registered");
        }
    }

    /** Removes a capability; the entry disappears from every consumer's view immediately. */
    public static void unregister(CapabilityId id) {
        ENTRIES.remove(id);
    }

    public static Optional<CapabilityDescriptor> find(CapabilityId id) {
        Entry entry = ENTRIES.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.descriptor());
    }

    public static List<CapabilityDescriptor> list() {
        List<CapabilityDescriptor> descriptors = new ArrayList<>(ENTRIES.size());
        for (Entry entry : ENTRIES.values()) {
            descriptors.add(entry.descriptor());
        }
        descriptors.sort(Comparator.comparing(CapabilityDescriptor::id));
        return List.copyOf(descriptors);
    }

    public static List<CapabilityDescriptor> list(CapabilityKind kind) {
        return list().stream().filter(descriptor -> descriptor.kind().equals(kind)).toList();
    }

    /**
     * Invokes one capability and returns the tool/agent envelope
     * ({@code {"ok":true,...}} / {@code {"ok":false,"code":...}}).
     */
    public static CompletableFuture<JsonObject> invoke(CapabilityId id, JsonObject args, ToolCaller caller,
                                                       ToolSide side) {
        try {
            Entry entry = ENTRIES.get(id);
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
            long timeout = timeoutOf(descriptor);
            return handler.invoke(new CapabilityContext(caller, side), safeArgs)
                    .orTimeout(timeout, TimeUnit.MILLISECONDS)
                    .exceptionallyCompose(failure -> CompletableFuture.completedFuture(failureOf(failure)));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(error("FAILED", String.valueOf(failure)));
        }
    }

    /** Kernel use: mirrors one tool into the table. Not part of the consumer contract. */
    public static void publishTool(TianshuTool<?, ?> tool) {
        Objects.requireNonNull(tool, "tool");
        JsonObject attributes = new JsonObject();
        if (tool.argsSchema() != null) {
            attributes.add("argsSchema", tool.argsSchema().deepCopy());
        }
        attributes.addProperty("category", tool.category().name().toLowerCase(java.util.Locale.ROOT));
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
        Entry existing = ENTRIES.get(descriptor.id());
        if (existing != null && existing.handler() != null) {
            throw new IllegalArgumentException(
                    "capability id " + descriptor.id() + " is already used by a non-tool capability");
        }
        ENTRIES.put(descriptor.id(), new Entry(descriptor, null));
    }

    /** Kernel use: drops a mirrored tool entry. Not part of the consumer contract. */
    public static void removeTool(CapabilityId id) {
        ENTRIES.remove(id);
    }

    /** Tests only. */
    public static void clear() {
        ENTRIES.clear();
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
