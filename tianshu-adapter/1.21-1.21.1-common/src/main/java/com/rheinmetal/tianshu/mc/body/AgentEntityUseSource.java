package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityUsePort;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Entity interaction through the action player: {@code interactOn} with the active item, or an empty hand.
 *
 * <p>Covers feeding, taming, leashing and trading, which are all the same vanilla entry point. Riding is not
 * included: a passenger body needs its own control path, which this port does not model.</p>
 */
public final class AgentEntityUseSource implements EntityUsePort {

    private static final double REACH_SQUARED = 36.0;

    private final MinecraftServer server;
    private final BodyRegistry bodies;
    private final ActionPlayerFactory actionPlayers;

    public AgentEntityUseSource(MinecraftServer server, BodyRegistry bodies, ActionPlayerFactory actionPlayers) {
        this.server = server;
        this.bodies = bodies;
        this.actionPlayers = actionPlayers;
    }

    @Override
    public CompletableFuture<ActionResult> useOn(ActorRef actor, String entityId) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(useNow(actor, entityId));
            } catch (ToolException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private ActionResult useNow(ActorRef actor, String entityId) {
        BodyRegistry.BodyState body = bodies.state(actor.id()).orElse(null);
        if (body == null) {
            return ActionResult.rejected("the agent body is not present");
        }
        UUID id = uuid(entityId);
        if (id == null) {
            throw new ToolException(ToolError.INVALID_ARGS, "that is not an entity id");
        }
        Entity target = find(id);
        if (target == null || target.isRemoved()) {
            return ActionResult.rejected("the target entity is not present");
        }
        if (target.getUUID().equals(body.id())) {
            return ActionResult.rejected("a body cannot use itself");
        }
        if (distanceSquared(body.position(), target.position()) > REACH_SQUARED) {
            return ActionResult.rejected("the target is out of reach");
        }
        int slot = bodies.activeSlot(body.id().toString());
        ActionContext context = ActionContext.hand(server, actor, bodies, slot).orElse(null);
        if (context == null) {
            return ActionResult.rejected("the agent body is not present");
        }
        Player player = context.player();
        InteractionResult outcome;
        try {
            outcome = player.interactOn(target, InteractionHand.MAIN_HAND);
        } finally {
            context.commit();
            context.close();
        }
        String type = target.getType().toString();
        return outcome.consumesAction()
                ? ActionResult.done("used on " + type)
                : new ActionResult(ActionOutcome.NO_EFFECT, "nothing happened on " + type);
    }

    private Entity find(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static double distanceSquared(Vec3 from, Vec3 to) {
        return from.distanceToSqr(to);
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}