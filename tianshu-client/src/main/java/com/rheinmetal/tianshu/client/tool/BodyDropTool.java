package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.DropResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Drops part of one inventory slot at the body's feet. */
public final class BodyDropTool implements TianshuTool<BodyDropTool.Args, DropResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.drop");

    private static final int MAIN_SLOTS = 36;

    /** {@code count <= 0} drops the whole stack. */
    public record Args(String body, int slot, int count) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BODY_WRITE;
    }

    @Override
    public String description() {
        return "Drops items from one inventory slot at the body's feet.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return DropResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("slot", BodyToolSchemas.integer("Inventory slot 0..35"));
        properties.add("count", BodyToolSchemas.integer("How many to drop; 0 or omitted means the whole stack"));
        return BodyToolSchemas.object(properties, "slot");
    }

    @Override
    public CompletableFuture<DropResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.body(caller, args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (scope.actor().kind() != ActorRef.Kind.BODY) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.drop needs an agent body"));
        }
        if (args == null || args.slot() < 0 || args.slot() >= MAIN_SLOTS) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "slot must be 0..35"));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(EntityCapability.INVENTORY);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        ActorRef actor = scope.actor();
        int slot = args.slot();
        int count = Math.max(0, args.count());
        return TianshuRuntimePorts.find(EntityItemPort.class)
                .map(port -> port.drop(actor, slot, count))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "dropping is only available on the server")));
    }
}