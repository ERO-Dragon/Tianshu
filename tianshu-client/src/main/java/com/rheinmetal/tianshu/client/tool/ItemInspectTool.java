package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.ItemInspectPort;
import com.rheinmetal.tianshu.api.game.ItemInspectRequest;
import com.rheinmetal.tianshu.api.game.ItemInspectResult;
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
 * Reads one stack in your inventory: durability, components, custom name.
 *
 * <p>Player side of the split pair; {@link BodyItemInspectTool} is the body side and owns the shared call in
 * {@link #inspect(ActorRef, Args)}.</p>
 *
 * <p>{@code body.inventory} says "slot 0 holds minecraft:iron_pickaxe, 1"; this says whether that pickaxe is about to
 * break and what is attached to it. A caller choosing what to equip needs the second half, and without it the first
 * broken tool is the notification.</p>
 */
public final class ItemInspectTool implements TianshuTool<ItemInspectTool.Args, ItemInspectResult> {

    /** {@code slot} is a main-inventory index, the one {@code body.inventory} reports. */
    public record Args(int slot) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "item.inspect");

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
        return "Reads one inventory slot of your inventory: item id, count, durability left, enchantments, custom name "
                + "and the data components on the stack.";
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
        properties.add("slot", BodyToolSchemas.integer("Main-inventory slot index, as body.inventory reports it"));
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<ItemInspectResult> invoke(ToolContext context, Args args) {
        if (args == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "item.inspect needs a slot"));
        }
        ActorScope scope = ActorScope.player(context.caller());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return inspect(scope.actor(), args);
    }

    /** Shared by both sides of the pair: the actor decides whose inventory slot is read. */
    static CompletableFuture<ItemInspectResult> inspect(ActorRef actor, Args args) {
        ItemInspectRequest request = new ItemInspectRequest(args.slot());
        return TianshuRuntimePorts.find(ItemInspectPort.class)
                .map(port -> port.inspect(actor, request)
                        .thenCompose(result -> result.read()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                                        result.message().isBlank() ? "the slot could not be read" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "item inspection is only available on the server")));
    }
}