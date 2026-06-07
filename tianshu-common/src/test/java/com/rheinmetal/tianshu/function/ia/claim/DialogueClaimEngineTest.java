package com.rheinmetal.tianshu.function.ia.claim;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueEntityRef;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaim;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimRule;
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
        assertEquals("rule_score:legacy.hotword,legacy.item,legacy.entity", claim.reason());
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
    void genericParticipantOnlyClaimsWhenNoSpecificParticipantMatches() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor assistant = descriptor("assistant", 1, List.of(), List.of(), List.of());
        DialogueParticipantDescriptor machine = descriptor("machine", 3, List.of(), List.of("mod:machine"), List.of("mod:wrench"));

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(assistant, machine), request(List.of(), List.of("mod:wrench"), List.of(), false, false));

        assertEquals(1, claims.size());
        assertEquals("machine", claims.get(0).participantId());
        assertEquals("rule_score:legacy.item", claims.get(0).reason());
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

    @Test
    void claimsOwnershipFromHeldItemRuleWithoutHotword() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor create = descriptor(
                "create",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "create.held_tool",
                        0.8D,
                        0.75D,
                        DialogueClaimCondition.heldItem("create:wrench", "create:*")
                ))
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(
                List.of(create),
                request(List.of(), List.of(), List.of(), new DialogueInteractionHints("create:wrench", false, false, false, 0.0D, List.of()), DialogueContextSnapshot.empty("player"))
        );

        assertEquals(1, claims.size());
        assertEquals("create", claims.get(0).participantId());
        assertEquals(0.8D, claims.get(0).score());
        assertEquals("rule_score:create.held_tool", claims.get(0).reason());
    }

    @Test
    void claimsOwnershipFromCrosshairEntityAndHotwordRule() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor maid = descriptor(
                "maid",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.all(
                        "maid.crosshair_intent",
                        0.9D,
                        0.85D,
                        DialogueClaimCondition.crosshairEntity("touhou_little_maid:maid"),
                        DialogueClaimCondition.hotword("maid", "follow")
                ))
        );
        DialogueContextSnapshot context = new DialogueContextSnapshot(
                "player",
                "minecraft:overworld",
                List.of(new DialogueEntityRef("1", "touhou_little_maid:maid", "maid", 2.0D, true)),
                List.of(),
                java.util.Map.of()
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(
                List.of(maid),
                request(List.of("FOLLOW"), List.of(), List.of(), new DialogueInteractionHints("", true, false, false, 2.0D, List.of()), context)
        );

        assertEquals(1, claims.size());
        assertEquals("maid", claims.get(0).participantId());
        assertEquals(0.95D, claims.get(0).score(), 0.0001D);
        assertEquals(0.85D, claims.get(0).confidence());
    }

    private DialogueParticipantDescriptor descriptor(String participantId, int priority, List<String> intents, List<String> entityTypes, List<String> itemIds) {
        return new DialogueParticipantDescriptor(participantId, "module." + participantId, participantId, priority, intents, entityTypes, itemIds, "ROUTE." + participantId, DialogueInterruptPolicy.ALLOW_AFTER_LEASE, DialogueLeasePolicy.DEFAULT);
    }

    private DialogueParticipantDescriptor descriptor(String participantId, int priority, DialogueClaimProfile claimProfile) {
        return new DialogueParticipantDescriptor(participantId, "module." + participantId, participantId, priority, List.of(), List.of(), List.of(), claimProfile, "ROUTE." + participantId, DialogueInterruptPolicy.ALLOW_AFTER_LEASE, DialogueLeasePolicy.DEFAULT);
    }

    private DialogueArbitrationRequestPayload request(List<String> hotwords, List<String> itemIds, List<String> entityRefs, boolean crosshairHit, boolean interactionKeyDown) {
        return new DialogueArbitrationRequestPayload("request", "module.ir", "player", "turn", "text", "text", hotwords, itemIds, entityRefs, new DialogueInteractionHints("", crosshairHit, interactionKeyDown, false, 0.0D, List.of()), DialogueContextSnapshot.empty("player"), 100L, 1_000L);
    }

    private DialogueArbitrationRequestPayload request(List<String> hotwords, List<String> itemIds, List<String> entityRefs, DialogueInteractionHints interactionHints, DialogueContextSnapshot contextSnapshot) {
        return new DialogueArbitrationRequestPayload("request", "module.ir", "player", "turn", "text", "text", hotwords, itemIds, entityRefs, interactionHints, contextSnapshot, 100L, 1_000L);
    }
}
