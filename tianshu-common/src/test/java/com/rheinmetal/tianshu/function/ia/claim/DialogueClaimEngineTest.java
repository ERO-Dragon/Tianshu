package com.rheinmetal.tianshu.function.ia.claim;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaim;
import com.rheinmetal.tianshu.function.ia.model.DialogueInterruptPolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueLeasePolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationRequestPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueClaimEngineTest {
    @Test
    void scoresIntentItemEntityAndInteractionHints() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor participant = descriptor("npc", 7, List.of("trade"), List.of("minecraft:villager"), List.of("minecraft:emerald"));

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(participant), request(List.of("TRADE"), List.of("minecraft:emerald"), List.of("minecraft:villager"), true, true));

        assertEquals(1, claims.size());
        DialogueClaim claim = claims.get(0);
        assertEquals("npc", claim.participantId());
        assertEquals(1.0D, claim.score());
        assertEquals(0.85D, claim.confidence());
        assertEquals(7, claim.priority());
        assertEquals("local_score", claim.reason());
    }

    @Test
    void createsLowConfidenceFallbackClaimForGenericParticipant() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor participant = descriptor("assistant", 1, List.of(), List.of(), List.of());

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(participant), request(List.of(), List.of(), List.of(), false, false));

        assertEquals(1, claims.size());
        assertEquals(0.1D, claims.get(0).score());
        assertEquals(0.1D, claims.get(0).confidence());
    }

    @Test
    void ignoresParticipantWithoutAnyMatchingSignal() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor participant = descriptor("machine", 3, List.of("configure"), List.of("mod:machine"), List.of("mod:wrench"));

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(participant), request(List.of("talk"), List.of("minecraft:stick"), List.of("minecraft:pig"), false, false));

        assertTrue(claims.isEmpty());
    }

    @Test
    void matchesSignalsCaseInsensitively() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor participant = descriptor("item", 2, List.of(), List.of(), List.of("mod:wand"));

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(participant), request(List.of(), List.of("MOD:WAND"), List.of(), false, false));

        assertEquals(1, claims.size());
        assertEquals(0.3D, claims.get(0).score());
        assertEquals(0.25D, claims.get(0).confidence());
    }

    private DialogueParticipantDescriptor descriptor(String participantId, int priority, List<String> intents, List<String> entityTypes, List<String> itemIds) {
        return new DialogueParticipantDescriptor(participantId, "module." + participantId, participantId, priority, intents, entityTypes, itemIds, "ROUTE." + participantId, DialogueInterruptPolicy.ALLOW_AFTER_LEASE, DialogueLeasePolicy.DEFAULT);
    }

    private DialogueArbitrationRequestPayload request(List<String> hotwords, List<String> itemIds, List<String> entityRefs, boolean crosshairHit, boolean interactionKeyDown) {
        return new DialogueArbitrationRequestPayload("request", "module.ir", "player", "turn", "text", "text", hotwords, itemIds, entityRefs, new DialogueInteractionHints("", crosshairHit, interactionKeyDown, false, 0.0D, List.of()), DialogueContextSnapshot.empty("player"), 100L, 1_000L);
    }
}
