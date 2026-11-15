package com.rheinmetal.tianshu.api.agent;

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
}