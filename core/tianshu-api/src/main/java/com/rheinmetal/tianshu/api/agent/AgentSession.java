package com.rheinmetal.tianshu.api.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityChange;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityExposure;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCallHandle;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolConcurrency;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The stable entry point an agent uses: discovery, JSON invocation, capability reads, cancellation and change
 * subscription. It composes the existing platform pieces and carries no Minecraft, loader or feature types.
 *
 * <p>This is deliberately a facade, not a second registry: everything here delegates to {@link TianshuTools} and
 * {@link CapabilityCatalog}, so an agent and a mod see exactly the same policy, limits and error envelopes.</p>
 */
public final class AgentSession {

    /** Bumped when the shape of {@link #describe()} changes. */
    public static final String PROTOCOL = "tianshu.agent/1";

    private final ToolCaller caller;
    private final ToolSide side;

    private AgentSession(ToolCaller caller, ToolSide side) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.side = Objects.requireNonNull(side, "side");
    }

    public static AgentSession client(ToolCaller caller) {
        return new AgentSession(caller, ToolSide.CLIENT);
    }

    public static AgentSession server(ToolCaller caller) {
        return new AgentSession(caller, ToolSide.SERVER);
    }

    public ToolCaller caller() {
        return caller;
    }

    public ToolSide side() {
        return side;
    }

    /** Tools this caller can actually call on this side, already filtered by permission and enablement. */
    public List<ToolSpec> tools() {
        return TianshuTools.catalog(caller, side);
    }

    /** Tool, skill, resource and prompt rows the caller may see; connectors are not exposed. */
    public List<CapabilityDescriptor> capabilities() {
        return CapabilityExposure.exposed(caller, side);
    }

    /** One machine-readable contract an external agent can fetch before its first call. */
    public JsonObject describe() {
        JsonObject document = new JsonObject();
        document.addProperty("protocol", PROTOCOL);
        document.addProperty("revision", CapabilityCatalog.revision());
        JsonObject identity = new JsonObject();
        identity.addProperty("kind", caller.kind().name());
        identity.addProperty("id", caller.id());
        identity.addProperty("operator", caller.operator());
        document.add("caller", identity);
        document.addProperty("side", side.name());

        JsonObject limits = new JsonObject();
        limits.addProperty("maxPending", ToolConcurrency.MAX_PENDING);
        limits.addProperty("maxInFlightPerCaller", ToolConcurrency.MAX_IN_FLIGHT_PER_CALLER);
        document.add("limits", limits);

        JsonArray toolRows = new JsonArray();
        for (ToolSpec spec : tools()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", spec.id().toString());
            row.addProperty("category", spec.category().name());
            row.addProperty("description", spec.description());
            row.addProperty("enabled", spec.enabled());
            if (spec.argsSchema() != null) {
                row.add("argsSchema", spec.argsSchema());
            }
            toolRows.add(row);
        }
        document.add("tools", toolRows);

        JsonArray capabilityRows = new JsonArray();
        for (CapabilityDescriptor row : capabilities()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", row.id().toString());
            object.addProperty("kind", row.kind().toString());
            object.addProperty("binding", row.binding().toString());
            object.addProperty("description", row.description());
            object.addProperty("permission", row.permission().name());
            capabilityRows.add(object);
        }
        document.add("capabilities", capabilityRows);
        return document;
    }

    /** Calls a tool and returns the standard envelope; errors are values, not dropped futures. */
    public CompletableFuture<JsonObject> call(ToolId id, JsonElement args) {
        return TianshuTools.callJson(ToolId.of(id.namespace(), id.name()), args, caller, side);
    }

    /** Same call with a handle, so the agent can cancel an in-flight server call. */
    public ToolCallHandle callHandled(ToolId id, JsonElement args) {
        return TianshuTools.callHandled(id, args, caller, side);
    }

    /**
     * Reads a capability by id: a tool goes through the tool path (schema and policy apply); a skill, resource or
     * prompt goes through the capability table and returns its text envelope.
     */
    public CompletableFuture<JsonObject> read(CapabilityId id, JsonObject args) {
        CapabilityDescriptor descriptor = CapabilityCatalog.find(id).orElse(null);
        if (descriptor != null && descriptor.kind().isTool()) {
            return TianshuTools.callJson(ToolId.of(id.namespace(), id.name()), args, caller, side);
        }
        return CapabilityCatalog.invoke(id, args, caller, side);
    }

    /** Subscribes to in-process capability changes; the returned runnable unsubscribes. */
    public Runnable onChange(CapabilityChange.Listener listener) {
        return CapabilityCatalog.subscribe(listener);
    }

    /** Best effort, as documented on {@link ToolCallHandle#cancel}. */
    public boolean cancel(String requestId) {
        return TianshuTools.cancel(requestId);
    }
}
