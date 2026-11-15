package com.rheinmetal.tianshu.api.agent;

import java.util.List;
import java.util.Optional;

/** Resolves an entity id to an agent body. Implemented by the mod that owns the entity type. */
public interface AgentBodyProvider {

    Optional<AgentBody> resolve(String bodyId);

    /** Every live body this provider holds; a provider that cannot enumerate returns none. */
    default List<AgentBody> all() {
        return List.of();
    }
}