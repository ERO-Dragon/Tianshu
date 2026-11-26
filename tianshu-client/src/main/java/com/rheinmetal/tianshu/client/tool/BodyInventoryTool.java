package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityQueryPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/** Reads the actor's own inventory; for a body this is its carried slots, not the local player's. */
public final class BodyInventoryTool implements TianshuTool<BodyInventoryTool.Args, InventorySnapshot> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.inventory");

    public record Args(String body) {
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
        return "Lists the actor's carried items: slot, item id and count.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return InventorySnapshot.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<InventorySnapshot> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.body(caller, args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        ActorRef actor = scope.actor();
        return TianshuRuntimePorts.find(EntityQueryPort.class)
                .map(port -> port.inventory(actor))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "inventory reading is only available on the server")));
    }
}