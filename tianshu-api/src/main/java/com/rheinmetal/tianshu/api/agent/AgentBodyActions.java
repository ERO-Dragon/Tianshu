package com.rheinmetal.tianshu.api.agent;

import com.rheinmetal.tianshu.api.game.BlockPlacePort;

import java.util.Optional;

/**
 * The action handlers a body provider offers for its carriers.
 *
 * <p>One member per capability port, and each member is optional: a provider returns only the handlers its carrier
 * can actually perform. Declaring a capability without a matching handler is a provider bug, and reaches the caller
 * as {@code UNAVAILABLE} instead of a silent no-op.</p>
 *
 * <p>The interface grows one port at a time, so every addition is a default method and an existing provider keeps
 * compiling.</p>
 */
public interface AgentBodyActions {

    /** Moving and turning, behind {@code body.move} and {@code body.look}. */
    default Optional<AgentMotionPort> motion() {
        return Optional.empty();
    }

    /** Breaking and striking, behind {@code body.mine} and {@code body.attack}. */
    default Optional<AgentActionPort> action() {
        return Optional.empty();
    }

    /** Picking up and equipping, behind {@code body.pickup} and {@code body.equip}. */
    default Optional<AgentItemPort> items() {
        return Optional.empty();
    }

    /** Scanning and reading the carrier's inventory, behind {@code body.scan} and {@code body.inventory}. */
    default Optional<AgentWorldQueryPort> query() {
        return Optional.empty();
    }

    /** Placing a block from the carrier's inventory, behind {@code block.place}. */
    default Optional<BlockPlacePort> placement() {
        return Optional.empty();
    }
}