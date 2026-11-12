package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.BlockMachinePort;
import com.rheinmetal.tianshu.api.game.MachineOpRequest;
import com.rheinmetal.tianshu.api.game.MachineOpResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Works a machine at a position: put fuel in, put input in, take the result out.
 *
 * <p>A write tool, off by default, player-only. The platform decides which machines are understood (for now the
 * furnace family); an unknown block is a refusal with a reason, never a generic "run this block" escape hatch.</p>
 */
public final class BlockMachineTool implements TianshuTool<MachineOpRequest, MachineOpResult> {

    public static final ToolId ID = ToolId.of("tianshu", "block.machine");
    private static final Set<String> OPERATIONS = Set.of("insert_fuel", "insert_input", "take_output");

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
        return "Operates a furnace-family machine: insert_fuel, insert_input or take_output at the given position.";
    }

    @Override
    public Type argsType() {
        return MachineOpRequest.class;
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
    public CompletableFuture<MachineOpResult> invoke(ToolContext context, MachineOpRequest args) {
        ToolCaller caller = context.caller();
        if (caller.kind() != ToolCaller.Kind.PLAYER) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.NO_PERMISSION,
                    "block.machine runs as a player"));
        }
        String operation = args == null || args.operation() == null
                ? "" : args.operation().trim().toLowerCase(Locale.ROOT);
        if (!OPERATIONS.contains(operation)) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.machine needs one of: insert_fuel, insert_input, take_output"));
        }
        if (!operation.equals("take_output") && (args.playerSlot() < 0 || args.playerSlot() >= 36)) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.machine needs a player slot between 0 and 35 for insert operations"));
        }
        MachineOpRequest normalised = new MachineOpRequest(args.x(), args.y(), args.z(), operation,
                operation.equals("take_output") ? -1 : args.playerSlot());
        return TianshuRuntimePorts.find(BlockMachinePort.class)
                .map(port -> port.operate(caller.id(), normalised))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "machine access is only available on the server")));
    }

    private static JsonObject integer(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        return property;
    }
}