package com.rheinmetal.tianshu.api.entity;

import java.util.concurrent.CompletableFuture;

/** Server-side allocation of an NPC at a world position on behalf of a module. */
@FunctionalInterface
public interface EntitySpawnPort {
    /**
     * Asks the server to bring one body into the world.
     *
     * <p>{@code kind} is either a built-in carrier name ({@code marker}, {@code stand}, {@code walker}) — the
     * adapter's own shapes, available to every module — or {@code <module>:<name>} for a type that module declared.
     * The server's registry decides, not the caller: a name nothing there can produce fails with
     * {@code body.kind.invalid}, and a type declared by another module fails with {@code body.kind.foreign}. A type
     * that exists only on the client has no entry on the server, so it cannot be spawned at all.</p>
     *
     * @param moduleId   the requesting module, and the namespace that owns any declared type it names
     * @param position   where to put the body, in the dimension the position names
     * @param playerLike whether the body takes part in player rules and player permissions
     */
    CompletableFuture<EntityActor> spawn(String moduleId, String kind, ActorPosition position, boolean playerLike);
}