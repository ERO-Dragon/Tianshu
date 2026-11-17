package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.DropResult;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
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

/**
 * Hands items from a body's inventory to a player or entity within reach.
 *
 * <p>Dropping at its own feet is not the same act: handing something over is aimed at someone. The stack lands at the
 * receiver's feet, so this grants nothing over their inventory.</p>
 */
public final class BodyGiveTool implements TianshuTool<BodyGiveTool.Args, DropResult> {

    public static final ToolId ID = ToolId.of("tianshu", "body.give");

    private static final int MAIN_SLOTS = 36;

    /** {@code count <= 0} hands over the whole stack. */
    public record Args(String body, int slot, int count, String target) {
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
        return "Hands items from one inventory slot to a player (by name) or an entity (by id) within reach; the stack "
                + "lands at their feet. count 0 or omitted hands over the whole stack.";
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
        properties.add("count", BodyToolSchemas.integer("How many to hand over; 0 or omitted means the whole stack"));
        properties.add("target", BodyToolSchemas.text("Player name or entity id to hand the items to"));
        return BodyToolSchemas.object(properties, "slot", "target");
    }

    @Override
    public CompletableFuture<DropResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body(), ID);
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (scope.actor().kind() != ActorRef.Kind.BODY) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.give needs an agent body"));
        }
        if (args == null || args.slot() < 0 || args.slot() >= MAIN_SLOTS) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS, "slot must be 0..35"));
        }
        String target = args.target() == null ? "" : args.target().trim();
        if (target.isEmpty()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "body.give needs a player name or an entity id as the target"));
        }
        Optional<ToolException> refusal = scope.capabilityRefusal(EntityCapability.INVENTORY);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        ActorRef actor = scope.actor();
        int slot = args.slot();
        int count = Math.max(0, args.count());
        return TianshuRuntimePorts.find(EntityItemPort.class)
                .map(port -> port.handTo(actor, slot, count, target))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "handing items over is only available on the server")));
    }
}