package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.MachineOpResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Works a machine at a position as an agent body: put fuel in, put input in, take the result out.
 *
 * <p>Body side of the split pair; {@link BlockMachineTool} is the player side and owns the shared call in
 * {@link BlockMachineTool#operate}. Every operation moves items into or out of a container, so the body must declare
 * {@code INVENTORY}, not only {@code USE}.</p>
 */
public final class BodyMachineTool implements TianshuTool<BodyMachineTool.Args, MachineOpResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.machine");

    public record Args(String body, int x, int y, int z, String operation, Integer playerSlot) {
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
        return "Makes an agent body operate a furnace-family machine: insert_fuel, insert_input or take_output at the "
                + "given position, using the body's own inventory.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return MachineOpResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("x", BodyToolSchemas.integer("Machine x coordinate"));
        properties.add("y", BodyToolSchemas.integer("Machine y coordinate"));
        properties.add("z", BodyToolSchemas.integer("Machine z coordinate"));

        JsonObject operation = new JsonObject();
        operation.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add("insert_fuel");
        values.add("insert_input");
        values.add("take_output");
        operation.add("enum", values);
        properties.add("operation", operation);

        properties.add("playerSlot",
                BodyToolSchemas.integer("Inventory slot used by insert_fuel and insert_input, 0..35"));
        return BodyToolSchemas.object(properties, "x", "y", "z", "operation");
    }

    @Override
    public CompletableFuture<MachineOpResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        BlockMachineTool.Args operate = args == null
                ? null
                : new BlockMachineTool.Args(args.x(), args.y(), args.z(), args.operation(), args.playerSlot());
        return BlockMachineTool.operate(scope.actor(), operate);
    }
}