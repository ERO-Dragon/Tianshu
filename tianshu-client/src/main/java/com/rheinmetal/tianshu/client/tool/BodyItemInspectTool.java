package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.ItemInspectResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Reads one stack in a body's inventory: durability, components, custom name.
 *
 * <p>Body side of the split pair; {@link ItemInspectTool} is the player side and owns the shared call in
 * {@link ItemInspectTool#inspect}. The slot index means the body's own inventory, so the actor is not optional
 * here.</p>
 */
public final class BodyItemInspectTool implements TianshuTool<BodyItemInspectTool.Args, ItemInspectResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.item.inspect");

    /** {@code slot} is a main-inventory index, the one {@code body.inventory} reports. */
    public record Args(String body, int slot) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_READ;
    }

    @Override
    public String description() {
        return "Makes an agent body read one of its inventory slots: item id, count, durability left, enchantments, "
                + "custom name and the data components on the stack.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return ItemInspectResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("slot", BodyToolSchemas.integer("Main-inventory slot index, as body.inventory reports it"));
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<ItemInspectResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        ItemInspectTool.Args inspect = args == null ? null : new ItemInspectTool.Args(args.slot());
        return ItemInspectTool.inspect(scope.actor(), inspect);
    }
}