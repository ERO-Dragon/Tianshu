package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.ActionOutcome;
import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.AgentMotionPort;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.MoveMode;
import com.rheinmetal.tianshu.api.agent.MoveOptions;
import com.rheinmetal.tianshu.api.agent.MoveResult;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Moves an agent body to a position, by whatever means that carrier supports. */
public final class BodyMoveTool implements TianshuTool<BodyMoveTool.Args, MoveResult> {

    public static final ToolId ID = ToolId.of("tianshu", "body.move");

    public record Args(String body, int x, int y, int z, String mode) {
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
        return "Moves an agent body to a position. mode may be walk, fly, teleport or any; a carrier that cannot "
                + "move that way refuses instead of improvising.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return MoveResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("x", BodyToolSchemas.integer("Target x"));
        properties.add("y", BodyToolSchemas.integer("Target y"));
        properties.add("z", BodyToolSchemas.integer("Target z"));
        JsonArray modes = new JsonArray();
        for (MoveMode mode : MoveMode.values()) {
            modes.add(mode.name().toLowerCase(Locale.ROOT));
        }
        JsonObject mode = BodyToolSchemas.text("Movement mode; defaults to any");
        mode.add("enum", modes);
        properties.add("mode", mode);
        return BodyToolSchemas.object(properties, "x", "y", "z");
    }

    @Override
    public CompletableFuture<MoveResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body(), ID);
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (args == null || (caller.kind() != ToolCaller.Kind.BODY
                && (args.body() == null || args.body().isBlank()))) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.move needs a body id unless the caller is a body"));
        }
        AgentBody body = AgentBodies.resolve(scope.actor().id()).orElse(null);
        if (body == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                    "agent body " + scope.actor().id() + " is not present"));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(BodyCapability.MOVE);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        MoveMode mode;
        try {
            mode = args.mode() == null || args.mode().isBlank()
                    ? MoveMode.ANY
                    : MoveMode.valueOf(args.mode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownMode) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "unknown movement mode: " + args.mode()));
        }
        ActorPosition target = new ActorPosition(body.position().dimension(), args.x(), args.y(), args.z());
        return TianshuRuntimePorts.find(AgentMotionPort.class)
                .map(port -> port.move(scope.actor(), target, new MoveOptions(mode, MoveOptions.DEFAULT.arriveRadius()))
                        .thenCompose(result -> result.outcome() == ActionOutcome.REJECTED
                                || result.outcome() == ActionOutcome.FAILED
                                ? CompletableFuture.failedFuture(new ToolException(ToolError.FAILED, result.detail()))
                                : CompletableFuture.completedFuture(result)))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "agent bodies move only on the server")));
    }
}
