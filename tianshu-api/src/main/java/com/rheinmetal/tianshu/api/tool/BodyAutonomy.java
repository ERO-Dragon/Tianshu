package com.rheinmetal.tianshu.api.tool;

/**
 * How much an action may do <em>to</em> or <em>through</em> an agent body.
 *
 * <p>This is a second scale on purpose, and it is not a synonym of {@link ToolAutonomy}. Both answer "how far may this
 * go here", but they answer it about different things:</p>
 *
 * <ul>
 *   <li>{@link ToolAutonomy} asks <em>is this something a player may do without cheating</em> — it bounds a tool that
 *   acts <strong>as the player</strong>, which is why teleporting and granting items sit at the privileged end.</li>
 *   <li>{@code BodyAutonomy} asks <em>is this something a body could physically do</em> — a carrier mining is not
 *   cheating, it is the body doing its job, so mining belongs at {@link #SURVIVAL}; flying, teleporting and running
 *   commands are what a body cannot do by itself, so those belong at {@link #PRIVILEGED}.</li>
 * </ul>
 *
 * <p>Folding the two together was the defect this scale exists to fix: a body write declared only "read only", so a
 * server set to {@code READ_ONLY} — "reads only, writes are hidden and refused" — still let a carrier mine and attack,
 * because the player scale said nothing about it.</p>
 *
 * <p>The scale mirrors {@link ToolAutonomy} level for level, because a server has one policy knob, not two. Keeping the
 * types separate is about <em>what a tool declares</em>, and about being able to say "this is a survival action for a
 * body" without also claiming "this is a survival action for a player".</p>
 */
public enum BodyAutonomy {

    /** Observes: reading the body, its inventory, or what is around it. Nothing changes. */
    READ(0),

    /** What a player can do with their own hands: walk, mine, place, attack, use, pick up, equip. */
    SURVIVAL(1),

    /** What a body cannot do by itself: fly, teleport, run commands, be spawned or granted things. */
    PRIVILEGED(2);

    private final int rank;

    BodyAutonomy(int rank) {
        this.rank = rank;
    }

    /** The level a tool needs, expressed on the scale a server configures. */
    public ToolAutonomy asToolAutonomy() {
        return switch (this) {
            case READ -> ToolAutonomy.READ_ONLY;
            case SURVIVAL -> ToolAutonomy.CONSERVATIVE;
            case PRIVILEGED -> ToolAutonomy.FULL;
        };
    }

    /** The same level, read as a body requirement — what a provider's ceiling means for bodies. */
    public static BodyAutonomy from(ToolAutonomy autonomy) {
        return switch (autonomy) {
            case READ_ONLY -> READ;
            case CONSERVATIVE -> SURVIVAL;
            case FULL -> PRIVILEGED;
        };
    }

    /** Whether an environment allowing {@code this} also allows {@code required}. */
    public boolean permits(BodyAutonomy required) {
        return this.rank >= required.rank;
    }
}