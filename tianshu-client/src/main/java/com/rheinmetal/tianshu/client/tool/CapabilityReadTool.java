package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityExposure;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reads a non-tool capability: a skill's text, a resource, a prompt template.
 *
 * <p>With no id it lists what the caller may read; with an id it resolves it through the capability table and
 * returns the text. Tools are refused on purpose — they have their own typed call path and executing one through
 * this tool would duplicate policy and results.</p>
 */
public final class CapabilityReadTool implements TianshuTool<CapabilityReadTool.Request, CapabilityReadTool.Reply> {

    public static final ToolId ID = ToolId.of("tianshu", "capability.read");

    public record Request(String id, JsonObject arguments) {
    }

    public record Reply(String text) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.LOCAL;
    }

    @Override
    public String description() {
        return "Lists or reads a skill, resource or prompt from the capability table. "
                + "Call it without an id to see what is available; pass the id to get its text. "
                + "Tools are not read here — they are called directly.";
    }

    @Override
    public Type argsType() {
        return Request.class;
    }

    @Override
    public Type resultType() {
        return Reply.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description", "Capability id (namespace:name). Omit to list available capabilities.");
        properties.add("id", id);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("type", "object");
        arguments.addProperty("description", "Arguments for a skill or prompt template.");
        properties.add("arguments", arguments);
        schema.add("properties", properties);
        return schema;
    }

    @Override
    public CompletableFuture<Reply> invoke(ToolContext context, Request request) {
        ToolCaller caller = context.caller();
        ToolSide side = context.side();
        if (request == null || request.id() == null || request.id().isBlank()) {
            String listing = CapabilityExposure.describe(CapabilityExposure.exposed(caller, side));
            return CompletableFuture.completedFuture(new Reply(listing.isBlank()
                    ? "No skills, resources or prompts are available on this side." : listing));
        }
        CapabilityId capabilityId;
        try {
            capabilityId = CapabilityId.parse(request.id().trim());
        } catch (RuntimeException malformed) {
            return CompletableFuture.failedFuture(new com.rheinmetal.tianshu.api.tool.ToolException(
                    com.rheinmetal.tianshu.api.tool.ToolError.INVALID_ARGS,
                    "malformed capability id: " + request.id()));
        }
        Optional<CapabilityDescriptor> descriptor = CapabilityCatalog.find(capabilityId);
        if (descriptor.isEmpty()) {
            return CompletableFuture.failedFuture(new com.rheinmetal.tianshu.api.tool.ToolException(
                    com.rheinmetal.tianshu.api.tool.ToolError.UNKNOWN_TOOL,
                    "no capability named " + capabilityId));
        }
        if (descriptor.get().kind().equals(CapabilityKind.TOOL)) {
            return CompletableFuture.failedFuture(new com.rheinmetal.tianshu.api.tool.ToolException(
                    com.rheinmetal.tianshu.api.tool.ToolError.INVALID_ARGS,
                    "capability " + capabilityId + " is a tool; call it directly"));
        }
        JsonObject args = request.arguments() == null ? new JsonObject() : request.arguments();
        return CapabilityCatalog.invoke(capabilityId, args, caller, side)
                .thenCompose(envelope -> {
                    if (envelope.has("ok") && envelope.get("ok").getAsBoolean()) {
                        String text = CapabilityExposure.textOf(envelope);
                        if (text.isBlank() && envelope.has("data")) {
                            text = envelope.get("data").toString();
                        }
                        return CompletableFuture.completedFuture(new Reply(text));
                    }
                    String code = envelope.has("code") ? envelope.get("code").getAsString() : "FAILED";
                    String message = envelope.has("message") ? envelope.get("message").getAsString() : code;
                    return CompletableFuture.failedFuture(new com.rheinmetal.tianshu.api.tool.ToolException(
                            com.rheinmetal.tianshu.api.tool.ToolError.valueOf(code),
                            code + ": " + message));
                });
    }
}
