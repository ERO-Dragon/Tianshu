package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.LootQuery;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.game.LootQueryResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * What a block or entity can drop.
 *
 * <p>The declaration lives on the client so the agent knows the shape; the lookup only ever runs on the server
 * (loot tables are server state), either through the registered {@link LootQueryPort} or, for a client caller, over
 * the tool channel.</p>
 */
public final class LootQueryTool implements TianshuTool<LootQuery, LootQueryResult> {

    public static final ToolId ID = ToolId.of("tianshu", "loot.query");

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
        return "Lists the items a block or entity can drop; give a block id or an entity id, not both.";
    }

    @Override
    public Type argsType() {
        return LootQuery.class;
    }

    @Override
    public Type resultType() {
        return LootQueryResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();

        JsonObject block = new JsonObject();
        block.addProperty("type", "string");
        block.addProperty("description", "A block id, for example minecraft:stone");
        properties.add("blockId", block);

        JsonObject entity = new JsonObject();
        entity.addProperty("type", "string");
        entity.addProperty("description", "An entity id, for example minecraft:zombie");
        properties.add("entityId", entity);

        JsonArray oneOf = new JsonArray();
        JsonObject blockOnly = new JsonObject();
        blockOnly.add("required", required("blockId"));
        JsonObject entityOnly = new JsonObject();
        entityOnly.add("required", required("entityId"));
        oneOf.add(blockOnly);
        oneOf.add(entityOnly);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("oneOf", oneOf);
        return schema;
    }

    @Override
    public CompletableFuture<LootQueryResult> invoke(ToolContext context, LootQuery args) {
        boolean byBlock = args != null && notBlank(args.blockId());
        boolean byEntity = args != null && notBlank(args.entityId());
        if (byBlock == byEntity) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "loot.query needs exactly one of blockId and entityId"));
        }
        LootQuery normalised = byBlock
                ? new LootQuery(args.blockId().trim(), null)
                : new LootQuery(null, args.entityId().trim());
        return TianshuRuntimePorts.find(LootQueryPort.class)
                .map(port -> port.query(normalised))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "loot tables are only available on the server")));
    }

    private static JsonObject required(String name) {
        JsonArray names = new JsonArray();
        names.add(name);
        JsonObject required = new JsonObject();
        required.add("required", names);
        return required;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}