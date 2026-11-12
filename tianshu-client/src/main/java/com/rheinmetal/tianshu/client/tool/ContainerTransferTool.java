package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.ContainerTransferPort;
import com.rheinmetal.tianshu.api.game.ContainerTransferRequest;
import com.rheinmetal.tianshu.api.game.ContainerTransferResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Moves items between a container and the player's inventory, as the requesting player.
 *
 * <p>A write tool: off by default, operator-only, one slot pair per call. It refuses negative slots and counts
 * outside 1..64 before anything reaches the game, so a malformed call never becomes a partial move.</p>
 */
public final class ContainerTransferTool implements TianshuTool<ContainerTransferRequest, ContainerTransferResult> {

    public static final ToolId ID = ToolId.of("tianshu", "container.transfer");

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
        return "Moves items between one slot of a container at the given position and one slot of the player inventory.";
    }

    @Override
    public Type argsType() {
        return ContainerTransferRequest.class;
    }

    @Override
    public Type resultType() {
        return ContainerTransferResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("x", integer("Container x coordinate"));
        properties.add("y", integer("Container y coordinate"));
        properties.add("z", integer("Container z coordinate"));
        properties.add("containerSlot", integer("Slot inside the container"));
        properties.add("playerSlot", integer("Slot inside the player inventory, 0..35"));
        JsonObject count = integer("How many items to move, 1..64");
        properties.add("count", count);
        JsonObject direction = new JsonObject();
        direction.addProperty("type", "boolean");
        direction.addProperty("description", "true = container to player, false = player to container");
        properties.add("toPlayer", direction);

        JsonArray required = new JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");
        required.add("containerSlot");
        required.add("playerSlot");
        required.add("count");
        required.add("toPlayer");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    @Override
    public CompletableFuture<ContainerTransferResult> invoke(ToolContext context, ContainerTransferRequest args) {
        ToolCaller caller = context.caller();
        if (caller.kind() != ToolCaller.Kind.PLAYER) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.NO_PERMISSION,
                    "container.transfer runs as a player"));
        }
        if (args == null || args.containerSlot() < 0 || args.playerSlot() < 0
                || args.playerSlot() >= 36 || args.count() < 1 || args.count() > 64) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "container.transfer needs non-negative slots and a count between 1 and 64"));
        }
        return TianshuRuntimePorts.find(ContainerTransferPort.class)
                .map(port -> port.transfer(caller.id(), args))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "container access is only available on the server")));
    }

    private static JsonObject integer(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        return property;
    }
}