package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.function.ia.protocol.IaCapabilities;

import com.rheinmetal.tianshu.function.intake.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.presence.PresenceProtocolAdapter;

import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;

import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTriggerGroup;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationResultPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.function.intake.protocol.IntakeTopics;
import com.rheinmetal.tianshu.function.ia.protocol.IaTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.function.ia.contract.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrResultPayload;
import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerSettings;
import com.rheinmetal.tianshu.function.presence.model.PresenceScreenKind;
import com.rheinmetal.tianshu.function.presence.payload.PresenceInteractionContextPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresencePartnerPayload;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IaModuleProtocolFlowTest {
    private ProtocolRuntime runtime;

    @AfterEach
    void closeRuntime() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void triggersRegisteredBeforePrepareAreSynchronizedWhenRuntimeContextArrives() {
        
        TriggerRegistry voiceResources = new TriggerRegistry();
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources);
        installPresence(runtime);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        ModuleRuntimeState runtimeState = new ModuleRuntimeState();
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, services));
        registerDelivery("module.maid", "MAID.DIALOGUE_INPUT", new RecordingHandler());

        services.require(IaModuleService.class).registerParticipant(participant(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "maid.wake",
                        DialogueClaimCondition.wakeWord("酒狐")
                ))
        ));
        assertTrue(voiceResources.registeredWords().isEmpty());

        ia.prepare(new ModuleRuntimeContext(runtime, services, voiceResources, runtimeState));

        assertEquals(List.of("酒狐"), voiceResources.registeredWords());
    }

    @Test
    void triggersFollowRuntimeParticipantUpdatesAndUnregisters() {
        TriggerRegistry voiceResources = new TriggerRegistry();
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources);
        installPresence(runtime);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        ModuleRuntimeState runtimeState = new ModuleRuntimeState();
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, services));
        ia.prepare(new ModuleRuntimeContext(runtime, services, voiceResources, runtimeState));
        registerDelivery("module.maid", "MAID.DIALOGUE_INPUT", new RecordingHandler());
        IaModuleService service = services.require(IaModuleService.class);

        service.registerParticipant(participant(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "maid.wake",
                        DialogueClaimCondition.wakeWord("酒狐")
                ))
        ));
        assertEquals(List.of("酒狐"), voiceResources.registeredWords());

        service.registerParticipant(participant(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "maid.wake.updated",
                        DialogueClaimCondition.wakeWord("女仆")
                ))
        ));
        assertEquals(List.of("女仆"), voiceResources.registeredWords());

        service.unregisterModule("module.maid");

        assertTrue(voiceResources.registeredWords().isEmpty());
    }

    @Test
    void aTriggerWordThatIsNotTheParticipantsWakeWordLeavesTheClaimToTheDefaultOwner() {
        TriggerRegistry voiceResources = new TriggerRegistry();
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources);
        installPresence(runtime);
        installAsrRoutes(runtime);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        ModuleRuntimeState runtimeState = new ModuleRuntimeState();
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, services));
        ia.prepare(new ModuleRuntimeContext(runtime, services, voiceResources, runtimeState));
        RecordingHandler maidDelivery = new RecordingHandler();
        RecordingHandler axDelivery = new RecordingHandler();
        registerDelivery("module.maid", "MAID.DIALOGUE_INPUT", maidDelivery);
        registerDelivery("module.ax", "AX.DIALOGUE_INPUT", axDelivery);
        subscribeSessionEvents(new RecordingHandler());
        IaModuleService service = services.require(IaModuleService.class);

        service.registerParticipant(participantWithTriggers(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "maid.wake",
                        DialogueClaimCondition.wakeWord("maid")
                )),
                DialogueTriggerGroup.of(List.of("maid"), List.of("farm"))
        ));
        service.registerParticipant(factoryDefaultOwnerParticipant("ax", "module.ax", "AX.DIALOGUE_INPUT"));

        assertEquals(List.of("maid", "farm"), voiceResources.registeredWords());

        runtime.submit(EnvelopeBuilder.eventTopic(
                "module.ir",
                IntakeTopics.IR_RESULT,
                PayloadType.of("IR_RESULT"),
                new IrResultPayload(
                        "farm please",
                        "farm please",
                        "farm please",
                        List.of(),
                        List.of(new IrModuleMatch("module.maid", "farm", "farm")),
                        18,
                        910L,
                        System.currentTimeMillis()
                )
        ).build());

        DialogueDeliveryPayload delivery = axDelivery.awaitPayload(DialogueDeliveryPayload.class);
        assertEquals(List.of(new IrModuleMatch("module.maid", "farm", "farm")), delivery.moduleMatches(),
                "the evidence reaches the delivery as it was reported");
        assertTrue(maidDelivery.envelopes().isEmpty(), "a trigger word that is not the wake word must not claim the turn");
    }

    @Test
    void participantRegistrationPublishesWakeWordsToSharedVoiceResources() {
        TriggerRegistry voiceResources = new TriggerRegistry();
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources);
        installPresence(runtime);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        ModuleRuntimeState runtimeState = new ModuleRuntimeState();
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, services));
        ia.prepare(new ModuleRuntimeContext(runtime, services, voiceResources, runtimeState));
        registerDelivery("module.maid", "MAID.DIALOGUE_INPUT", new RecordingHandler());

        registerParticipant(participant(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "maid.wake",
                        DialogueClaimCondition.wakeWord("酒狐")
                ))
        ));

        await(() -> !voiceResources.registeredWords().isEmpty());
        assertEquals(List.of("酒狐"), voiceResources.registeredWords());
    }

    @Test
    void hardClaimUsesFrozenSpeechStartContextAndDeliversOnlyToOwner() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        installPresence(runtime);
        installAsrRoutes(runtime);
        registerInteractionContextTopic();
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, new ModuleServiceRegistry()));
        RecordingHandler maidDelivery = new RecordingHandler();
        RecordingHandler axDelivery = new RecordingHandler();
        registerDelivery("module.maid", "MAID.DIALOGUE_INPUT", maidDelivery);
        registerDelivery("module.ax", "AX.DIALOGUE_INPUT", axDelivery);
        RecordingHandler previewEvents = new RecordingHandler();
        subscribePartner(previewEvents);
        subscribeSessionEvents(new RecordingHandler());
        registerParticipant(participant(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.all(
                        "maid.frozen_crosshair",
                        DialogueClaimCondition.wakeWord("酒狐"),
                        DialogueClaimCondition.crosshairEntity("touhou_little_maid:maid")
                ))
        ));
        registerParticipant(participant("ax", "module.ax", "AX.DIALOGUE_INPUT", 0, DialogueClaimProfile.DEFAULT_OWNER));
        // The digest is the only context a turn is arbitrated against now, so what the player was aiming at when the
        // speech started has to arrive as a push, not as an answer to a query.
        publishInteractionContext("local", "minecraft:overworld", "主世界", "",
                "maid-uuid", "touhou_little_maid:maid", "酒狐", 3.0D);

        runtime.submit(EnvelopeBuilder.eventTopic(
                "module.asr",
                IntakeTopics.INPUT_ASR_SPEECH_ACTIVITY,
                PayloadType.of("ASR_SPEECH_ACTIVITY"),
                AsrSpeechActivityPayload.speaking(900L)
        ).build());
        runtime.submit(EnvelopeBuilder.eventTopic(
                "module.ir",
                IntakeTopics.IR_RESULT,
                PayloadType.of("IR_RESULT"),
                new IrResultPayload(
                        "酒狐帮我种地",
                        "酒狐帮我种地",
                        "九狐帮我种地",
                        List.of(),
                        List.of(new IrModuleMatch("module.maid", "酒狐", "酒狐")),
                        1,
                        900L,
                        System.currentTimeMillis()
                )
        ).build());

        DialogueDeliveryPayload delivery = maidDelivery.awaitPayload(DialogueDeliveryPayload.class);
        PresencePartnerPayload partner = previewEvents.awaitPayload(PresencePartnerPayload.class, PresencePartnerPayload::hasLease);
        assertEquals("酒狐帮我种地", delivery.repairedText());
        assertEquals(List.of(new IrModuleMatch("module.maid", "酒狐", "酒狐")), delivery.moduleMatches());
        assertEquals(1, delivery.matchedEntityRefs().size());
        assertEquals("maid-uuid", delivery.matchedEntityRefs().get(0).entityId());
        assertEquals("酒狐", delivery.matchedEntityRefs().get(0).displayName());
        assertEquals("酒狐", delivery.displayNames().get("touhou_little_maid:maid"));
        assertEquals("主世界", delivery.displayNames().get("minecraft:overworld"));
        assertEquals(3.0D, delivery.interactionHints().targetDistance(), 0.001D);
        assertEquals("local", delivery.contextSnapshot().playerId());
        assertEquals("module.maid", partner.moduleId());
        assertTrue(partner.hasLease(), "winning a turn opens the participant's ten-second lease");
        assertEquals(10_000L, partner.holdDurationMillis());
        assertFalse(partner.defaultOwner());
        assertTrue(axDelivery.envelopes().isEmpty());
        assertTrue(runtime.deadLetters().snapshot(16).isEmpty());
    }

    @Test
    void defaultOwnerReceivesDeliveryWhenNoHardClaimAndRequestGetsResultResponse() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        installPresence(runtime);
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, new ModuleServiceRegistry()));
        RecordingHandler axDelivery = new RecordingHandler();
        registerDelivery("module.ax", "AX.DIALOGUE_INPUT", axDelivery);
        subscribeSessionEvents(new RecordingHandler());
        registerParticipant(factoryDefaultOwnerParticipant("ax", "module.ax", "AX.DIALOGUE_INPUT"));
        DialogueArbitrationRequestPayload payload = request("req-2", "player", "turn-2", 901L, "帮我记一下", List.of(), List.of());
        TianshuEnvelope requestEnvelope = EnvelopeBuilder.requestCapability(
                "module.ir",
                IaCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.of("DIALOGUE_ARBITRATION_REQUEST"),
                payload
        ).build();
        RecordingHandler resultHandler = new RecordingHandler();
        registerResponseHandler(requestEnvelope, PayloadType.of("DIALOGUE_ARBITRATION_RESULT"), DialogueArbitrationResultPayload.class, resultHandler);

        runtime.submit(requestEnvelope);

        DialogueDeliveryPayload delivery = axDelivery.awaitPayload(DialogueDeliveryPayload.class);
        DialogueArbitrationResultPayload result = resultHandler.awaitPayload(DialogueArbitrationResultPayload.class);
        assertEquals("帮我记一下", delivery.repairedText());
        assertTrue(result.accepted());
        assertEquals("module.ax", result.ownerModuleId());
        assertEquals("ax", result.ownerParticipantId());
        assertEquals("DEFAULT_OWNER", result.reason());
    }

    @Test
    void aTurnWithNoClaimIsRejectedWhenTheDefaultOwnerSettingIsOff() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        installPresence(runtime);
        IaModule ia = new IaModule(runtime, new IaDefaultOwnerSettings() {
            @Override
            public boolean isDisabled() {
                return true;
            }

            @Override
            public String participantId() {
                return "";
            }
        });
        ia.register(new ModuleRegistrationContext(runtime, new ModuleServiceRegistry()));
        RecordingHandler axDelivery = new RecordingHandler();
        registerDelivery("module.ax", "AX.DIALOGUE_INPUT", axDelivery);
        subscribeSessionEvents(new RecordingHandler());
        registerParticipant(factoryDefaultOwnerParticipant("ax", "module.ax", "AX.DIALOGUE_INPUT"));
        TianshuEnvelope requestEnvelope = EnvelopeBuilder.requestCapability(
                "module.ir",
                IaCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.of("DIALOGUE_ARBITRATION_REQUEST"),
                request("req-off", "player", "turn-off", 902L, "帮我记一下", List.of(), List.of())
        ).build();
        RecordingHandler resultHandler = new RecordingHandler();
        registerResponseHandler(requestEnvelope, PayloadType.of("DIALOGUE_ARBITRATION_RESULT"), DialogueArbitrationResultPayload.class, resultHandler);

        runtime.submit(requestEnvelope);

        DialogueArbitrationResultPayload result = resultHandler.awaitPayload(DialogueArbitrationResultPayload.class);
        assertFalse(result.accepted(), "the player turned the default owner off; nobody owns an unclaimed turn");
        assertEquals("NO_OWNER", result.reason());
        assertTrue(axDelivery.envelopes().isEmpty());
    }

    @Test
    void readyArbitrationWithoutParticipantsRejectsSilently() {
        TriggerRegistry voiceResources = new TriggerRegistry();
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources);
        installPresence(runtime);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        ModuleRuntimeState runtimeState = new ModuleRuntimeState();
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, services));
        ia.prepare(new ModuleRuntimeContext(runtime, services, voiceResources, runtimeState));
        subscribeSessionEvents(new RecordingHandler());
        subscribePartner(new RecordingHandler());
        DialogueArbitrationRequestPayload payload = request("req-empty", "player", "turn-empty", 903L, "无人处理", List.of(), List.of());
        TianshuEnvelope requestEnvelope = EnvelopeBuilder.requestCapability(
                "module.ir",
                IaCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.of("DIALOGUE_ARBITRATION_REQUEST"),
                payload
        ).build();
        RecordingHandler resultHandler = new RecordingHandler();
        registerResponseHandler(requestEnvelope, PayloadType.of("DIALOGUE_ARBITRATION_RESULT"), DialogueArbitrationResultPayload.class, resultHandler);

        runtime.submit(requestEnvelope);

        DialogueArbitrationResultPayload result = resultHandler.awaitPayload(DialogueArbitrationResultPayload.class);
        assertTrue(runtimeState.capabilities().isReady(IaRuntimeCapabilities.ARBITRATION));
        assertFalse(result.accepted());
        assertEquals("NO_PARTICIPANT", result.reason());
        assertTrue(runtime.deadLetters().snapshot(16).isEmpty());
    }



    private void registerParticipant(DialogueParticipantDescriptor descriptor) {
        TianshuEnvelope registration = EnvelopeBuilder.commandToCapability(
                descriptor.moduleId(),
                IaCapabilities.DIALOGUE_PARTICIPANT_REGISTER,
                PayloadType.of("DIALOGUE_PARTICIPANT_REGISTER"),
                new DialogueParticipantRegisterPayload(descriptor, System.currentTimeMillis())
        ).build();
        runtime.submit(registration);
        await(() -> runtime.lifecycle().statusOf(registration.envelopeId()) == EnvelopeStatus.COMPLETED);
    }

    private DialogueArbitrationRequestPayload request(String requestId, String playerId, String turnId, long sourceSessionId, String text, List<String> wakeWords, List<String> itemIds) {
        List<IrModuleMatch> moduleMatches = wakeWords.stream()
                .map(word -> new IrModuleMatch("module.test", word, word))
                .toList();
        List<IrObjectMatch> objectMatches = itemIds.stream()
                .map(id -> new IrObjectMatch(id, id, IrObjectMatch.KIND_ITEM, ""))
                .toList();
        return new DialogueArbitrationRequestPayload(requestId, "module.ir", playerId, turnId, sourceSessionId, text, text, objectMatches, moduleMatches, System.currentTimeMillis(), System.currentTimeMillis() + 10_000L);
    }

    private DialogueParticipantDescriptor participant(String participantId, String moduleId, String routeCapability, int priority, DialogueClaimProfile profile) {
        return new DialogueParticipantDescriptor(
                participantId,
                moduleId,
                participantId,
                priority,
                profile,
                DialogueTriggerGroup.EMPTY,
                routeCapability,
                DialogueTurnProcessingPolicy.DEFAULT,
                partnerIcon(participantId),
                DialogueHoldTier.TEN_SECONDS
        );
    }

    /** The participant a fresh install falls back to: it owns unclaimed turns unless the player says otherwise. */
    private DialogueParticipantDescriptor factoryDefaultOwnerParticipant(String participantId, String moduleId, String routeCapability) {
        return new DialogueParticipantDescriptor(
                participantId,
                moduleId,
                participantId,
                0,
                DialogueClaimProfile.DEFAULT_OWNER,
                DialogueTriggerGroup.EMPTY,
                routeCapability,
                DialogueTurnProcessingPolicy.DEFAULT,
                partnerIcon(participantId),
                DialogueHoldTier.TEN_SECONDS,
                true
        );
    }

    private DialogueParticipantDescriptor participantWithTriggers(String participantId, String moduleId, String routeCapability, int priority, DialogueClaimProfile profile, DialogueTriggerGroup triggerGroup) {
        return new DialogueParticipantDescriptor(
                participantId,
                moduleId,
                participantId,
                priority,
                profile,
                triggerGroup,
                routeCapability,
                DialogueTurnProcessingPolicy.DEFAULT,
                partnerIcon(participantId),
                DialogueHoldTier.TEN_SECONDS
        );
    }

    private static String partnerIcon(String participantId) {
        return "tianshu:textures/gui/partner/" + participantId + ".png";
    }

    private void registerDelivery(String moduleId, String capabilityId, RecordingHandler handler) {
        runtime.registerModule(
                descriptor(moduleId, new CapabilityDescriptor(
                        capabilityId,
                        PayloadType.of("DIALOGUE_DELIVERY"),
                        DialogueDeliveryPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.COMMAND),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                )),
                handler
        );
    }

    private void registerResponseHandler(TianshuEnvelope requestEnvelope, PayloadType payloadType, Class<?> payloadClass, RecordingHandler handler) {
        runtime.registerResponseHandler(
                requestEnvelope.envelopeId(),
                descriptor("test.response." + requestEnvelope.envelopeId(), List.of()),
                new CapabilityDescriptor(
                        "response." + requestEnvelope.envelopeId() + "." + payloadType.name(),
                        payloadType,
                        payloadClass.asSubclass(com.rheinmetal.tianshu.protocol.ITianshuPayload.class),
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.RESPONSE),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                handler
        );
    }

    private void registerInteractionContextTopic() {
        runtime.registerTopic(new TopicDescriptor(
                PresenceTopics.INTERACTION_CONTEXT,
                PresencePayloadTypes.INTERACTION_CONTEXT,
                DeliveryPolicy.LATEST_ONLY,
                20
        ));
    }

    private void publishInteractionContext(
            String playerId,
            String dimensionId,
            String dimensionDisplayName,
            String heldItemId,
            String targetId,
            String targetTypeId,
            String targetName,
            double targetDistance
    ) {
        runtime.submit(EnvelopeBuilder.eventTopic(
                "module.presence",
                PresenceTopics.INTERACTION_CONTEXT,
                PresencePayloadTypes.INTERACTION_CONTEXT,
                new PresenceInteractionContextPayload(
                        playerId,
                        dimensionId,
                        dimensionDisplayName,
                        PresenceScreenKind.NONE,
                        heldItemId,
                        "",
                        List.of(),
                        targetId,
                        targetTypeId,
                        targetName,
                        targetDistance,
                        !targetId.isBlank() || !targetTypeId.isBlank(),
                        false,
                        false,
                        System.currentTimeMillis()
                )
        ).build());
    }

    @Test
    void aPushedInteractionChangeMakesTheAimedParticipantThePreWinner() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        installPresence(runtime);
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, new ModuleServiceRegistry()));
        registerInteractionContextTopic();
        RecordingHandler partnerEvents = new RecordingHandler();
        subscribePartner(partnerEvents);
        registerDelivery("module.maid", "MAID.DIALOGUE_INPUT", new RecordingHandler());
        registerParticipant(participant(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "maid.crosshair",
                        DialogueClaimCondition.crosshairEntity("touhou_little_maid:maid")
                ))
        ));
        registerParticipant(participant("ax", "module.ax", "AX.DIALOGUE_INPUT", 0, DialogueClaimProfile.DEFAULT_OWNER));

        publishInteractionContext("player", "minecraft:overworld", "", "", "maid-uuid",
                "touhou_little_maid:maid", "", 0.0D);

        PresencePartnerPayload partner = partnerEvents.awaitPayload(PresencePartnerPayload.class);
        assertEquals("module.maid", partner.moduleId());
        assertFalse(partner.hasLease(), "a pre-winner has no lease yet");
        assertFalse(partner.defaultOwner());
        assertTrue(partner.icon().contains("maid"), "the marker carries the participant's registered icon");
    }

    @Test
    void aTurnWithoutAFrozenSpeechContextArbitratesAgainstTheNewestDigest() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        installPresence(runtime);
        installAsrRoutes(runtime);
        registerInteractionContextTopic();
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, new ModuleServiceRegistry()));
        RecordingHandler maidDelivery = new RecordingHandler();
        registerDelivery("module.maid", "MAID.DIALOGUE_INPUT", maidDelivery);
        registerParticipant(participant(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.any(
                        "maid.crosshair",
                        DialogueClaimCondition.crosshairEntity("touhou_little_maid:maid")
                ))
        ));
        registerParticipant(participant("ax", "module.ax", "AX.DIALOGUE_INPUT", 0, DialogueClaimProfile.DEFAULT_OWNER));
        publishInteractionContext("player", "minecraft:overworld", "主世界", "",
                "maid-uuid", "touhou_little_maid:maid", "酒狐", 3.0D);

        runtime.submit(EnvelopeBuilder.commandToCapability(
                "module.ir",
                IaCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.of("DIALOGUE_ARBITRATION_REQUEST"),
                request("req-digest", "player", "turn-digest", 0L, "看看这个", List.of(), List.of())
        ).build());

        DialogueDeliveryPayload delivery = maidDelivery.awaitPayload(DialogueDeliveryPayload.class);
        assertEquals("看看这个", delivery.repairedText());
        assertEquals("maid-uuid", delivery.matchedEntityRefs().get(0).entityId());
        assertEquals("酒狐", delivery.matchedEntityRefs().get(0).displayName());
    }

    /** Presence owns its topics now; the kernel no longer registers them. */
    private static void installPresence(ProtocolRuntime runtime) {
        new PresenceProtocolAdapter(runtime).registerOwnedTopics(runtime);
    }

    /** The ASR module owns the ASR/IR routes; a flow test that publishes them declares them the same way. */
    private static void installAsrRoutes(ProtocolRuntime runtime) {
        new AsrProtocolAdapter(runtime).registerOwnedTopics(runtime);
    }
    private void subscribePartner(RecordingHandler handler) {        runtime.subscribeTopic(
                descriptor("test.partner", List.of()),
                new TopicSubscriptionDescriptor(
                        PresenceTopics.PARTNER,
                        PresencePayloadTypes.PARTNER,
                        PresencePartnerPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.EVENT),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                handler
        );
    }

    private void subscribeSessionEvents(RecordingHandler handler) {
        runtime.subscribeTopic(
                descriptor("test.session_events." + System.nanoTime(), List.of()),
                new TopicSubscriptionDescriptor(
                        IaTopics.DIALOGUE_SESSION_EVENTS,
                        PayloadType.of("DIALOGUE_SESSION_EVENT"),
                        DialogueSessionEventPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.EVENT),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                handler
        );
    }

    private ModuleDescriptor descriptor(String moduleId, CapabilityDescriptor capability) {
        return descriptor(moduleId, List.of(capability));
    }

    private ModuleDescriptor descriptor(String moduleId, List<CapabilityDescriptor> capabilities) {
        return new ModuleDescriptor(
                moduleId,
                capabilities,
                com.rheinmetal.tianshu.protocol.ThreadPolicy.ANY,
                com.rheinmetal.tianshu.protocol.CancellationScope.SELF_ONLY,
                com.rheinmetal.tianshu.protocol.FailurePolicy.REPORT_ONLY,
                com.rheinmetal.tianshu.protocol.DeliveryPolicy.WAIT_IN_QUEUE,
                false,
                false,
                1,
                32
        );
    }

    private static void await(BooleanCondition condition) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.satisfied()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out waiting for condition");
    }

    private interface BooleanCondition {
        boolean satisfied();
    }

    private static final class RecordingHandler implements EnvelopeHandler {
        private final List<TianshuEnvelope> envelopes = new CopyOnWriteArrayList<>();

        @Override
        public void handle(TianshuEnvelope envelope, ProtocolContext context) {
            envelopes.add(envelope);
            context.complete(envelope.envelopeId());
        }

        private List<TianshuEnvelope> envelopes() {
            return new ArrayList<>(envelopes);
        }

        private <T> T awaitPayload(Class<T> type) {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline) {
                for (TianshuEnvelope envelope : envelopes) {
                    if (type.isInstance(envelope.payload())) {
                        return type.cast(envelope.payload());
                    }
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new AssertionError("Timed out waiting for payload " + type.getSimpleName() + ", received " + envelopes.size() + " envelope(s)");
        }

        /** Waits for the first payload of {@code type} that matches, so a marker's later state is observable. */
        private <T> T awaitPayload(Class<T> type, java.util.function.Predicate<T> condition) {
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline) {
                for (TianshuEnvelope envelope : envelopes) {
                    if (type.isInstance(envelope.payload())) {
                        T payload = type.cast(envelope.payload());
                        if (condition.test(payload)) {
                            return payload;
                        }
                    }
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new AssertionError("Timed out waiting for matching " + type.getSimpleName() + ", received " + envelopes.size() + " envelope(s)");
        }
    }
}
