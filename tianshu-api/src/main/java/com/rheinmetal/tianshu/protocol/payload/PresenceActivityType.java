package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;

/**
 * One kind of activity presence may show on the player-facing surface.
 *
 * <p>Each constant carries the two rules that used to be switches inside the client aggregator: which
 * module is allowed to publish it ({@code publisherModuleId}, empty means "any module"), and where it ranks
 * while several activities are live. Keeping the rules on the contract means a new product state is added
 * in one place instead of three that can drift apart.</p>
 *
 * <p>Ranking answers "what should the player see now", not "how bad is it": a foreground conversation
 * always outranks an ambient lane condition, so a notice about a degraded backend can never hide an answer
 * that is being delivered. Between the ambient conditions a failure outranks progress, because only the
 * failure asks the player to do something.</p>
 */
public enum PresenceActivityType {
    /** The assistant is delivering a reply it already has. */
    RESPONDING(ProtocolModuleIds.AX, 60),
    /** The assistant accepted a turn and is working on the reply. */
    THINKING(ProtocolModuleIds.AX, 50),
    /** A lane failed for good; the player has to act for it to recover. */
    ERROR(ProtocolModuleIds.LLM, 40),
    /** Any module is executing a task the player can perceive. */
    PROCESSING_TASK("", 30),
    /** A model or service is really loading and cannot serve yet. */
    LOADING("", 25),
    /** A lane is retrying after a failed start; it recovers on its own. */
    RECONNECTING(ProtocolModuleIds.LLM, 20),
    /** A lane runs on a reduced backend and says so instead of staying silent. */
    DEGRADED(ProtocolModuleIds.LLM, 15),
    /**
     * The player's own speech is being processed. It is a foreground flag rather than a primary state, so it
     * has the lowest rank and is rendered next to whatever primary state is live.
     */
    LISTENING(ProtocolModuleIds.ASR, 0);

    private final String publisherModuleId;
    private final int priority;

    PresenceActivityType(String publisherModuleId, int priority) {
        this.publisherModuleId = publisherModuleId == null ? "" : publisherModuleId;
        this.priority = priority;
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

    /** Higher wins when several activities are live at the same time. */
    public int priority() {
        return priority;
    }
}
