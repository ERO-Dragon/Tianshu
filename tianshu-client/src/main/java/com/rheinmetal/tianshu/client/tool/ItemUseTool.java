package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityItemUsePort;
import com.rheinmetal.tianshu.api.entity.BlockFace;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.ItemUseTarget;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Uses the item in the body's active slot: a bucket, flint and steel, bone meal, a spawn egg, a projectile.
 *
 * <p>Instant uses only. Food, a bow or a shield need a held use and are refused until a use session exists.</p>
 */
public final class ItemUseTool implements TianshuTool<ItemUseTool.Args, ActionResult> {

    public static final ToolId ID = ToolId.of("tianshu", "item.use");
    /** Upper bound for a held use: five seconds of ticks. Anything longer is a use session, which does not exist yet. */
    private static final int MAX_HOLD_TICKS = 100;

    /** {@code x/y/z} absent means "use it on the air"; a face is optional and derived when omitted. */
    public record Args(String body, Integer x, Integer y, Integer z, String face, Integer holdTicks) {
    }

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
        return "Uses the item in the body's active slot on the air or on a block face (instant uses only).";
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
        properties.add("x", BodyToolSchemas.coordinate("Target block x; omit x, y and z together to use on the air"));
        properties.add("y", BodyToolSchemas.coordinate("Target block y; required when x is given"));
        properties.add("z", BodyToolSchemas.coordinate("Target block z; required when x is given"));
        properties.add("face", BodyToolSchemas.text("Block face: up, down, north, south, east, west"));
        JsonObject holdTicks = BodyToolSchemas.integer(
                "Held-use ticks; 0 means the item's own duration, at most " + MAX_HOLD_TICKS);
        holdTicks.addProperty("minimum", 0);
        holdTicks.addProperty("maximum", MAX_HOLD_TICKS);
        properties.add("holdTicks", holdTicks);
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<ActionResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (scope.actor().kind() != ActorRef.Kind.BODY) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "item.use needs an agent body"));
        }
        // The three coordinates name one target: half of them used to become "use the item on the air", which is a
        // different action from the one the caller described.
        int given = (args == null ? 0 : (args.x() == null ? 0 : 1) + (args.y() == null ? 0 : 1)
                + (args.z() == null ? 0 : 1));
        if (given != 0 && given != 3) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "item.use needs all of x, y and z, or none of them to use the item on the air"));
        }
        if (args != null && args.holdTicks() != null && args.holdTicks() > MAX_HOLD_TICKS) {
            // The description promises that long-held uses are refused; an unbounded value was forwarded and the
            // server-side session stayed open long after the tool had timed out.
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "holdTicks is at most " + MAX_HOLD_TICKS));
        }
        for (EntityCapability capability : java.util.List.of(EntityCapability.USE, EntityCapability.INVENTORY)) {
            Optional<ToolException> refusal = scope.capabilityRefusal(capability);
            if (refusal.isPresent()) {
                return CompletableFuture.failedFuture(refusal.get());
            }
        }
        ItemUseTarget target;
        if (given == 0) {
            target = ItemUseTarget.Air.INSTANCE;
        } else {
            BlockFace face = null;
            if (args.face() != null && !args.face().isBlank()) {
                try {
                    face = BlockFace.valueOf(args.face().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknownFace) {
                    return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                            "unknown face: " + args.face()));
                }
            }
            String dimension = EntityActors.resolve(scope.actor().id())
                    .map(body -> body.position().dimension())
                    .orElse("");
            target = new ItemUseTarget.Block(BodyTargets.block(dimension, args.x(), args.y(), args.z()), face);
        }
        ItemUseTarget use = target;
        ActorRef actor = scope.actor();
        return ActorScope.guarded(actor, context,
                () -> TianshuRuntimePorts.find(EntityItemUsePort.class)
                        .map(port -> port.use(actor, use, args == null || args.holdTicks() == null
                                ? 0 : Math.max(0, args.holdTicks())))
                        .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                                "item use is only available on the server"))));
    }

    @Override
    public com.rheinmetal.tianshu.api.tool.ToolPolicy policy() {
        return com.rheinmetal.tianshu.api.tool.ToolPolicy.forCategory(category()).withTimeout(25_000L);
    }
}