package com.rheinmetal.tianshu.function.ia.claim;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionDecay;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimStrength;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueClaimEngineTest {
    @Test
    void wakeWordCreatesStrongSlowHardClaim() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor participant = descriptor("maid", 7, List.of("酒狐"), List.of(), List.of());

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(participant), request(List.of("酒狐"), List.of(), DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player")));

        assertEquals(1, claims.size());
        DialogueClaim claim = claims.get(0);
        assertEquals("maid", claim.participantId());
        assertEquals(DialogueClaimStrength.STRONG, claim.strength());
        assertEquals(DialogueAttentionDecay.SLOW, claim.decay());
        assertEquals(7, claim.priority());
        assertEquals("hard_claim:legacy.wake_word", claim.reason());
    }

    @Test
    void legacyItemUsesGameStateOnlyAndIgnoresMatchedItemText() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor machine = descriptor("machine", 3, List.of(), List.of(), List.of("mod:wrench"));

        List<DialogueClaim> textOnlyClaims = engine.collectLocalClaims(List.of(machine), request(List.of(), List.of("mod:wrench"), DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player")));
        List<DialogueClaim> heldClaims = engine.collectLocalClaims(List.of(machine), request(List.of(), List.of(), new DialogueInteractionHints("mod:wrench", false, false, false, 0.0D, List.of()), DialogueContextSnapshot.empty("player")));

        assertTrue(textOnlyClaims.isEmpty());
        assertEquals(1, heldClaims.size());
        assertEquals(DialogueClaimStrength.NORMAL, heldClaims.get(0).strength());
        assertEquals(DialogueAttentionDecay.FAST, heldClaims.get(0).decay());
        assertEquals("hard_claim:legacy.item", heldClaims.get(0).reason());
    }

    @Test
    void defaultOwnerParticipantDoesNotCreateHardClaim() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor participant = descriptor("assistant", 1, DialogueClaimProfile.DEFAULT_OWNER);

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(participant), request(List.of(), List.of(), DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player")));

        assertTrue(claims.isEmpty());
    }

    @Test
    void defaultOwnerCanAlsoCreateHardClaimWhenRulesMatch() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor participant = descriptor(
                "assistant",
                1,
                DialogueClaimProfile.defaultOwnerWithRules(DialogueClaimRule.anyStrong(
                        "assistant.wake_word",
                        DialogueAttentionDecay.SLOW,
                        DialogueClaimCondition.wakeWord("天枢")
                ))
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(participant), request(List.of("天枢"), List.of(), DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player")));

        assertEquals(1, claims.size());
        assertEquals("assistant", claims.get(0).participantId());
        assertEquals("hard_claim:assistant.wake_word", claims.get(0).reason());
    }

    @Test
    void choosesStrongestMatchedRuleForParticipant() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor create = descriptor(
                "create",
                2,
                DialogueClaimProfile.rules(
                        DialogueClaimRule.anyNormal("create.held_tool", DialogueClaimCondition.heldItem("create:wrench", "create:*")),
                        DialogueClaimRule.allStrong("create.active_tool", DialogueAttentionDecay.FAST, DialogueClaimCondition.heldItem("create:wrench"), DialogueClaimCondition.interactionKey())
                )
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(
                List.of(create),
                request(List.of(), List.of(), new DialogueInteractionHints("create:wrench", false, true, false, 0.0D, List.of()), DialogueContextSnapshot.empty("player"))
        );

        assertEquals(1, claims.size());
        assertEquals(DialogueClaimStrength.STRONG, claims.get(0).strength());
        assertEquals(DialogueAttentionDecay.FAST, claims.get(0).decay());
        assertEquals("hard_claim:create.held_tool,create.active_tool", claims.get(0).reason());
    }

    @Test
    void allRuleCanCombineCrosshairEntityAndWakeWord() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor maid = descriptor(
                "maid",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.allStrong(
                        "maid.crosshair_wake",
                        DialogueAttentionDecay.SLOW,
                        DialogueClaimCondition.crosshairEntity("touhou_little_maid:maid"),
                        DialogueClaimCondition.wakeWord("酒狐")
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
                request(List.of("酒狐"), List.of(), new DialogueInteractionHints("", true, false, false, 2.0D, List.of()), context)
        );

        assertEquals(1, claims.size());
        assertEquals(DialogueClaimStrength.STRONG, claims.get(0).strength());
        assertEquals(DialogueAttentionDecay.SLOW, claims.get(0).decay());
    }

    @Test
    void equippedItemCanClaimWithoutInventoryScan() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor armorModule = descriptor(
                "armor",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.anyNormal("armor.equipped", DialogueClaimCondition.equippedItem("mod:signal_helmet")))
        );
        DialogueContextSnapshot context = new DialogueContextSnapshot(
                "player",
                "minecraft:overworld",
                List.of(),
                List.of("mod:signal_helmet"),
                java.util.Map.of()
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(armorModule), request(List.of(), List.of(), DialogueInteractionHints.empty(), context));

        assertEquals(1, claims.size());
        assertEquals("armor", claims.get(0).participantId());
        assertEquals(DialogueClaimStrength.NORMAL, claims.get(0).strength());
    }

    @Test
    void nearestEntityWithinClaimsByEntityTypeAndKeepsEntityReferenceForDelivery() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor maid = descriptor(
                "maid",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.anyNormal("maid.nearest", DialogueClaimCondition.nearestEntityWithin(8.0D, "touhou_little_maid:maid")))
        );
        DialogueEntityRef nearestMaid = new DialogueEntityRef("maid-uuid", "touhou_little_maid:maid", "酒狐", 4.0D, false);
        DialogueContextSnapshot context = new DialogueContextSnapshot(
                "player",
                "minecraft:overworld",
                List.of(nearestMaid),
                List.of(),
                java.util.Map.of()
        );
        DialogueArbitrationInput input = request(List.of(), List.of(), DialogueInteractionHints.empty(), context);

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(maid), input);
        DialogueDeliveryPayload delivery = DialogueDeliveryPayload.from("session", input);

        assertEquals(1, claims.size());
        assertEquals("maid", claims.get(0).participantId());
        assertEquals("hard_claim:maid.nearest", claims.get(0).reason());
        assertEquals(1, delivery.matchedEntityRefs().size());
        assertEquals("maid-uuid", delivery.matchedEntityRefs().get(0).entityId());
        assertEquals("touhou_little_maid:maid", delivery.matchedEntityRefs().get(0).entityTypeId());
    }

    @Test
    void mentionedEntityClaimsFromIrTextEntityRepairWithoutNearbyEntityInstance() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor villagerModule = descriptor(
                "villager",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.anyNormal("villager.mentioned", DialogueClaimCondition.mentionedEntity("minecraft:villager")))
        );
        DialogueArbitrationRequestPayload request = new DialogueArbitrationRequestPayload(
                "request",
                "module.ir",
                "player",
                "turn",
                9L,
                "村民怎么交易",
                "村名怎么交易",
                List.of(),
                List.of(),
                List.of("minecraft:villager"),
                100L,
                1_000L
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(
                List.of(villagerModule),
                DialogueArbitrationInput.from(request, new DialogueContextFrame(DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player")))
        );

        assertEquals(1, claims.size());
        assertEquals("villager", claims.get(0).participantId());
        assertEquals("hard_claim:villager.mentioned", claims.get(0).reason());
    }

    @Test
    void namespaceWildcardMatchesHeldItemButNotDifferentNamespace() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor create = descriptor(
                "create",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.anyNormal("create.namespace", DialogueClaimCondition.heldItem("create:*")))
        );

        List<DialogueClaim> matching = engine.collectLocalClaims(
                List.of(create),
                request(List.of(), List.of(), new DialogueInteractionHints("create:wrench", false, false, false, 0.0D, List.of()), DialogueContextSnapshot.empty("player"))
        );
        List<DialogueClaim> nonMatching = engine.collectLocalClaims(
                List.of(create),
                request(List.of(), List.of(), new DialogueInteractionHints("minecraft:stick", false, false, false, 0.0D, List.of()), DialogueContextSnapshot.empty("player"))
        );

        assertEquals(1, matching.size());
        assertTrue(nonMatching.isEmpty());
    }

    @Test
    void nearestEntityWithinRespectsDistanceBoundary() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor maid = descriptor(
                "maid",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.anyNormal("maid.nearest", DialogueClaimCondition.nearestEntityWithin(8.0D, "touhou_little_maid:maid")))
        );
        DialogueContextSnapshot tooFar = new DialogueContextSnapshot(
                "player",
                "minecraft:overworld",
                List.of(new DialogueEntityRef("maid-uuid", "touhou_little_maid:maid", "maid", 8.1D, false)),
                List.of(),
                java.util.Map.of()
        );
        DialogueContextSnapshot onBoundary = new DialogueContextSnapshot(
                "player",
                "minecraft:overworld",
                List.of(new DialogueEntityRef("maid-uuid", "touhou_little_maid:maid", "maid", 8.0D, false)),
                List.of(),
                java.util.Map.of()
        );

        assertTrue(engine.collectLocalClaims(List.of(maid), request(List.of(), List.of(), DialogueInteractionHints.empty(), tooFar)).isEmpty());
        assertEquals(1, engine.collectLocalClaims(List.of(maid), request(List.of(), List.of(), DialogueInteractionHints.empty(), onBoundary)).size());
    }

    @Test
    void contextFactCanRequireOnlyKeyOrExactValue() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor keyOnly = descriptor(
                "key",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.anyNormal("fact.key", DialogueClaimCondition.contextFact("screen_open")))
        );
        DialogueParticipantDescriptor valueExact = descriptor(
                "value",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.anyNormal("fact.value", DialogueClaimCondition.contextFact("screen_open", "create:belt")))
        );
        DialogueContextSnapshot context = new DialogueContextSnapshot(
                "player",
                "minecraft:overworld",
                List.of(),
                List.of(),
                java.util.Map.of("screen_open", "create:belt")
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(keyOnly, valueExact), request(List.of(), List.of(), DialogueInteractionHints.empty(), context));

        assertEquals(List.of("key", "value"), claims.stream().map(DialogueClaim::participantId).sorted().toList());
    }

    private DialogueParticipantDescriptor descriptor(String participantId, int priority, List<String> wakeWords, List<String> entityTypes, List<String> itemIds) {
        return new DialogueParticipantDescriptor(participantId, "module." + participantId, participantId, priority, wakeWords, entityTypes, itemIds, "ROUTE." + participantId, DialogueTurnProcessingPolicy.DEFAULT);
    }

    private DialogueParticipantDescriptor descriptor(String participantId, int priority, DialogueClaimProfile claimProfile) {
        return new DialogueParticipantDescriptor(participantId, "module." + participantId, participantId, priority, List.of(), List.of(), List.of(), claimProfile, "ROUTE." + participantId, DialogueTurnProcessingPolicy.DEFAULT);
    }

    private DialogueArbitrationInput request(List<String> wakeWords, List<String> itemIds, DialogueInteractionHints interactionHints, DialogueContextSnapshot contextSnapshot) {
        DialogueArbitrationRequestPayload request = new DialogueArbitrationRequestPayload("request", "module.ir", "player", "turn", 9L, "text", "text", wakeWords, itemIds, 100L, 1_000L);
        return DialogueArbitrationInput.from(request, new DialogueContextFrame(interactionHints, contextSnapshot));
    }
}
