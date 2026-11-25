package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * Reads one entity: what it is, what it is doing, and whether it is already someone's.
 *
 * <p>Atomic: one entity, its own state. It is the counterpart of {@code block.inspect} for the other half of the world —
 * without it, every action on an entity ({@code entity.use}, {@code body.attack}) is taken without knowing whether the
 * target is tame, a baby, or already dead.</p>
 *
 * <p>Reach-bounded like the block reads: an actor that could not touch the entity does not get to look inside it either,
 * so a body cannot use this to survey the other side of the world.</p>
 */
public interface EntityInspectPort {

    CompletableFuture<EntityInspectResult> inspect(ActorRef actor, EntityInspectRequest request);
}