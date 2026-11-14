package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.function.ia.claim.DialogueClaimEngine;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.model.DialogueArbitrationInput;
import com.rheinmetal.tianshu.function.ia.policy.DialogueArbitrationPolicy;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantContractValidator;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaim;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimConditionType;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimSource;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueArbitrationDecision;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolCapabilityRegistration;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabParticipantsTest {
    private final DialogueClaimEngine claimEngine = new DialogueClaimEngine();
    private final DialogueArbitrationPolicy policy = new DialogueArbitrationPolicy();

    @Test
    void alphaClaimsFromTheConfiguredCrosshairEntityTypes() {
        DialogueParticipantDescriptor cowOnly = LabParticipants.alpha("Alpha", List.of("minecraft:cow"), DialogueHoldTier.TEN_SECONDS);

        assertEquals(DialogueClaimConditionType.CROSSHAIR_ENTITY, conditionType(cowOnly));
        assertEquals(List.of("minecraft:cow"), conditionValues(cowOnly));
        assertEquals(LabParticipants.ALPHA_PRIORITY, cowOnly.priority());
        assertTrue(cowOnly.hasPartnerIcon());
        assertTrue(cowOnly.triggerGroup().empty(), "a context participant has no wake words");
    }

    @Test
    void theBenchCarriesTheSelectedHoldTier() {
        assertEquals(DialogueHoldTier.TWENTY_SECONDS,
                LabParticipants.alpha("Alpha", List.of(), DialogueHoldTier.TWENTY_SECONDS).holdTier());
        assertEquals(DialogueHoldTier.FIVE_SECONDS,
                LabParticipants.beta("Beta", "minecraft:diamond", DialogueHoldTier.FIVE_SECONDS).holdTier());
        assertEquals(DialogueHoldTier.TEN_SECONDS,
                LabParticipants.gamma("Gamma", List.of("伽马"), DialogueHoldTier.TEN_SECONDS).holdTier());
        assertEquals(DialogueHoldTier.NONE,
                LabParticipants.alpha("Alpha", List.of(), null).holdTier(),
                "a missing tier must not turn into an accidental lease");
    }

    @Test
    void anEmptyCrosshairFilterMeansAnyCrosshairEntity() {
        DialogueParticipantDescriptor anyEntity = LabParticipants.alpha("Alpha", List.of(), DialogueHoldTier.TEN_SECONDS);

        assertEquals(DialogueClaimConditionType.CROSSHAIR_ENTITY, conditionType(anyEntity));
        assertTrue(conditionValues(anyEntity).isEmpty());
    }

    @Test
    void betaClaimsFromTheConfiguredHeldItem() {
        DialogueParticipantDescriptor beta = LabParticipants.beta("Beta", "minecraft:diamond", DialogueHoldTier.TEN_SECONDS);

        assertEquals(DialogueClaimConditionType.HELD_ITEM, conditionType(beta));
        assertEquals(List.of("minecraft:diamond"), conditionValues(beta));
        assertEquals(LabParticipants.BETA_PRIORITY, beta.priority());
        assertThrows(IllegalArgumentException.class, () -> LabParticipants.beta("Beta", " ", DialogueHoldTier.TEN_SECONDS),
                "a blank item would turn the condition into 'any held item'");
    }

    @Test
    void gammaClaimsFromItsWakeWordsAndAlsoRegistersThemAsTriggers() {
        DialogueParticipantDescriptor gamma = LabParticipants.gamma("Gamma", List.of("伽马", "gamma"), DialogueHoldTier.TEN_SECONDS);

        assertEquals(DialogueClaimConditionType.WAKE_WORD, conditionType(gamma));
        assertEquals(List.of("伽马", "gamma"), conditionValues(gamma));
        assertEquals(List.of("伽马", "gamma"), gamma.triggerGroup().wakeWords());
        assertEquals(LabParticipants.GAMMA_PRIORITY, gamma.priority());
        assertThrows(IllegalArgumentException.class, () -> LabParticipants.gamma("Gamma", List.of(), DialogueHoldTier.TEN_SECONDS));
    }

    @Test
    void parseListTrimsDropsBlanksAndDeduplicates() {
        assertEquals(List.of("minecraft:cow", "minecraft:pig"),
                LabParticipants.parseList(" minecraft:cow, ,minecraft:pig, minecraft:cow "));
        assertEquals(List.of(), LabParticipants.parseList("  "));
    }

    /**
     * The three participants against the real claim engine and policy: crosshair and held both claim as
     * CONTEXT, the higher-priority crosshair wins, and a wake word alone is UTTERANCE.
     */
    @Test
    void theBenchFixturesProduceTheClaimsTheArbiterRanks() {
        DialogueParticipantDescriptor alpha = LabParticipants.alpha("Alpha", List.of(), DialogueHoldTier.TEN_SECONDS);
        DialogueParticipantDescriptor beta = LabParticipants.beta("Beta", "minecraft:diamond", DialogueHoldTier.TEN_SECONDS);
        DialogueParticipantDescriptor gamma = LabParticipants.gamma("Gamma", List.of("伽马"), DialogueHoldTier.TEN_SECONDS);
        List<DialogueParticipantDescriptor> participants = List.of(alpha, beta, gamma);

        DialogueInteractionHints held = new DialogueInteractionHints("minecraft:diamond", true, false, false, 2.0D);
        DialogueContextSnapshot crosshair = new DialogueContextSnapshot(
                "local",
                "minecraft:overworld",
                List.of(new DialogueEntityRef("entity-1", "minecraft:cow", "Cow", 2.0D, true)),
                List.of(),
                java.util.Map.of());
        List<DialogueClaim> claims = claimEngine.collectLocalClaims(
                participants, input(held, crosshair, List.of()));

        assertEquals(2, claims.size(), "alpha (crosshair) and beta (held) both claim");
        assertEquals(DialogueClaimSource.CONTEXT, claimFor(claims, "alpha").source());
        assertEquals(DialogueClaimSource.CONTEXT, claimFor(claims, "beta").source());

        DialogueArbitrationDecision decision = policy.decide(participants, claims, Optional.empty(), Optional.empty());
        assertTrue(decision.accepted());
        assertEquals("alpha", decision.owner().participantId());
        assertEquals(DialogueArbitrationPolicy.REASON_HARD_CLAIM, decision.reason());
    }

    @Test
    void aWakeWordClaimIsUtteranceAndAloneDecidesTheTurn() {
        DialogueParticipantDescriptor gamma = LabParticipants.gamma("Gamma", List.of("伽马"), DialogueHoldTier.TEN_SECONDS);
        List<DialogueClaim> claims = claimEngine.collectLocalClaims(
                List.of(gamma),
                input(DialogueInteractionHints.empty(), DialogueContextSnapshot.empty("local"),
                        List.of(new IrModuleMatch(LabProtocolAdapter.MODULE_ID, "伽马", "伽马", IrModuleMatch.KIND_WAKE))));

        assertEquals(1, claims.size());
        assertEquals(DialogueClaimSource.UTTERANCE, claims.get(0).source());
        assertTrue(policy.decide(List.of(gamma), claims, Optional.empty(), Optional.empty()).accepted());
    }

    @Test
    void aCrosshairFilterDoesNotClaimForAnotherEntityType() {
        DialogueParticipantDescriptor cowOnly = LabParticipants.alpha("Alpha", List.of("minecraft:cow"), DialogueHoldTier.TEN_SECONDS);
        DialogueContextSnapshot pig = new DialogueContextSnapshot(
                "local", "minecraft:overworld",
                List.of(new DialogueEntityRef("entity-2", "minecraft:pig", "Pig", 2.0D, true)),
                List.of(), java.util.Map.of());

        assertTrue(claimEngine.collectLocalClaims(
                List.of(cowOnly), input(DialogueInteractionHints.empty(), pig, List.of())).isEmpty());
    }

    @Test
    void theDescriptorsSatisfyTheArbitersRegistrationContract() {
        DialogueParticipantContractValidator validator = new DialogueParticipantContractValidator(this::registrations);

        assertTrue(validator.validate(LabParticipants.alpha("A", List.of(), DialogueHoldTier.TEN_SECONDS)).accepted());
        assertTrue(validator.validate(LabParticipants.beta("B", "minecraft:diamond", DialogueHoldTier.TEN_SECONDS)).accepted());
        assertTrue(validator.validate(LabParticipants.gamma("G", List.of("伽马"), DialogueHoldTier.TEN_SECONDS)).accepted());
    }

    private DialogueArbitrationInput input(DialogueInteractionHints hints, DialogueContextSnapshot snapshot,
                                           List<IrModuleMatch> moduleMatches) {
        long now = System.currentTimeMillis();
        DialogueArbitrationRequestPayload request = new DialogueArbitrationRequestPayload(
                "request", LabProtocolAdapter.MODULE_ID, "local", "1", 0L,
                "测试", "测试", List.of(), moduleMatches, now, now + 5_000L);
        return new DialogueArbitrationInput(request, hints, snapshot, java.util.Map.of(), List.of(), moduleMatches);
    }

    private static DialogueClaim claimFor(List<DialogueClaim> claims, String participantId) {
        return claims.stream()
                .filter(claim -> participantId.equals(claim.participantId()))
                .findFirst()
                .orElseThrow();
    }

    private static DialogueClaimConditionType conditionType(DialogueParticipantDescriptor descriptor) {
        return descriptor.claimProfile().rules().get(0).conditions().get(0).type();
    }

    private static List<String> conditionValues(DialogueParticipantDescriptor descriptor) {
        return descriptor.claimProfile().rules().get(0).conditions().get(0).values();
    }

    private List<ProtocolCapabilityRegistration> registrations(String capabilityId) {
        if (!LabProtocolAdapter.ALPHA_CAPABILITY.equals(capabilityId)
                && !LabProtocolAdapter.BETA_CAPABILITY.equals(capabilityId)
                && !LabProtocolAdapter.GAMMA_CAPABILITY.equals(capabilityId)) {
            return List.of();
        }
        return List.of(new ProtocolCapabilityRegistration(
                LabProtocolAdapter.MODULE_ID,
                new CapabilityDescriptor(
                        capabilityId,
                        PayloadType.of("DIALOGUE_DELIVERY"),
                        DialogueDeliveryPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.COMMAND),
                        Priority.LOW,
                        CompletionPolicy.MANUAL_COMPLETE)));
    }
}