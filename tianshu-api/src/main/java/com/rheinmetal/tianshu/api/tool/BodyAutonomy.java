package com.rheinmetal.tianshu.api.tool;

/**
 * How much an action may do <em>through an agent body</em>.
 *
 * <p>Two levels, and the question is not "is this cheating":</p>
 *
 * <ul>
 *   <li>{@link #SURVIVAL} — what a body can do with its own hands: walk, mine, place, attack, use, pick up, equip.</li>
 *   <li>{@link #PRIVILEGED} — what a body cannot do by itself: fly, teleport, run commands.</li>
 * </ul>
 *
 * <p>A carrier mining is not a cheat, it is the body doing its job, so it is survival work. That is why this scale is
 * separate from {@link ToolAutonomy} instead of reusing it: the player scale answers "may a player do this without
 * cheating", and the answer for mining is yes — the wrong answer for a body on a read-only server.</p>
 *
 * <p>There is deliberately no "read" level here. Reads are not something a body does <em>to</em> the world; they are
 * declared by not stating a requirement at all (see {@link TianshuTool#requiredBodyAutonomy()}), and "this server lets
 * bodies do nothing" is expressed by {@link #allowedBy} returning null, not by a third level.</p>
 */
public enum BodyAutonomy {

    /** What a player can do with their own hands: walk, mine, place, attack, use, pick up, equip. */
    SURVIVAL(0),

    /** What a body cannot do by itself: fly, teleport, run commands. */
    PRIVILEGED(1);

    private final int rank;

    BodyAutonomy(int rank) {
        this.rank = rank;
    }

    /**
     * What bodies may do on a server configured with {@code ceiling}; {@code null} when they may do nothing at all.
     *
     * <p>The server has one policy knob, and this is the one place that says what it means for bodies: read-only means no
     * body work, conservative means survival work, full means privileged work too. Stated here as a policy rather than
     * derived from the player scale, because the two scales do not line up: the player scale calls mining "survival"
     * and teleporting "privileged", but it has no opinion about a body acting at all.</p>
     */
    public static BodyAutonomy allowedBy(ToolAutonomy ceiling) {
        return switch (ceiling) {
            case READ_ONLY -> null;
            case CONSERVATIVE -> SURVIVAL;
            case FULL -> PRIVILEGED;
        };
    }

    /** Whether an environment allowing {@code this} also allows {@code required}. */
    public boolean permits(BodyAutonomy required) {
        return this.rank >= required.rank;
    }
}