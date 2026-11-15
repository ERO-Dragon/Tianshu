package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentActionPort;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.EntityTarget;
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

    public static final ToolId ID = ToolId.of("tianshu", "body.attack");

    public record Args(String body, String entity) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_WRITE;
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
        if (caller.kind() != ToolCaller.Kind.PLAYER && caller.kind() != ToolCaller.Kind.BODY) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.NO_PERMISSION,
                    "body.attack runs as a player or a body"));
        }
        if (args == null || args.body() == null || args.body().isBlank()
                || args.entity() == null || args.entity().isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.attack needs a body id and an entity id"));
        }
        AgentBody body = AgentBodies.resolve(args.body()).orElse(null);
        if (body == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "agent body " + args.body() + " is not present"));
        }
        ActorRef actor = ActorRef.body(body.id(), caller.operator());
        Optional<ToolException> refusal = AgentBodies.checkCapability(actor, BodyCapability.ATTACK);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        return TianshuRuntimePorts.find(AgentActionPort.class)
                .map(port -> port.attack(actor, new EntityTarget(args.entity())).thenCompose(result ->
                        result.outcome() == ActionOutcome.REJECTED || result.outcome() == ActionOutcome.FAILED
                                ? CompletableFuture.failedFuture(new ToolException(ToolError.FAILED, result.detail()))
                                : CompletableFuture.completedFuture(result)))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "agent bodies act only on the server")));
    }
}