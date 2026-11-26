package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.ContainerTransferResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Moves items between a container and a body's inventory.
 *
 * <p>Body side of the split pair; {@link ContainerTransferTool} is the player side. The world operation itself lives in
 * {@link TransferOps}: this tool only adapts its arguments and calls in. Both slots belong to the body, which is why
 * the actor is not optional here.</p>
 */
public final class BodyTransferTool implements TianshuTool<BodyTransferTool.Args, ContainerTransferResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.transfer");

    public record Args(String body, int x, int y, int z, int containerSlot, int playerSlot, int count,
                       boolean toPlayer) {
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
        return "Makes an agent body move items between one slot of a container at the given position and one slot of "
                + "its own inventory.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return ContainerTransferResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("x", BodyToolSchemas.integer("Container x coordinate"));
        properties.add("y", BodyToolSchemas.integer("Container y coordinate"));
        properties.add("z", BodyToolSchemas.integer("Container z coordinate"));
        properties.add("containerSlot", BodyToolSchemas.integer("Slot inside the container"));
        properties.add("playerSlot", BodyToolSchemas.integer("Slot inside the body inventory, 0..35"));
        properties.add("count", BodyToolSchemas.integer("How many items to move, 1..64"));
        JsonObject direction = new JsonObject();
        direction.addProperty("type", "boolean");
        direction.addProperty("description", "true = container to body, false = body to container");
        properties.add("toPlayer", direction);
        return BodyToolSchemas.object(properties, "x", "y", "z", "containerSlot", "playerSlot", "count", "toPlayer");
    }

    @Override
    public CompletableFuture<ContainerTransferResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return TransferOps.transfer(scope.actor(), args.x(), args.y(), args.z(), args.containerSlot(),
                args.playerSlot(), args.count(), args.toPlayer());
    }
}