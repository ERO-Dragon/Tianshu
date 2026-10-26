package com.rheinmetal.tianshu.function.auxilium.input;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;

import java.util.List;
import java.util.Map;

/**
 * One dialogue turn's input, as this module understands it.
 *
 * <p>Why this type exists: the module used to consume the intake side's delivery payload directly, which made
 * the intake module a hard dependency of everything from the gateway to the turn orchestrator. Input is
 * conceptually "what the player said", no matter whether it arrived spoken or typed, so the module keeps its
 * own currency and the translation happens once, at the edge.</p>
 *
 * <p>What each part is and is not:</p>
 * <ul>
 *   <li>{@code text} — the utterance. Spoken input arrives already repaired by the intake side; typed input
 *       arrives as-is.</li>
 *   <li>{@code sessionId} / {@code playerId} / {@code turnId} / {@code requestId}, {@code expireAtMillis} —
 *       identity and deadline, supplied by the session, not by the input device.</li>
 *   <li>{@code contextSnapshot} and {@code interactionHints} — the world as it was around the utterance. These
 *       are platform vocabulary, so they need no bridge.</li>
 *   <li>{@code objectMatches} — what the intake side's repair resolved the utterance to. A typed turn has none:
 *       nothing was recognized, so nothing was matched.</li>
 * </ul>
 *
 * <p>Everything is normalised at construction so callers never have to null-check a field of an input.</p>
 */
public record AXDialogueInput(
        String sessionId,
        String requestId,
        String playerId,
        String turnId,
        String text,
        DialogueContextSnapshot contextSnapshot,
        DialogueInteractionHints interactionHints,
        List<DialogueEntityRef> matchedEntityRefs,
        Map<String, String> displayNames,
        List<AXMatchedObject> objectMatches,
        long expireAtMillis
) {

    public AXDialogueInput {
        sessionId = clean(sessionId);
        requestId = clean(requestId);
        playerId = clean(playerId);
        turnId = clean(turnId);
        text = clean(text);
        contextSnapshot = contextSnapshot == null ? DialogueContextSnapshot.empty(playerId) : contextSnapshot;
        interactionHints = interactionHints == null ? DialogueInteractionHints.empty() : interactionHints;
        matchedEntityRefs = matchedEntityRefs == null ? List.of() : List.copyOf(matchedEntityRefs);
        displayNames = displayNames == null ? Map.of() : Map.copyOf(displayNames);
        objectMatches = objectMatches == null ? List.of() : List.copyOf(objectMatches);
        expireAtMillis = Math.max(0L, expireAtMillis);
    }

    /** Turn identity: the session's turn id when it has one, the request id otherwise. */
    public String turnIdentity() {
        return turnId.isBlank() ? requestId : turnId;
    }

    /** How long this input stays worth answering; a non-positive deadline means "no deadline". */
    public boolean isExpired(long nowMillis) {
        return expireAtMillis > 0L && expireAtMillis < nowMillis;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
