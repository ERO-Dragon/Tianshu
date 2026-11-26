package com.rheinmetal.tianshu.api.entity;

import com.rheinmetal.tianshu.api.tool.ServerBodyAutonomyCeiling;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** The platform-facing registry of agent-body providers. */
public final class EntityActors {

    private static final List<EntityProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private EntityActors() {
    }

    public static Runnable register(EntityProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        PROVIDERS.add(provider);
        return () -> PROVIDERS.remove(provider);
    }

    public static Optional<EntityActor> resolve(String bodyId) {
        if (bodyId == null || bodyId.isBlank()) {
            return Optional.empty();
        }
        for (EntityProvider provider : PROVIDERS) {
            Optional<EntityActor> found = provider.resolve(bodyId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Every live body every provider knows about; empty when no adapter has installed a body registry. */
    public static List<EntityActor> list() {
        List<EntityActor> bodies = new java.util.ArrayList<>();
        for (EntityProvider provider : PROVIDERS) {
            bodies.addAll(provider.all());
        }
        return List.copyOf(bodies);
    }

    /** The provider that owns {@code bodyId}, if any; ownership decides which handler set acts for it. */
    public static Optional<EntityProvider> owner(String bodyId) {
        if (bodyId == null || bodyId.isBlank()) {
            return Optional.empty();
        }
        for (EntityProvider provider : PROVIDERS) {
            if (provider.resolve(bodyId).isPresent()) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /** The handlers of the provider that owns {@code bodyId}; empty when nobody owns it or it offers none. */
    public static Optional<EntityActions> actions(String bodyId) {
        return owner(bodyId).flatMap(provider -> provider.actions(bodyId));
    }

    /**
     * Empty when {@code caller} may command {@code bodyId}; a refusal otherwise.
     *
     * Module ownership and player access are distinct. Player permissions cannot authorize a module caller.
     */
    public static Optional<ToolException> checkAccess(ToolCaller caller, String bodyId) {
        if (caller == null || bodyId == null || bodyId.isBlank()) {
            return Optional.of(BodyFailure.CONTROLLER_REQUIRED.exception());
        }
        if (caller.kind() == ToolCaller.Kind.AGENT || caller.kind() == ToolCaller.Kind.MOD) {
            if (!caller.id().isBlank() && resolve(bodyId).map(EntityActor::ownerModule).filter(caller.id()::equals).isPresent()) {
                return Optional.empty();
            }
            return Optional.of(BodyFailure.CONTROLLER_MISMATCH.exception());
        }
        if (caller.kind() == ToolCaller.Kind.BODY) {
            return bodyId.equals(caller.id()) ? Optional.empty()
                    : Optional.of(BodyFailure.SELF_ONLY.exception());
        }
        return owner(bodyId).flatMap(provider -> provider.checkAccess(caller, bodyId));
    }

    /**
     * Empty when the actor may perform {@code capability}; otherwise the error a caller should surface.
     *
     * <p>A player is never checked here — game-side permission belongs to the player path. A body must still be
     * present and must declare the capability: the platform refuses instead of trying an action the body cannot
     * perform.</p>
     */
    public static Optional<ToolException> checkCapability(ActorRef actor, EntityCapability capability) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(capability, "capability");
        if (actor.kind() != ActorRef.Kind.BODY) {
            return Optional.empty();
        }
        EntityActor body = resolve(actor.id()).orElse(null);
        if (body == null) {
            return Optional.of(new ToolException(ToolError.UNAVAILABLE,
                    "agent body " + actor.id() + " is not present"));
        }
        if (!body.capabilities().contains(capability)) {
            return Optional.of(new ToolException(ToolError.UNSUPPORTED_ACTOR,
                    "agent body " + actor.id() + " cannot " + capability.name().toLowerCase(Locale.ROOT)));
        }
        if (!ServerBodyAutonomyCeiling.current().permits(body.requiredAutonomy(capability))) {
            return Optional.of(BodyFailure.AUTONOMY_DENIED.exception());
        }
        return Optional.empty();
    }

    public static void clearForTests() {
        PROVIDERS.clear();
    }
}