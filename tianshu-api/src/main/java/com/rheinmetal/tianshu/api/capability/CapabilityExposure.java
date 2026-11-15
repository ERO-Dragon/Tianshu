package com.rheinmetal.tianshu.api.capability;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The consumer side of the capability table: which rows to show a model and how to render them.
 *
 * <p>This is a reference exposure, not the only one. An AI module may build native function-calling definitions,
 * an MCP list or a prompt block; this helper keeps the common case (a text catalogue plus pointer-style resources)
 * in one tested place.</p>
 */
public final class CapabilityExposure {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CapabilityExposure() {
    }

    /**
     * The rows a consumer on {@code side} may expose to {@code caller}: reachable, permitted, not a connector.
     *
     * <p>Switched-off tools are included, carrying {@code attributes.enabled = false}: the catalogue's other
     * discovery path already lists them so a model can say "this exists but is turned off", and two views of the
     * same table disagreeing about whether a tool exists is worse than a slightly longer list.</p>
     *
     * <p>Every attribute read is tolerant. {@code attributes} is an open bag by contract, so a provider can put
     * anything under {@code autonomy}; an unreadable value hides that one row instead of failing the call, or one
     * third-party descriptor breaks every consumer's capability view.</p>
     */
    public static List<CapabilityDescriptor> exposed(ToolCaller caller, ToolSide side) {
        ToolAutonomy allowed = TianshuTools.autonomyFor(side, caller);
        List<CapabilityDescriptor> rows = new ArrayList<>();
        for (CapabilityDescriptor descriptor : CapabilityCatalog.list()) {
            if (descriptor.kind().equals(CapabilityKind.CONNECTOR)) {
                continue;
            }
            if (!reachable(descriptor, side)
                    || !permitted(descriptor, caller)
                    || !autonomyAllows(descriptor, allowed)) {
                continue;
            }
            rows.add(descriptor);
        }
        return List.copyOf(rows);
    }

    private static boolean reachable(CapabilityDescriptor descriptor, ToolSide side) {
        return descriptor.side() != ToolSide.SERVER || side == ToolSide.SERVER || TianshuTools.serverAvailable();
    }

    private static boolean permitted(CapabilityDescriptor descriptor, ToolCaller caller) {
        return descriptor.permission() != ToolPermission.OPERATOR || caller.operator();
    }

    /**
     * Whether the tier governing this side permits this row.
     *
     * <p>An unreadable tier hides the row: failing closed keeps a typo in someone else's metadata from widening
     * what the model is offered.</p>
     */
    private static boolean autonomyAllows(CapabilityDescriptor descriptor, ToolAutonomy allowed) {
        if (!descriptor.kind().isTool()) {
            return true;
        }
        JsonElement declared = descriptor.attributes().get("autonomy");
        if (declared == null || !declared.isJsonPrimitive()) {
            return true;
        }
        ToolAutonomy required;
        try {
            required = ToolAutonomy.valueOf(declared.getAsString().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException unreadable) {
            return false;
        }
        return allowed.permits(required);
    }

    /** Renders the exposed rows as a prompt block; connectors never appear. */
    public static String describe(List<CapabilityDescriptor> descriptors) {
        final int maxRows = 200;
        final int maxChars = 8_000;
        StringBuilder text = new StringBuilder();
        int rows = 0;
        for (CapabilityDescriptor descriptor : descriptors) {
            if (descriptor.kind().equals(CapabilityKind.CONNECTOR)) {
                continue;
            }
            if (rows >= maxRows || text.length() >= maxChars) {
                text.append("… (").append(descriptors.size() - rows).append(" more; narrow the list)\n");
                break;
            }
            String name = descriptor.attributes().has("name")
                    ? descriptor.attributes().get("name").getAsString() : "";
            if (descriptor.kind().equals(CapabilityKind.SKILL) && !name.isBlank()) {
                text.append("- ").append(descriptor.id()).append(" — ").append(name).append(" — ")
                        .append(descriptor.description());
                text.append('\n');
                rows++;
                continue;
            }
            text.append("- ").append(descriptor.id())
                    .append(" [").append(descriptor.kind()).append('/').append(descriptor.binding()).append("]: ")
                    .append(descriptor.description());
            rows++;
            JsonElement schema = descriptor.attributes().get("argsSchema");
            if (schema != null && schema.isJsonObject()) {
                text.append(" args: ").append(GSON.toJson(schema));
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** The text of a resolved resource/prompt/skill result, or an empty string when it is not text. */
    public static String textOf(JsonObject envelope) {
        if (envelope == null || !envelope.has("ok") || !envelope.get("ok").getAsBoolean()) {
            return "";
        }
        JsonElement text = envelope.get("text");
        return text == null || !text.isJsonPrimitive() ? "" : text.getAsString();
    }
}
