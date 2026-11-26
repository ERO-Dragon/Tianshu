package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.MoveMode;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Lists the agent bodies the server currently holds, so a caller can name one it did not spawn itself. */
public final class BodyListTool implements TianshuTool<JsonObject, BodyListTool.Result> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.list");

    /** One row per body; the capability names are the platform's, not the carrier's. */
    public record Row(String id, boolean operator, String dimension, double x, double y, double z,
                      List<String> capabilities, List<String> nativeMovementModes) {
    }

    public record Result(List<Row> bodies) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BODY_READ;
    }

    @Override
    public String description() {
        return "Lists the agent bodies on the server: id, position and what each can do.";
    }

    @Override
    public Type argsType() {
        return JsonObject.class;
    }

    @Override
    public Type resultType() {
        return Result.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public CompletableFuture<Result> invoke(ToolContext context, JsonObject args) {
        ToolCaller caller = context.caller();
        if (caller.kind() != ToolCaller.Kind.PLAYER && caller.kind() != ToolCaller.Kind.AGENT
                && caller.kind() != ToolCaller.Kind.BODY) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.NO_PERMISSION,
                    "body.list runs for a player, an agent or a body"));
        }
        List<Row> rows = new ArrayList<>();
        for (EntityActor body : EntityActors.list()) {
            rows.add(new Row(body.id(), body.operator(), body.position().dimension(),
                    body.position().x(), body.position().y(), body.position().z(),
                    body.capabilities().stream().map(EntityCapability::name).sorted().toList(),
                    body.nativeMovementModes().stream().filter(mode -> mode != MoveMode.ANY)
                            .map(MoveMode::name).sorted().toList()));
        }
        return CompletableFuture.completedFuture(new Result(List.copyOf(rows)));
    }
}