package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Uses the item in a body's active slot: a bucket, flint and steel, bone meal, a spawn egg, a projectile.
 *
 * <p>There is no player-side twin on purpose: what a player does with the item in their own hand is theirs to decide,
 * so the platform does not offer it as a tool. Only a body uses items through the tool surface.</p>
 *
 * <p>The world operation itself lives in {@link ItemUseOps}: this tool only adapts its arguments and calls in. Instant
 * uses only. Food, a bow or a shield need a held use and are refused until a use session exists.</p>
 */
public final class BodyItemUseTool implements TianshuTool<BodyItemUseTool.Args, ActionResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.item.use");

    /** {@code x/y/z} absent means "use it on the air"; a face is optional and derived when omitted. */
    public record Args(String body, Integer x, Integer y, Integer z, String face, Integer holdTicks) {
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
    public com.rheinmetal.tianshu.api.tool.ToolPolicy policy() {
        // A held use can run up to ItemUseOps.MAX_HOLD_TICKS (5s), so the 2s default would time out first.
        return com.rheinmetal.tianshu.api.tool.ToolPolicy.forCategory(category()).withTimeout(25_000L);
    }

    @Override
    public String description() {
        return "Makes an agent body use the item in its active slot on the air or on a block face (instant uses only).";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return ActionResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("x",
                BodyToolSchemas.coordinate("Target block x; omit x, y and z together to use on the air"));
        properties.add("y", BodyToolSchemas.coordinate("Target block y; required when x is given"));
        properties.add("z", BodyToolSchemas.coordinate("Target block z; required when x is given"));
        properties.add("face", BodyToolSchemas.text("Block face: up, down, north, south, east, west"));
        JsonObject holdTicks = BodyToolSchemas.integer(
                "Held-use ticks; 0 means the item's own duration, at most " + ItemUseOps.MAX_HOLD_TICKS);
        holdTicks.addProperty("minimum", 0);
        holdTicks.addProperty("maximum", ItemUseOps.MAX_HOLD_TICKS);
        properties.add("holdTicks", holdTicks);
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<ActionResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return ItemUseOps.use(scope.actor(), context, args.x(), args.y(), args.z(), args.face(), args.holdTicks());
    }
}