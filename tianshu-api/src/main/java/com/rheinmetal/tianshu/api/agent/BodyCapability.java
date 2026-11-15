package com.rheinmetal.tianshu.api.agent;

/**
 * What an agent body can actually do. A body is not assumed to be a player: it may float, have no health, no
 * hands and no inventory, so every action names the capability it needs and a body simply does not declare the
 * ones it cannot perform.
 */
public enum BodyCapability {

    /** Changes its own position, by walking, flying or teleporting — the platform does not care which. */
    MOVE,

    /** Turns towards a point in the world. */
    LOOK,

    /** Applies a "use" action to a block or a held item, without implying hands or a tool. */
    USE,

    /** Breaks blocks, with or without a tool. */
    MINE,

    /** Affects entities, with or without dealing damage. */
    ATTACK,

    /** Owns a readable and writable item store; without it crafting and transfers are refused. */
    INVENTORY
}