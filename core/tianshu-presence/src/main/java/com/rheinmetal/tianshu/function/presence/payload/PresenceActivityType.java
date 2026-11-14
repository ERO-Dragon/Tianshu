package com.rheinmetal.tianshu.function.presence.payload;


/**
 * One kind of activity presence may show on the player-facing surface.
 *
 * <p>Each constant carries the rules that every consumer would otherwise repeat: which module owns it
 * ({@code publisherModuleId}, empty means any module), where it ranks while several activities are live
 * ({@code priority}), whether it can occupy the single primary slot ({@code primary}), and whether the module that
 * resolves a turn may close it ({@code endableByAnyModule}).</p>
 *
 * <p>Two axes are deliberately separate. A <em>primary</em> state answers "what is the player experiencing right
 * now" and only one can win; a <em>parallel</em> activity (a background task) is shown next to the primary state
 * instead of competing with it, because it can be true at the same time as any primary state. Listening is a primary
 * state: a speech turn is what the player is doing, and it competes for the same slot as thinking or responding.</p>
 *
 * <p>Ranking answers "what should the player see now", not "how bad is it": a foreground conversation always
 * outranks an ambient failure or a load, so a notice about a failed lane can never hide an answer that is being
 * delivered. A module that fails while its own phase is running ends that phase first, so the failure becomes
 * visible immediately.</p>
 */
public enum PresenceActivityType {
    /** The assistant is delivering a reply it already has. */
    RESPONDING("module.ax", 60, true, false),
    /** The assistant accepted the turn and is working on the reply. */
    THINKING("module.ax", 50, true, false),
    /** A module failed for good; the player has to act for it to recover. Any module may report it. */
    ERROR("", 40, true, false),
    /** A module is retrying after a failed start; it recovers on its own. Any module may report it. */
    RECONNECTING("", 35, true, false),
    /**
     * A module is executing a task the player can perceive, including internal maintenance.
     *
     * <p>It is <em>not</em> a primary state: a background task runs in parallel with whatever the player is doing,
     * so it becomes a parallel marker instead of competing for the one primary slot.</p>
     */
    PROCESSING_TASK("", 30, false, false),
    /** A model or service is really loading and cannot serve yet. */
    LOADING("", 25, true, false),
    /**
     * The player's speech turn is being handled.
     *
     * <p>It is a primary state: the turn owns the single primary slot while it runs, so the HUD shows the listening
     * text and pulse instead of whatever ambient state was underneath. It is turn scoped: the speech module opens it
     * when the player starts speaking and the module that resolves the turn (the arbiter) closes it. That is why this
     * type alone may be ended by a module other than its owner.</p>
     */
    LISTENING("module.asr", 70, true, true);

    private final String publisherModuleId;
    private final int priority;
    private final boolean primary;
    private final boolean endableByAnyModule;

    PresenceActivityType(String publisherModuleId, int priority, boolean primary, boolean endableByAnyModule) {
        this.publisherModuleId = publisherModuleId == null ? "" : publisherModuleId;
        this.priority = priority;
        this.primary = primary;
        this.endableByAnyModule = endableByAnyModule;
    }

    /**
     * The only module allowed to publish this activity, or an empty string when every module may.
     *
     * <p>Reserved states exist so a stale or hostile publisher cannot fake a product state it does not own;
     * the rule lives here rather than in the aggregator, which must stay free of feature-module names.</p>
     */
    public String publisherModuleId() {
        return publisherModuleId;
    }

    /** Higher wins when several primary activities are live at the same time. */
    public int priority() {
        return priority;
    }

    /** Whether an activity of this type can occupy the single primary slot. */
    public boolean primary() {
        return primary;
    }

    /**
     * Whether the module that resolves a turn may end this activity even though it did not start it.
     *
     * <p>Only turn-scoped activities answer true. The start is still owner-only; this relaxes only the end, so a
     * resolved turn can never leave a stale foreground mark behind.</p>
     */
    public boolean endableByAnyModule() {
        return endableByAnyModule;
    }
}