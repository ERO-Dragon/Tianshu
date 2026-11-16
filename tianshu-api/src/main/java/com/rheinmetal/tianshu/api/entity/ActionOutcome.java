package com.rheinmetal.tianshu.api.entity;

/** How an action ended. {@link #NO_EFFECT} is a legitimate result, not a failure. */
public enum ActionOutcome {

    /** The action happened. */
    DONE,

    /** The action was attempted and had no effect in the world. */
    NO_EFFECT,

    /** The preconditions did not hold; nothing was attempted. */
    REJECTED,

    /** The action was attempted and failed. */
    FAILED
}