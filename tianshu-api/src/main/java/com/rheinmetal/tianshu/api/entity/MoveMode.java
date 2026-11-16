package com.rheinmetal.tianshu.api.entity;

/**
 * How a body is asked to reach a target; a carrier that cannot move that way refuses instead of improvising.
 *
 * <p>The modes describe requests, not a body shape. A carrier does not have to be humanoid or grounded: a floating
 * body can answer {@link #TELEPORT} or implement {@link #FLY}, and the built-in carriers refusing {@code FLY} is
 * their own limitation, not a platform rule.</p>
 */
public enum MoveMode {

    /** Along the ground. */
    WALK,

    /** Through the air under its own control; a carrier that can hover implements this. */
    FLY,

    /** Repositioned directly, without travelling the distance. */
    TELEPORT,

    /** Whatever the carrier can do; the adapter picks. */
    ANY
}