package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.BlockFace;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EntityItemUsePort;
import com.rheinmetal.tianshu.api.entity.ItemUseTarget;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Uses the item in an actor's active slot: a bucket, flint and steel, bone meal, a spawn egg, a projectile.
 *
 * <p>The world operation itself. Neither the player-side nor the body-side tool owns it — both only adapt arguments and
 * call in. There is no player-side tool today, so this is the body side's whole implementation.</p>
 *
 * <p>Instant uses only. Food, a bow or a shield need a held use and are refused until a use session exists.</p>
 */
final class ItemUseOps {

    /** Upper bound for a held use: five seconds of ticks. Anything longer is a use session, which does not exist yet. */
    static final int MAX_HOLD_TICKS = 100;

    private ItemUseOps() {
    }

    static CompletableFuture<ActionResult> use(ActorRef actor, ToolContext context, Integer x, Integer y, Integer z,
                                              String face, Integer holdTicks) {
        // The three coordinates name one target: half of them used to become "use the item on the air", which is a
        // different action from the one the caller described.
        int given = (x == null ? 0 : 1) + (y == null ? 0 : 1) + (z == null ? 0 : 1);
        if (given != 0 && given != 3) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "entity.item.use needs all of x, y and z, or none of them to use the item on the air"));
        }
        if (holdTicks != null && holdTicks > MAX_HOLD_TICKS) {
            // The description promises that long-held uses are refused; an unbounded value was forwarded and the
            // server-side session stayed open long after the tool had timed out.
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "holdTicks is at most " + MAX_HOLD_TICKS));
        }
        for (EntityCapability capability : List.of(EntityCapability.USE, EntityCapability.INVENTORY)) {
            Optional<ToolException> refusal = EntityActors.checkCapability(actor, capability);
            if (refusal.isPresent()) {
                return CompletableFuture.failedFuture(refusal.get());
            }
        }
        ItemUseTarget target;
        if (given == 0) {
            target = ItemUseTarget.Air.INSTANCE;
        } else {
            BlockFace blockFace = null;
            if (face != null && !face.isBlank()) {
                try {
                    blockFace = BlockFace.valueOf(face.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknownFace) {
                    return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                            "unknown face: " + face));
                }
            }
            String dimension = EntityActors.resolve(actor.id())
                    .map(body -> body.position().dimension())
                    .orElse("");
            target = new ItemUseTarget.Block(BodyTargets.block(dimension, x, y, z), blockFace);
        }
        ItemUseTarget use = target;
        int ticks = holdTicks == null ? 0 : Math.max(0, holdTicks);
        return ActorScope.guarded(actor, context,
                () -> TianshuRuntimePorts.find(EntityItemUsePort.class)
                        .map(port -> port.use(actor, use, ticks))
                        .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                                "item use is only available on the server"))));
    }
}