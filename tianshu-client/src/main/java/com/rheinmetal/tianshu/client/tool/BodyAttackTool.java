package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.EntityActionPort;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EntityTarget;
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

/** Strikes one entity as an agent body. */
public final class BodyAttackTool implements TianshuTool<BodyAttackTool.Args, ActionResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.attack");

    public record Args(String body, String entity) {
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
        return "Strikes an entity as an agent body that can attack. A body without damage still succeeds with no "
                + "effect.";
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
        properties.add("entity", BodyToolSchemas.text("Entity id (UUID)"));
        return BodyToolSchemas.object(properties, "entity");
    }

    @Override
    public CompletableFuture<ActionResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.body(caller, args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (args == null || args.entity() == null || args.entity().isBlank()
                || (caller.kind() != ToolCaller.Kind.BODY && (args.body() == null || args.body().isBlank()))) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.attack needs an entity id and, unless the caller is a body, a body id"));
        }
        EntityActor body = EntityActors.resolve(scope.actor().id()).orElse(null);
        if (body == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "agent body " + scope.actor().id() + " is not present"));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(EntityCapability.ATTACK);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        return TianshuRuntimePorts.find(EntityActionPort.class)
                .map(port -> port.attack(scope.actor(), new EntityTarget(args.entity())).thenCompose(result ->
                        result.outcome() == ActionOutcome.REJECTED || result.outcome() == ActionOutcome.FAILED
                                ? CompletableFuture.failedFuture(new ToolException(ToolError.FAILED, result.detail()))
                                : CompletableFuture.completedFuture(result)))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "agent bodies act only on the server")));
    }
}
