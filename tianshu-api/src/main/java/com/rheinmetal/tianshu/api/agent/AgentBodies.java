package com.rheinmetal.tianshu.api.agent;

import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** The platform-facing registry of agent-body providers. */
public final class AgentBodies {

    private static final List<AgentBodyProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private AgentBodies() {
    }

    public static Runnable register(AgentBodyProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        PROVIDERS.add(provider);
        return () -> PROVIDERS.remove(provider);
    }

    public static Optional<AgentBody> resolve(String bodyId) {
        if (bodyId == null || bodyId.isBlank()) {
            return Optional.empty();
        }
        for (AgentBodyProvider provider : PROVIDERS) {
            Optional<AgentBody> found = provider.resolve(bodyId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Empty when the actor may perform {@code capability}; otherwise the error a caller should surface.
     *
     * <p>A player is never checked here — game-side permission belongs to the player path. A body must still be
     * present and must declare the capability: the platform refuses instead of trying an action the body cannot
     * perform.</p>
     */
    public static Optional<ToolException> checkCapability(ActorRef actor, BodyCapability capability) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(capability, "capability");
        if (actor.kind() != ActorRef.Kind.BODY) {
            return Optional.empty();
        }
        AgentBody body = resolve(actor.id()).orElse(null);
        if (body == null) {
            return Optional.of(new ToolException(ToolError.UNAVAILABLE,
                    "agent body " + actor.id() + " is not present"));
        }
        if (!body.capabilities().contains(capability)) {
            return Optional.of(new ToolException(ToolError.UNSUPPORTED_ACTOR,
                    "agent body " + actor.id() + " cannot " + capability.name().toLowerCase(Locale.ROOT)));
        }
        return Optional.empty();
    }

    public static void clearForTests() {
        PROVIDERS.clear();
    }
}