package com.rheinmetal.tianshu.api.capability;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomyGate;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.List;

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

    /** The rows a consumer on {@code side} may expose to {@code caller}: reachable, permitted, not a connector. */
    public static List<CapabilityDescriptor> exposed(ToolCaller caller, ToolSide side) {
        return CapabilityCatalog.list().stream()
                .filter(descriptor -> !descriptor.kind().equals(CapabilityKind.CONNECTOR))
                .filter(descriptor -> side != ToolSide.CLIENT || !descriptor.kind().isTool()
                        || ToolAutonomyGate.writeToolsVisible()
                        || !"server_write".equals(descriptor.attributes().has("category")
                                ? descriptor.attributes().get("category").getAsString() : ""))
                .filter(descriptor -> !descriptor.kind().isTool()
                        || !descriptor.attributes().has("enabled")
                        || descriptor.attributes().get("enabled").getAsBoolean())
                .filter(descriptor -> descriptor.side() != ToolSide.SERVER
                        || side == ToolSide.SERVER
                        || TianshuTools.serverAvailable())
                .filter(descriptor -> descriptor.permission() != ToolPermission.OPERATOR || caller.operator())
                .toList();
    }

    /** Renders the exposed rows as a prompt block; connectors never appear. */
    public static String describe(List<CapabilityDescriptor> descriptors) {
        StringBuilder text = new StringBuilder();
        for (CapabilityDescriptor descriptor : descriptors) {
            if (descriptor.kind().equals(CapabilityKind.CONNECTOR)) {
                continue;
            }
            text.append("- ").append(descriptor.id())
                    .append(" [").append(descriptor.kind()).append('/').append(descriptor.binding()).append("]: ")
                    .append(descriptor.description());
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
