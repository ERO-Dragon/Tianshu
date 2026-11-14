package com.rheinmetal.tianshu.api.agent;

import java.util.Optional;

/** Resolves an entity id to an agent body. Implemented by the mod that owns the entity type. */
@FunctionalInterface
public interface AgentBodyProvider {

    Optional<AgentBody> resolve(String bodyId);
}
