package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.ActionResult;
import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentItemUsePort;
import com.rheinmetal.tianshu.api.agent.BlockFace;
import com.rheinmetal.tianshu.api.agent.BlockTarget;
import com.rheinmetal.tianshu.api.agent.BodyCapability;
import com.rheinmetal.tianshu.api.agent.ItemUseTarget;
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
        properties.add("x", BodyToolSchemas.integer("Target block x; omit to use the item on the air"));
        properties.add("y", BodyToolSchemas.integer("Target block y"));
        properties.add("z", BodyToolSchemas.integer("Target block z"));
        properties.add("face", BodyToolSchemas.text("Block face: up, down, north, south, east, west"));
        properties.add("holdTicks", BodyToolSchemas.integer("Held-use ticks; 0 means the item's own duration"));
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<ActionResult> invoke(ToolContext context, Args args) {
        ToolCaller caller = context.caller();
        ActorScope scope = ActorScope.of(caller, args == null ? null : args.body(), ID);
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        if (scope.actor().kind() != ActorRef.Kind.BODY) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "item.use needs an agent body"));
        }
        for (BodyCapability capability : java.util.List.of(BodyCapability.USE, BodyCapability.INVENTORY)) {
            Optional<ToolException> refusal = scope.capabilityRefusal(capability);
            if (refusal.isPresent()) {
                return CompletableFuture.failedFuture(refusal.get());
            }
        }
        ItemUseTarget target;
        if (args == null || args.x() == null || args.y() == null || args.z() == null) {
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
            String dimension = AgentBodies.resolve(scope.actor().id())
                    .map(body -> body.position().dimension())
                    .orElse("");
            target = new ItemUseTarget.Block(new BlockTarget(dimension, args.x(), args.y(), args.z()), face);
        }
        ItemUseTarget use = target;
        ActorRef actor = scope.actor();
        return TianshuRuntimePorts.find(AgentItemUsePort.class)
                .map(port -> port.use(actor, use, args == null || args.holdTicks() == null
                        ? 0 : Math.max(0, args.holdTicks())))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "item use is only available on the server")));
    }
}