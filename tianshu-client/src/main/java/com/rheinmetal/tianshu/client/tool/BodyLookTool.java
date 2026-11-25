package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
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

/** Turns an agent body towards a point. */
public final class BodyLookTool implements TianshuTool<BodyLookTool.Args, ActionResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.look");

    public record Args(String body, int x, int y, int z, String dimension) {
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
        return "Turns an agent body towards a point in its current dimension.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return ActionResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("x", BodyToolSchemas.coordinate("Target x"));
        properties.add("y", BodyToolSchemas.coordinate("Target y"));
        properties.add("z", BodyToolSchemas.coordinate("Target z"));
        properties.add("dimension", BodyToolSchemas.dimension());
        return BodyToolSchemas.object(properties, "x", "y", "z");
    }

    @Override
    public CompletableFuture<ActionResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (args == null || (caller.kind() != ToolCaller.Kind.BODY
                && (args.body() == null || args.body().isBlank()))) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.look needs a body id unless the caller is a body"));
        }
        EntityActor body = EntityActors.resolve(scope.actor().id()).orElse(null);
        if (body == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "agent body " + scope.actor().id() + " is not present"));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(EntityCapability.LOOK);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        ActorPosition target = BodyTargets.position(BodyTargets.dimension(args.dimension(), body.position().dimension()), args.x(), args.y(), args.z());
        return TianshuRuntimePorts.find(EntityMotionPort.class)
                .map(port -> port.look(scope.actor(), target).thenCompose(result ->
                        result.outcome() == ActionOutcome.REJECTED || result.outcome() == ActionOutcome.FAILED
                                ? CompletableFuture.failedFuture(new ToolException(ToolError.FAILED, result.detail()))
                                : CompletableFuture.completedFuture(result)))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "agent bodies move only on the server")));
    }
}
