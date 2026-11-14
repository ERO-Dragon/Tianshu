package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.InventoryQueryPort;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * What the local player is carrying, as a list of occupied slots.
 *
 * <p>This is the client's own inventory, which the game already keeps in memory; other players' inventories are
 * server state and deliberately out of scope here.</p>
 */
public final class InventoryInspectTool implements TianshuTool<EmptyArgs, InventorySnapshot> {

    public static final ToolId ID = ToolId.of("tianshu", "inventory.inspect");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.LOCAL;
    }

    @Override
    public String description() {
        return "Lists the local player's occupied inventory slots with item ids and stack sizes.";
    }

    @Override
    public Type argsType() {
        return EmptyArgs.class;
    }

    @Override
    public Type resultType() {
        return InventorySnapshot.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public CompletableFuture<InventorySnapshot> invoke(ToolContext context, EmptyArgs args) {
        return TianshuRuntimePorts.find(InventoryQueryPort.class)
                .map(InventoryQueryPort::read)
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "this platform does not provide an inventory source")));
    }
}