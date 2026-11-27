package com.rheinmetal.tianshu.api.entity;

import java.util.concurrent.CompletableFuture;

/**
 * Server-side allocation of an NPC at a world position on behalf of a module.
 *
 * <p>Allocation is a pair: a module that brings a body into the world has to be able to take it out again, or the only
 * way to remove one is an operator command and every module-spawned NPC outlives the module that wanted it. Both calls
 * answer to the same owner rule — the module that asked for the body is the only one allowed to act on it.</p>
 */
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

    /**
     * Removes a body this module spawned, and drops its record with it.
     *
     * <p>The carrier is discarded rather than killed: withdrawing one's own NPC is not a player death, so nothing is
     * dropped at its position. Fails with {@code body.controller.mismatch} when the body belongs to another module and
     * with {@code body.target.required} when no live body has that id — a body someone else owns is not the same
     * failure as a body that is not there.</p>
     *
     * @param moduleId the module that spawned it, and the only one allowed to release it
     * @param bodyId   the body to remove
     */
    CompletableFuture<Void> release(String moduleId, String bodyId);

    /**
     * Removes every body this module owns.
     *
     * <p>The platform calls this when the module stops: a body is the module's logic walking around, and logic that is
     * gone should not leave anything behind. Adapters run it on their own thread, so a caller never blocks on the world.
     * Bodies another module owns are left alone — that is the whole point of ownership.</p>
     *
     * @param moduleId the module that is stopping, and the only owner whose bodies are removed
     */
    void withdrawAll(String moduleId);
}