package com.rheinmetal.tianshu.api.agent;

import java.util.List;
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

    public static void clearForTests() {
        PROVIDERS.clear();
    }
}
