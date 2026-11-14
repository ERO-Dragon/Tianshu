package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockInteractPort;
import com.rheinmetal.tianshu.api.game.BlockInteractRequest;
import com.rheinmetal.tianshu.api.game.BlockInteractResult;
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
 * Operates a harmless block as the requesting player: buttons, levers, doors, trapdoors and fence gates.
 *
 * <p>This is a write tool, so it is off by default and needs an operator. Breaking, placing and igniting are
 * deliberately not part of it; if they are ever offered, they get their own tool with their own switch.</p>
 */
public final class BlockInteractTool implements TianshuTool<BlockInteractRequest, BlockInteractResult> {

    public static final ToolId ID = ToolId.of("tianshu", "block.interact");

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
        return "Presses a button, toggles a lever, opens a door/trapdoor/fence gate at the given block position.";
    }

    @Override
    public Type argsType() {
        return BlockInteractRequest.class;
    }

    @Override
    public Type resultType() {
        return BlockInteractResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("x", integer("Block x coordinate"));
        properties.add("y", integer("Block y coordinate"));
        properties.add("z", integer("Block z coordinate"));

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    @Override
    public CompletableFuture<BlockInteractResult> invoke(ToolContext context, BlockInteractRequest args) {
        ToolCaller caller = context.caller();
        if (caller.kind() != ToolCaller.Kind.PLAYER) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.NO_PERMISSION,
                    "block.interact runs as a player"));
        }
        return TianshuRuntimePorts.find(BlockInteractPort.class)
                .map(port -> port.interact(caller.id(), args))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "block interaction is only available on the server")));
    }

    private static JsonObject integer(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        return property;
    }
}