package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.EntityActionPort;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.BlockTarget;
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

/** Breaks one block as an agent body. */
public final class BodyMineTool implements TianshuTool<BodyMineTool.Args, ActionResult> {

    public static final ToolId ID = ToolId.of("tianshu", "body.mine");

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
        return "Breaks the block at a position as an agent body that can mine.";
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
        properties.add("x", BodyToolSchemas.coordinate("Block x"));
        properties.add("y", BodyToolSchemas.coordinate("Block y"));
        properties.add("z", BodyToolSchemas.coordinate("Block z"));
        properties.add("dimension", BodyToolSchemas.dimension());
        return BodyToolSchemas.object(properties, "x", "y", "z");
    }

    @Override
    public CompletableFuture<ActionResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body(), ID);
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (args == null || (caller.kind() != ToolCaller.Kind.BODY
                && (args.body() == null || args.body().isBlank()))) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.mine needs a body id unless the caller is a body"));
        }
        EntityActor body = EntityActors.resolve(scope.actor().id()).orElse(null);
        if (body == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "agent body " + scope.actor().id() + " is not present"));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(EntityCapability.MINE);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        BlockTarget target = BodyTargets.block(BodyTargets.dimension(args.dimension(), body.position().dimension()), args.x(), args.y(), args.z());
        return ActorScope.guarded(scope.actor(), context,
                () -> TianshuRuntimePorts.find(EntityActionPort.class)
                        .map(port -> port.mine(scope.actor(), target).thenCompose(result ->
                                result.outcome() == ActionOutcome.REJECTED || result.outcome() == ActionOutcome.FAILED
                                        ? CompletableFuture.failedFuture(
                                                new ToolException(ToolError.FAILED, result.detail()))
                                        : CompletableFuture.completedFuture(result)))
                        .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                                "agent bodies act only on the server"))));
    }

    @Override
    public com.rheinmetal.tianshu.api.tool.ToolPolicy policy() {
        return com.rheinmetal.tianshu.api.tool.ToolPolicy.forCategory(category()).withTimeout(70_000L);
    }
}
