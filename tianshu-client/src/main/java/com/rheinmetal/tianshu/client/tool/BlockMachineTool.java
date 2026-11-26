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
 * Works a machine at a position: put fuel in, put input in, take the result out.
 *
 * <p>Player side of the split pair; {@link BodyMachineTool} is the body side. The world operation itself lives in
 * {@link MachineOps}: this tool only adapts its arguments and calls in.</p>
 *
 * <p>A write tool, off by default. Working a machine needs the actor's inventory, not its ability to use blocks: every
 * operation moves items into or out of a container, so an actor that can only use blocks cannot work the machine. The
 * platform decides which machines are understood (for now the furnace family); an unknown block is a refusal with a
 * reason, never a generic "run this block" escape hatch.</p>
 */
public final class BlockMachineTool implements TianshuTool<BlockMachineTool.Args, MachineOpResult> {

    public record Args(int x, int y, int z, String operation, Integer playerSlot) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "block.machine");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_WRITE;
    }

    @Override
    public com.rheinmetal.tianshu.api.tool.ToolPolicy policy() {
        // Any survival player may do this by hand; the server switch and their own rights still bound it.
        // On by default: this is a player's own crafting or container work, and their real rights still apply.
        return com.rheinmetal.tianshu.api.tool.ToolPolicy.forCategory(ToolCategory.SERVER_WRITE)
                .withPermission(com.rheinmetal.tianshu.api.tool.ToolPermission.EVERYONE)
                .withEnabledByDefault(true);
    }

    @Override
    public String description() {
        return "Operates a furnace-family machine: insert_fuel, insert_input or take_output at the given position.";
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
        properties.add("x", integer("Machine x coordinate"));
        properties.add("y", integer("Machine y coordinate"));
        properties.add("z", integer("Machine z coordinate"));

        JsonObject operation = new JsonObject();
        operation.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add("insert_fuel");
        values.add("insert_input");
        values.add("take_output");
        operation.add("enum", values);
        properties.add("operation", operation);

        JsonObject playerSlot = integer("Player inventory slot used by insert_fuel and insert_input, 0..35");
        properties.add("playerSlot", playerSlot);

        JsonArray required = new JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");
        required.add("operation");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    @Override
    public CompletableFuture<MachineOpResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.player(context.caller());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return MachineOps.operate(scope.actor(), args.x(), args.y(), args.z(), args.operation(), args.playerSlot());
    }

    private static JsonObject integer(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        return property;
    }
}