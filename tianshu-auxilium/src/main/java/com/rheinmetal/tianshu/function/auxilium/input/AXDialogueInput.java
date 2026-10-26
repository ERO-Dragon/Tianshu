package com.rheinmetal.tianshu.function.auxilium.input;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One dialogue turn's input, as this module understands it.
 *
 * <p>Why this type exists: the module used to consume the intake side's delivery payload directly, which made
 * the intake module a hard dependency of everything from the gateway to the turn orchestrator. Input is
 * conceptually "what the player said", whether it arrived spoken or typed, so the module keeps its own
 * currency and the translation happens once, at the edge.</p>
 *
 * <p><b>Three identities, and only three.</b> Each answers a different question and none can stand in for
 * another:</p>
 * <ul>
 *   <li>{@code playerId} — <em>who</em>. Memory and preferences are owned per player, not per world.</li>
 *   <li>{@code sessionId} — <em>which conversation</em>. History grouping, session control, the speech
 *       session, and the activity id are keyed by it.</li>
 *   <li>{@code turnId} — <em>which turn</em>. History order, delegation, and the per-turn fact query.</li>
 * </ul>
 * <p>The producing side's request id is deliberately absent: it only ever served as a fallback for
 * {@code turnId} and as a diagnostic label, and carrying a fourth identifier for that is how two names for
 * one thing start drifting apart.</p>
 *
 * <p>The rest is the utterance and the world around it:</p>
 * <ul>
 *   <li>{@code text} — spoken input arrives already repaired by the intake side; typed input arrives as-is.</li>
 *   <li>{@code contextSnapshot} / {@code interactionHints} — platform vocabulary, so they need no bridge. A
 *       typed turn leaves them empty and lets the module collect its own.</li>
 *   <li>{@code objectMatches} — what the repair step resolved the utterance to. A typed turn has none.</li>
 * </ul>
 */
public record AXDialogueInput(
        String playerId,
        String sessionId,
        String turnId,
        String text,
        DialogueContextSnapshot contextSnapshot,
        DialogueInteractionHints interactionHints,
        List<DialogueEntityRef> matchedEntityRefs,
        Map<String, String> displayNames,
        List<AXMatchedObject> objectMatches,
        long expireAtMillis
) {

    private static final AtomicLong TYPED_TURNS = new AtomicLong();

    public AXDialogueInput {
        playerId = clean(playerId);
        sessionId = clean(sessionId);
        text = clean(text);
        // A turn without an id has no place in the history and no activity of its own, so the type guarantees
        // one rather than leaving every caller to invent the same fallback.
        String effectiveTurn = clean(turnId);
        turnId = effectiveTurn.isBlank() ? "turn-" + Integer.toHexString((sessionId + "|" + text).hashCode()) : effectiveTurn;
        contextSnapshot = contextSnapshot == null ? DialogueContextSnapshot.empty(playerId) : contextSnapshot;
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        matchedEntityRefs = matchedEntityRefs == null ? List.of() : List.copyOf(matchedEntityRefs);
        displayNames = displayNames == null ? Map.of() : Map.copyOf(displayNames);
        objectMatches = objectMatches == null ? List.of() : List.copyOf(objectMatches);
        expireAtMillis = Math.max(0L, expireAtMillis);
    }

    /**
     * A turn the player typed: they said it, nothing recognized it.
     *
     * <p>Session identity is produced here because a typed turn has no inbound delivery to carry one; it is
     * stable per player so consecutive typed turns read as one conversation, and separate from the spoken
     * sessions so the two channels cannot close each other's session.</p>
     *
     * <p>Context and matches are left empty on purpose: the module collects the world itself when the input
     * carries none.</p>
     */
    public static AXDialogueInput typed(String playerId, String text, long expireAtMillis) {
        String owner = clean(playerId);
        String session = "ax.text." + (owner.isBlank() ? "local" : owner);
        String turn = "text-" + TYPED_TURNS.incrementAndGet();
        return new AXDialogueInput(owner, session, turn, text, null, null, List.of(), Map.of(), List.of(), expireAtMillis);
    }

    /** How long this input stays worth answering; a non-positive deadline means "no deadline". */
    public boolean isExpired(long nowMillis) {
        return expireAtMillis > 0L && expireAtMillis < nowMillis;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
