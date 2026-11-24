package com.rheinmetal.tianshu.lab.acceptance;

import com.rheinmetal.tianshu.lab.LabModuleRegistration;
import com.rheinmetal.tianshu.api.entity.ActionOutcome;
import com.rheinmetal.tianshu.api.entity.ActionResult;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityActions;
import com.rheinmetal.tianshu.api.entity.EntityProvider;
import com.rheinmetal.tianshu.api.entity.EntityMotionPort;
import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.MoveOptions;
import com.rheinmetal.tianshu.api.entity.MoveResult;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory agent body a module owns, so the body tools can be exercised without a world.
 *
 * <p>This is the shape the platform is built for: a module registers a provider, the provider owns its bodies, and
 * {@link EntityProvider#checkAccess} decides who may command them. Nothing here impersonates a player and no
 * permission is relaxed — the owner simply is the caller.</p>
 */
final class AcceptanceBodies implements EntityProvider {

    static final String OWNER = "lab";
    static final String BODY_ID = "lab:acceptance-body";

    private final String ownerId;
    private final List<String> looks = new CopyOnWriteArrayList<>();

    /**
     * @param ownerId who may command the body. The platform resolves a client call to the real local player, so the
     *                bench passes that player's id here instead of a made-up name.
     */
    AcceptanceBodies(String ownerId) {
        this.ownerId = ownerId == null || ownerId.isBlank() ? OWNER : ownerId;
    }

    List<String> looks() {
        return List.copyOf(looks);
    }

    @Override
    public Optional<EntityActor> resolve(String bodyId) {
        return BODY_ID.equals(bodyId) ? Optional.of(body()) : Optional.empty();
    }

    @Override
    public Optional<EntityActions> actions(String bodyId) {
        return BODY_ID.equals(bodyId) ? Optional.of(actions()) : Optional.empty();
    }

    /** Only the body itself, or the module that registered it, may command it. */
    @Override
    public Optional<ToolException> checkAccess(ToolCaller caller, String bodyId) {
        if (!BODY_ID.equals(bodyId)) {
            return Optional.empty();
        }
        String actor = ActorRef.of(caller).id();
        if (BODY_ID.equals(actor) || ownerId.equals(actor) || OWNER.equals(actor)) {
            return Optional.empty();
        }
        return Optional.of(new ToolException(ToolError.NO_PERMISSION,
                "body " + bodyId + " belongs to " + OWNER + " (caller=" + actor + ", kind=" + caller.kind() + ")"));
    }

    private EntityActor body() {
        return new EntityActor() {
            @Override
            public String id() {
                return BODY_ID;
            }

            @Override
            public String ownerModule() {
                return LabModuleRegistration.MOD_ID;
            }

            @Override
            public boolean operator() {
                // A body tool that writes the world needs an operator body; the body being an agent's own is what
                // makes that legitimate, and it is what the earlier NO_PERMISSION was about.
                return true;
            }

            @Override
            public ActorPosition position() {
                return new ActorPosition("minecraft:overworld", 0.5, 64.0, 0.5);
            }

            @Override
            public Set<EntityCapability> capabilities() {
                return EnumSet.of(EntityCapability.LOOK, EntityCapability.MOVE);
            }
        };
    }

    private EntityActions actions() {
        return new EntityActions() {
            @Override
            public Optional<EntityMotionPort> motion() {
                return Optional.of(new EntityMotionPort() {
                    @Override
                    public CompletableFuture<MoveResult> move(ActorRef actor, ActorPosition target, MoveOptions options) {
                        return CompletableFuture.completedFuture(
                                new MoveResult(ActionOutcome.DONE, target, "moved to " + target));
                    }

                    @Override
                    public CompletableFuture<ActionResult> look(ActorRef actor, ActorPosition target) {
                        looks.add(target.toString());
                        return CompletableFuture.completedFuture(ActionResult.done("looked at " + target));
                    }
                });
            }
        };
    }
}