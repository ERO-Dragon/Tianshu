package com.rheinmetal.tianshu.api.agent;

/** How a body is asked to reach a target; a carrier that cannot move that way refuses instead of improvising. */
public enum MoveMode {

    /** Along the ground. */
    WALK,

    /** Through the air under its own control. */
    FLY,

    /** Repositioned directly, without travelling the distance. */
    TELEPORT,

    /** Whatever the carrier can do; the adapter picks. */
    ANY
}