package com.rheinmetal.tianshu.api.entity;

import java.util.concurrent.CompletableFuture;

/** Server-side allocation of an NPC at a world position on behalf of a module. */
@FunctionalInterface
public interface EntitySpawnPort {
    /** Kind is a built-in carrier name or a namespaced type declared by the requesting module. */
    CompletableFuture<EntityActor> spawn(String moduleId, String kind, ActorPosition position, boolean playerLike);
}
