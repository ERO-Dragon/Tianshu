package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.ActorStatus;
import com.rheinmetal.tianshu.api.game.ActorStatusPort;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * How the acting body is doing: where it is, how healthy, how fed, in fire or water.
 *
 * <p>Atomic: it reports the actor's own state and nothing else. What it carries is {@code body.inventory}, what is
 * around it is {@code body.scan} — a caller that wants all three composes them, which is the module's business, not a
 * bigger tool here.</p>
 *
 * <p>An operator asking for its own status and a body asking for its own status take the same path: the caller is the
 * default actor, and an explicit {@code body} switches to that carrier.</p>
 */
public final class BodyStatusTool implements TianshuTool<BodyStatusTool.Args, ActorStatus> {

    /** {@code body} is optional: without it the caller's own status is read. */
    public record Args(String body) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "entity.status");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_READ;
    }

    @Override
    public ToolPolicy policy() {
        return ToolPolicy.forCategory(ToolCategory.SERVER_READ).withEnabledByDefault(true);
    }

    @Override
    public String description() {
        return "Reads the acting body's own state: position, dimension, health, hunger, air, fire, water.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return ActorStatus.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<ActorStatus> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return TianshuRuntimePorts.find(ActorStatusPort.class)
                .map(port -> port.status(scope.actor())
                        .thenCompose(status -> status.read()
                                ? CompletableFuture.completedFuture(status)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                                        status.message().isBlank() ? "the status could not be read" : status.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "actor status is only available on the server")));
    }
}