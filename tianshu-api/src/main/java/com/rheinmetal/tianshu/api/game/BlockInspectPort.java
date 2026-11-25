package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * Reads one block: what it is, and what it holds.
 *
 * <p>Read-only, and deliberately not tied to a GUI: the caller never has to open a menu, which is what makes it usable
 * for a body that has no client, and for a machine whose own screen the platform cannot render. It is the perception
 * side of the same reach rule the write tools use — the actor has to be close enough to see the block.</p>
 *
 * <p>Implementations answer for any block, including modded ones: what can be read is decided by what the block
 * exposes (item handler, fluid handler, energy storage), not by a registry of known block types. A block that exposes
 * nothing still answers with its id and state.</p>
 */
public interface BlockInspectPort {

    CompletableFuture<BlockInspectResult> inspect(ActorRef actor, BlockInspectRequest request);
}