package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
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

    public static final ToolId ID = ToolId.of("tianshu", "body.look");

    public record Args(String body, int x, int y, int z) {
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
        properties.add("x", BodyToolSchemas.integer("Target x"));
        properties.add("y", BodyToolSchemas.integer("Target y"));
        properties.add("z", BodyToolSchemas.integer("Target z"));
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
                    "body.look needs a body id unless the caller is a body"));
        }
        AgentBody body = AgentBodies.resolve(scope.actor().id()).orElse(null);
        if (body == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "agent body " + scope.actor().id() + " is not present"));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(BodyCapability.LOOK);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        ActorPosition target = new ActorPosition(body.position().dimension(), args.x(), args.y(), args.z());
        return TianshuRuntimePorts.find(AgentMotionPort.class)
                .map(port -> port.look(scope.actor(), target).thenCompose(result ->
                        result.outcome() == ActionOutcome.REJECTED || result.outcome() == ActionOutcome.FAILED
                                ? CompletableFuture.failedFuture(new ToolException(ToolError.FAILED, result.detail()))
                                : CompletableFuture.completedFuture(result)))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "agent bodies move only on the server")));
    }
}
