package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
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
 * Operates a harmless block as the requesting actor: a player, or an agent body that declares {@code USE}.
 *
 * <p>This is a write tool, so it is off by default and needs an operator. Breaking, placing and igniting are
 * deliberately not part of it; if they are ever offered, they get their own tool with their own switch.</p>
 */
public final class BlockInteractTool implements TianshuTool<BlockInteractTool.Args, BlockInteractResult> {

    /** {@code body} is optional: when present the interaction runs as that body. */
    public record Args(int x, int y, int z, String body) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "block.interact");

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
        return "Presses a button, toggles a lever, opens a door/trapdoor/fence gate at the given block position.";
    }

    @Override
    public Type argsType() {
        return Args.class;
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
        properties.add("body", BodyToolSchemas.body());

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
    public CompletableFuture<BlockInteractResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        java.util.Optional<ToolException> refusal = scope.capabilityRefusal(EntityCapability.USE);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        if (args == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "block.interact needs x, y and z"));
        }
        BlockInteractRequest request = new BlockInteractRequest(args.x(), args.y(), args.z());
        return TianshuRuntimePorts.find(BlockInteractPort.class)
                .map(port -> port.interact(scope.actor(), request))
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
