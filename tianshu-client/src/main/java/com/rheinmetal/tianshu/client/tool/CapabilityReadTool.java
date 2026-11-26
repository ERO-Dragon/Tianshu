package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityAccess;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityExposure;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.capability.skill.SkillReferences;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolError;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reads a non-tool capability: a skill's text, a resource, a prompt template.
 *
 * <p>With no id it lists what the caller may read; with an id it resolves it through the capability table and
 * returns the text. For a tool it reads the attached reference without executing the tool.</p>
 */
public final class CapabilityReadTool implements TianshuTool<CapabilityReadTool.Request, CapabilityReadTool.Reply> {

    public static final ToolId ID = ToolId.parse(CapabilityAccess.DOCUMENT_READER.toString());

    public record Request(String id, JsonObject arguments, String file, Integer page) {
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
                + "Pass a tool id to read its reference.";
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
        JsonObject file = new JsonObject();
        file.addProperty("type", "string");
        file.addProperty("description", "Reference file inside the skill, e.g. references/bosses.md.");
        properties.add("file", file);
        JsonObject page = new JsonObject();
        page.addProperty("type", "integer");
        page.addProperty("description", "Page of a long text, starting at 1.");
        properties.add("page", page);
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
            return CompletableFuture.failedFuture(new ToolException(
                    ToolError.INVALID_ARGS,
                    "malformed capability id: " + request.id()));
        }
        Optional<CapabilityDescriptor> descriptor = CapabilityCatalog.find(capabilityId);
        if (descriptor.isEmpty() || !CapabilityAccess.visibleTo(descriptor.get(), caller)) {
            return CompletableFuture.failedFuture(new ToolException(
                    ToolError.UNKNOWN_TOOL,
                    "no capability named " + capabilityId));
        }
        if (descriptor.get().kind().equals(CapabilityKind.TOOL)) {
            // A tool is called directly, but it may still ship a document: one tool, one file, found from its id in
            // the references of the skill that ships it.
            var documentation = SkillReferences.of(capabilityId);
            if (documentation.isPresent()) {
                return CompletableFuture.completedFuture(new Reply(documentation.get().text()));
            }
            return CompletableFuture.failedFuture(new ToolException(
                    ToolError.INVALID_ARGS,
                    "capability " + capabilityId + " is a tool with no document (expected "
                            + SkillReferences.pathOf(capabilityId) + " under the skill that ships it); call it directly"));
        }
        var kind = descriptor.get().kind();
        if (!CapabilityKind.SKILL.equals(kind) && !CapabilityKind.RESOURCE.equals(kind)
                && !CapabilityKind.PROMPT.equals(kind)) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "capability has no readable document: " + capabilityId));
        }
        JsonObject args = request.arguments() == null ? new JsonObject() : request.arguments().deepCopy();
        if (request.file() != null && !request.file().isBlank()) {
            args.addProperty("file", request.file());
        }
        if (request.page() != null) {
            args.addProperty("page", request.page());
        }
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
                    return CompletableFuture.failedFuture(new ToolException(
                            ToolError.fromWire(
                                    code, ToolError.FAILED),
                            code + ": " + message));
                });
    }
}
