package com.rheinmetal.tianshu.function.ia.claim;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimSource;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueClaimEngineTest {
    @Test
    void wakeWordCreatesAnUtteranceHardClaim() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor participant = descriptor("maid", 7, DialogueClaimProfile.rules(DialogueClaimRule.any(
                "maid.wake_word", DialogueClaimCondition.wakeWord("酒狐")
        )));

        // The condition names the word the player says; the word the module registered is a different string here on
        // purpose, because a claim is decided by what was said, not by which registered alias happened to hit.
        List<DialogueClaim> claims = engine.collectLocalClaims(
                List.of(participant),
                requestWith(List.of(new IrModuleMatch("module.maid", "酒狐", "maid.name")),
                        List.of(), DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player"))
        );

        assertEquals(1, claims.size());
        DialogueClaim claim = claims.get(0);
        assertEquals("maid", claim.participantId());
        assertEquals(DialogueClaimSource.UTTERANCE, claim.source());
        assertEquals(7, claim.priority());
        assertEquals("hard_claim:maid.wake_word", claim.reason());
    }

    @Test
    void mentionedItemNeverCountsAsAHeldOrEquippedState() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor machine = descriptor("machine", 3, DialogueClaimProfile.rules(DialogueClaimRule.any(
                "machine.item",
                DialogueClaimCondition.heldItem("mod:wrench"),
                DialogueClaimCondition.equippedItem("mod:wrench")
        )));

        List<DialogueClaim> textOnlyClaims = engine.collectLocalClaims(List.of(machine), request(List.of(), List.of(new IrObjectMatch("扳手", "mod:wrench", "item", "item.mod.wrench")), DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player")));
        List<DialogueClaim> heldClaims = engine.collectLocalClaims(List.of(machine), request(List.of(), List.of(), new DialogueInteractionHints("mod:wrench", false, false, false, 0.0D), DialogueContextSnapshot.empty("player")));

        assertTrue(textOnlyClaims.isEmpty());
        assertEquals(1, heldClaims.size());
        assertEquals(DialogueClaimSource.CONTEXT, heldClaims.get(0).source());
        assertEquals("hard_claim:machine.item", heldClaims.get(0).reason());
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
                DialogueClaimProfile.defaultOwnerWithRules(DialogueClaimRule.any(
                        "assistant.wake_word",
                        DialogueClaimCondition.wakeWord("天枢")
                ))
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(participant), request(List.of("天枢"), List.of(), DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player")));

        assertEquals(1, claims.size());
        assertEquals("assistant", claims.get(0).participantId());
        assertEquals("hard_claim:assistant.wake_word", claims.get(0).reason());
    }

    @Test
    void oneClaimKeepsEveryMatchedRuleInDeclarationOrder() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor create = descriptor(
                "create",
                2,
                DialogueClaimProfile.rules(
                        DialogueClaimRule.any("create.held_tool", DialogueClaimCondition.heldItem("create:wrench", "create:*")),
                        DialogueClaimRule.all("create.active_tool", DialogueClaimCondition.heldItem("create:wrench"), DialogueClaimCondition.interactionKey())
                )
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(
                List.of(create),
                request(List.of(), List.of(), new DialogueInteractionHints("create:wrench", false, true, false, 0.0D), DialogueContextSnapshot.empty("player"))
        );

        assertEquals(1, claims.size());
        assertEquals(DialogueClaimSource.CONTEXT, claims.get(0).source());
        assertEquals("hard_claim:create.held_tool,create.active_tool", claims.get(0).reason());
    }

    @Test
    void aRuleThatAlsoMatchedAContextConditionRanksAsContextEvenWhenTheWakeWordDidToo() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor maid = descriptor(
                "maid",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.all(
                        "maid.crosshair_wake",
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
                request(List.of("酒狐"), List.of(), new DialogueInteractionHints("", true, false, false, 2.0D), context)
        );

        assertEquals(1, claims.size());
        assertEquals(DialogueClaimSource.CONTEXT, claims.get(0).source());
        assertEquals("hard_claim:maid.crosshair_wake", claims.get(0).reason());
    }

    @Test
    void anAnyRuleKeepsTheSourceOfTheConditionsThatActuallyMatched() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor maid = descriptor(
                "maid",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "maid.either",
                        DialogueClaimCondition.crosshairEntity("touhou_little_maid:maid"),
                        DialogueClaimCondition.wakeWord("酒狐")
                ))
        );

        List<DialogueClaim> spoken = engine.collectLocalClaims(
                List.of(maid),
                request(List.of("酒狐"), List.of(), DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player"))
        );
        List<DialogueClaim> aimed = engine.collectLocalClaims(
                List.of(maid),
                request(List.of(), List.of(), new DialogueInteractionHints("", true, false, false, 2.0D),
                        new DialogueContextSnapshot("player", "minecraft:overworld",
                                List.of(new DialogueEntityRef("1", "touhou_little_maid:maid", "maid", 2.0D, true)),
                                List.of(), java.util.Map.of()))
        );

        assertEquals(DialogueClaimSource.UTTERANCE, spoken.get(0).source());
        assertEquals(DialogueClaimSource.CONTEXT, aimed.get(0).source());
    }

    @Test
    void equippedItemCanClaimWithoutInventoryScan() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor armorModule = descriptor(
                "armor",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.any("armor.equipped", DialogueClaimCondition.equippedItem("mod:signal_helmet")))
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
        assertEquals(DialogueClaimSource.CONTEXT, claims.get(0).source());
    }

    @Test
    void crosshairEntityClaimsByTypeAndKeepsTheEntityReferenceForDelivery() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor maid = descriptor(
                "maid",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.any("maid.crosshair", DialogueClaimCondition.crosshairEntity("touhou_little_maid:maid")))
        );
        DialogueEntityRef aimedMaid = new DialogueEntityRef("maid-uuid", "touhou_little_maid:maid", "酒狐", 4.0D, true);
        DialogueContextSnapshot context = new DialogueContextSnapshot(
                "player",
                "minecraft:overworld",
                List.of(aimedMaid),
                List.of(),
                java.util.Map.of()
        );
        DialogueArbitrationInput input = request(List.of(), List.of(), new DialogueInteractionHints("", true, false, false, 4.0D), context);

        List<DialogueClaim> claims = engine.collectLocalClaims(List.of(maid), input);
        DialogueDeliveryPayload delivery = DialogueDeliveryPayload.from("session", input);

        assertEquals(1, claims.size());
        assertEquals("maid", claims.get(0).participantId());
        assertEquals("hard_claim:maid.crosshair", claims.get(0).reason());
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
                DialogueClaimProfile.rules(DialogueClaimRule.any("villager.mentioned", DialogueClaimCondition.mentionedEntity("minecraft:villager")))
        );
        DialogueArbitrationRequestPayload request = new DialogueArbitrationRequestPayload(
                "request",
                "module.ir",
                "player",
                "turn",
                9L,
                "村民怎么交易",
                "村名怎么交易",
                List.of(new IrObjectMatch("村民", "minecraft:villager", "entity", "entity.minecraft.villager")),
                List.of(),
                100L,
                1_000L
        );

        List<DialogueClaim> claims = engine.collectLocalClaims(
                List.of(villagerModule),
                DialogueArbitrationInput.from(request, new DialogueContextFrame(DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player")))
        );

        assertEquals(1, claims.size());
        assertEquals("villager", claims.get(0).participantId());
        assertEquals(DialogueClaimSource.UTTERANCE, claims.get(0).source());
        assertEquals("hard_claim:villager.mentioned", claims.get(0).reason());
    }

    /**
     * The registry kind decides, not the shape of the id.
     *
     * <p>{@code minecraft:minecart} is both an item and a creature, so a mention of the item must not satisfy a
     * condition that asks for the creature — the same reasoning that makes the spoken word unreliable here.</p>
     */
    @Test
    void mentionedItemIsNotAMentionedEntityEvenWhenTheRegistryIdCollides() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor minecartModule = descriptor(
                "minecart",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.any("minecart.mentioned", DialogueClaimCondition.mentionedEntity("minecraft:minecart")))
        );
        DialogueArbitrationRequestPayload itemMention = new DialogueArbitrationRequestPayload(
                "request", "module.ir", "player", "turn", 9L,
                "矿车物品怎么做", "矿车物品怎么做",
                List.of(new IrObjectMatch("矿车物品", "minecraft:minecart", "item", "item.minecraft.minecart")),
                List.of(),
                100L, 1_000L
        );
        DialogueArbitrationRequestPayload entityMention = new DialogueArbitrationRequestPayload(
                "request", "module.ir", "player", "turn", 9L,
                "矿车实体在哪里", "矿车实体在哪里",
                List.of(new IrObjectMatch("矿车实体", "minecraft:minecart", "entity", "entity.minecraft.minecart")),
                List.of(),
                100L, 1_000L
        );

        assertTrue(engine.collectLocalClaims(List.of(minecartModule), input(itemMention)).isEmpty(),
                "an item mention must not claim a creature condition");
        assertEquals(1, engine.collectLocalClaims(List.of(minecartModule), input(entityMention)).size());
    }

    @Test
    void namespaceWildcardMatchesHeldItemButNotDifferentNamespace() {
        DialogueClaimEngine engine = new DialogueClaimEngine();
        DialogueParticipantDescriptor create = descriptor(
                "create",
                2,
                DialogueClaimProfile.rules(DialogueClaimRule.any("create.namespace", DialogueClaimCondition.heldItem("create:*")))
        );

        List<DialogueClaim> matching = engine.collectLocalClaims(
                List.of(create),
                request(List.of(), List.of(), new DialogueInteractionHints("create:wrench", false, false, false, 0.0D), DialogueContextSnapshot.empty("player"))
        );
        List<DialogueClaim> nonMatching = engine.collectLocalClaims(
                List.of(create),
                request(List.of(), List.of(), new DialogueInteractionHints("minecraft:stick", false, false, false, 0.0D), DialogueContextSnapshot.empty("player"))
        );

        assertEquals(1, matching.size());
        assertTrue(nonMatching.isEmpty());
    }

    private DialogueParticipantDescriptor descriptor(String participantId, int priority, DialogueClaimProfile claimProfile) {
        return new DialogueParticipantDescriptor(participantId, "module." + participantId, participantId, priority, claimProfile, DialogueTriggerGroup.EMPTY, "ROUTE." + participantId, DialogueTurnProcessingPolicy.DEFAULT);
    }

    private DialogueArbitrationInput request(List<String> triggerWords, List<IrObjectMatch> objectMatches, DialogueInteractionHints interactionHints, DialogueContextSnapshot contextSnapshot) {
        return requestWith(triggerWords.stream().map(word -> new IrModuleMatch("module.test", word, word)).toList(),
                objectMatches, interactionHints, contextSnapshot);
    }

    private DialogueArbitrationInput requestWith(List<IrModuleMatch> moduleMatches, List<IrObjectMatch> objectMatches, DialogueInteractionHints interactionHints, DialogueContextSnapshot contextSnapshot) {
        DialogueArbitrationRequestPayload request = new DialogueArbitrationRequestPayload("request", "module.ir", "player", "turn", 9L, "text", "text", objectMatches, moduleMatches, 100L, 1_000L);
        return DialogueArbitrationInput.from(request, new DialogueContextFrame(interactionHints, contextSnapshot));
    }

    private DialogueArbitrationInput input(DialogueArbitrationRequestPayload request) {
        return DialogueArbitrationInput.from(request, new DialogueContextFrame(DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("player")));
    }
}
