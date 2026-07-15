package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimCondition;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimRule;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueAttentionDecay;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueVoiceTriggerGroup;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationResultPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationRequestPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueLlmUsageAuthorizationResultPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueOwnerPreviewPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceManager;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    void voiceTriggersRegisteredBeforePrepareAreSynchronizedWhenRuntimeContextArrives() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(java.nio.file.Path.of("build/test-voice-resources-before-prepare"));
        VoiceResourceManager voiceResources = new VoiceResourceManager(new TestLlmSupport.FakeGameEnvironment(), config);
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources.voiceTriggers());
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
                DialogueClaimProfile.rules(DialogueClaimRule.anyStrong(
                        "maid.wake",
                        DialogueAttentionDecay.SLOW,
                        DialogueClaimCondition.wakeWord("酒狐")
                ))
        ));
        assertTrue(voiceResources.voiceTriggers().asrHotwords().isEmpty());

        ia.prepare(new ModuleRuntimeContext(runtime, services, voiceResources, runtimeState));

        assertEquals(List.of("酒狐"), voiceResources.voiceTriggers().asrHotwords());
    }

    @Test
    void voiceTriggersFollowRuntimeParticipantUpdatesAndUnregisters() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(java.nio.file.Path.of("build/test-voice-resources-runtime-update"));
        VoiceResourceManager voiceResources = new VoiceResourceManager(new TestLlmSupport.FakeGameEnvironment(), config);
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources.voiceTriggers());
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
                DialogueClaimProfile.rules(DialogueClaimRule.anyStrong(
                        "maid.wake",
                        DialogueAttentionDecay.SLOW,
                        DialogueClaimCondition.wakeWord("酒狐")
                ))
        ));
        assertEquals(List.of("酒狐"), voiceResources.voiceTriggers().asrHotwords());

        service.registerParticipant(participant(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.anyStrong(
                        "maid.wake.updated",
                        DialogueAttentionDecay.SLOW,
                        DialogueClaimCondition.wakeWord("女仆")
                ))
        ));
        assertEquals(List.of("女仆"), voiceResources.voiceTriggers().asrHotwords());

        service.unregisterModule("module.maid");

        assertTrue(voiceResources.voiceTriggers().asrHotwords().isEmpty());
    }

    @Test
    void participantVoiceTriggerGroupKeepsExtraWordsOutOfIaClaims() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(java.nio.file.Path.of("build/test-voice-trigger-group"));
        VoiceResourceManager voiceResources = new VoiceResourceManager(new TestLlmSupport.FakeGameEnvironment(), config);
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources.voiceTriggers());
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

        service.registerParticipant(participantWithVoiceTriggers(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.anyStrong(
                        "maid.wake",
                        DialogueAttentionDecay.SLOW,
                        DialogueClaimCondition.wakeWord("maid")
                )),
                DialogueVoiceTriggerGroup.of(List.of("maid"), List.of("farm"))
        ));
        service.registerParticipant(participant("ax", "module.ax", "AX.DIALOGUE_INPUT", 0, DialogueClaimProfile.DEFAULT_OWNER));

        assertEquals(List.of("maid", "farm"), voiceResources.voiceTriggers().asrHotwords());

        runtime.submit(EnvelopeBuilder.commandToCapability(
                "module.ir",
                ProtocolCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.DIALOGUE_ARBITRATION_REQUEST,
                request("req-extra", "player", "turn-extra", 910L, "farm please", List.of(), List.of())
        ).build());

        axDelivery.awaitPayload(DialogueDeliveryPayload.class);
        assertTrue(maidDelivery.envelopes().isEmpty());
    }

    @Test
    void participantRegistrationPublishesWakeWordsToSharedVoiceResources() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(java.nio.file.Path.of("build/test-voice-resources"));
        VoiceResourceManager voiceResources = new VoiceResourceManager(new TestLlmSupport.FakeGameEnvironment(), config);
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources.voiceTriggers());
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
                DialogueClaimProfile.rules(DialogueClaimRule.anyStrong(
                        "maid.wake",
                        DialogueAttentionDecay.SLOW,
                        DialogueClaimCondition.wakeWord("酒狐")
                ))
        ));

        await(() -> !voiceResources.voiceTriggers().asrHotwords().isEmpty());
        assertEquals(List.of("酒狐"), voiceResources.voiceTriggers().asrHotwords());
    }

    @Test
    void hardClaimUsesFrozenSpeechStartContextAndDeliversOnlyToOwner() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        MutablePresenceContextHandler presence = new MutablePresenceContextHandler();
        registerPresenceContextHandler(presence);
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, new ModuleServiceRegistry()));
        RecordingHandler maidDelivery = new RecordingHandler();
        RecordingHandler axDelivery = new RecordingHandler();
        registerDelivery("module.maid", "MAID.DIALOGUE_INPUT", maidDelivery);
        registerDelivery("module.ax", "AX.DIALOGUE_INPUT", axDelivery);
        RecordingHandler previewEvents = new RecordingHandler();
        subscribeOwnerPreview(previewEvents);
        subscribeSessionEvents(new RecordingHandler());
        registerParticipant(participant(
                "maid",
                "module.maid",
                "MAID.DIALOGUE_INPUT",
                10,
                DialogueClaimProfile.rules(DialogueClaimRule.allStrong(
                        "maid.frozen_crosshair",
                        DialogueAttentionDecay.SLOW,
                        DialogueClaimCondition.wakeWord("酒狐"),
                        DialogueClaimCondition.crosshairEntity("touhou_little_maid:maid")
                ))
        ));
        registerParticipant(participant("ax", "module.ax", "AX.DIALOGUE_INPUT", 0, DialogueClaimProfile.DEFAULT_OWNER));
        presence.payload(interactionContextPayload(
                "",
                "minecraft:overworld",
                "",
                "",
                "maid-uuid",
                "touhou_little_maid:maid",
                "酒狐",
                "3.0"
        ));

        runtime.submit(EnvelopeBuilder.eventTopic(
                "module.asr",
                ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY,
                PayloadType.ASR_SPEECH_ACTIVITY,
                AsrSpeechActivityPayload.speaking(900L)
        ).build());
        await(() -> presence.requestCount() >= 1);
        presence.payload(PresenceContextSnapshotPayload.success("empty", List.of()));
        runtime.submit(EnvelopeBuilder.commandToCapability(
                "module.ir",
                ProtocolCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.DIALOGUE_ARBITRATION_REQUEST,
                request("req-1", "player", "turn-1", 900L, "酒狐帮我种地", List.of("酒狐"), List.of())
        ).build());

        DialogueDeliveryPayload delivery = maidDelivery.awaitPayload(DialogueDeliveryPayload.class);
        DialogueOwnerPreviewPayload preview = previewEvents.awaitPayload(DialogueOwnerPreviewPayload.class);
        assertEquals("酒狐帮我种地", delivery.repairedText());
        assertEquals(List.of("酒狐"), delivery.matchedWakeWords());
        assertEquals(1, delivery.matchedEntityRefs().size());
        assertEquals("maid-uuid", delivery.matchedEntityRefs().get(0).entityId());
        assertEquals("player", delivery.contextSnapshot().playerId());
        assertEquals("module.maid", preview.moduleId());
        assertTrue(axDelivery.envelopes().isEmpty());
        assertTrue(runtime.deadLetters().snapshot(16).isEmpty());
    }

    @Test
    void defaultOwnerReceivesDeliveryWhenNoHardClaimAndRequestGetsResultResponse() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, new ModuleServiceRegistry()));
        RecordingHandler axDelivery = new RecordingHandler();
        registerDelivery("module.ax", "AX.DIALOGUE_INPUT", axDelivery);
        subscribeSessionEvents(new RecordingHandler());
        registerParticipant(participant("ax", "module.ax", "AX.DIALOGUE_INPUT", 0, DialogueClaimProfile.DEFAULT_OWNER));
        DialogueArbitrationRequestPayload payload = request("req-2", "player", "turn-2", 901L, "帮我记一下", List.of(), List.of());
        TianshuEnvelope requestEnvelope = EnvelopeBuilder.requestCapability(
                "module.ir",
                ProtocolCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.DIALOGUE_ARBITRATION_REQUEST,
                payload
        ).build();
        RecordingHandler resultHandler = new RecordingHandler();
        registerResponseHandler(requestEnvelope, PayloadType.DIALOGUE_ARBITRATION_RESULT, DialogueArbitrationResultPayload.class, resultHandler);

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
    void readyArbitrationWithoutParticipantsRejectsSilentlyWithoutPresenceLookup() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(java.nio.file.Path.of("build/test-ia-no-participant"));
        VoiceResourceManager voiceResources = new VoiceResourceManager(new TestLlmSupport.FakeGameEnvironment(), config);
        runtime = ProtocolBootstrap.create(Runnable::run, voiceResources.voiceTriggers());
        MutablePresenceContextHandler presence = new MutablePresenceContextHandler();
        registerPresenceContextHandler(presence);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        ModuleRuntimeState runtimeState = new ModuleRuntimeState();
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, services));
        ia.prepare(new ModuleRuntimeContext(runtime, services, voiceResources, runtimeState));
        subscribeSessionEvents(new RecordingHandler());
        subscribeOwnerPreview(new RecordingHandler());
        DialogueArbitrationRequestPayload payload = request("req-empty", "player", "turn-empty", 903L, "无人处理", List.of(), List.of());
        TianshuEnvelope requestEnvelope = EnvelopeBuilder.requestCapability(
                "module.ir",
                ProtocolCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.DIALOGUE_ARBITRATION_REQUEST,
                payload
        ).build();
        RecordingHandler resultHandler = new RecordingHandler();
        registerResponseHandler(requestEnvelope, PayloadType.DIALOGUE_ARBITRATION_RESULT, DialogueArbitrationResultPayload.class, resultHandler);

        runtime.submit(requestEnvelope);

        DialogueArbitrationResultPayload result = resultHandler.awaitPayload(DialogueArbitrationResultPayload.class);
        assertTrue(runtimeState.capabilities().isReady(IaRuntimeCapabilities.ARBITRATION));
        assertFalse(result.accepted());
        assertEquals("NO_PARTICIPANT", result.reason());
        assertEquals(0, presence.requestCount());
        assertTrue(runtime.deadLetters().snapshot(16).isEmpty());
    }

    @Test
    void llmAuthorizationAllowsCurrentOwnerAndRejectsNonOwnerAfterDelivery() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, new ModuleServiceRegistry()));
        RecordingHandler axDelivery = new RecordingHandler();
        registerDelivery("module.ax", "AX.DIALOGUE_INPUT", axDelivery);
        subscribeSessionEvents(new RecordingHandler());
        registerParticipant(participant("ax", "module.ax", "AX.DIALOGUE_INPUT", 0, DialogueClaimProfile.DEFAULT_OWNER));
        runtime.submit(EnvelopeBuilder.commandToCapability(
                "module.ir",
                ProtocolCapabilities.DIALOGUE_ARBITRATE,
                PayloadType.DIALOGUE_ARBITRATION_REQUEST,
                request("req-3", "player", "turn-3", 902L, "继续", List.of(), List.of())
        ).build());
        DialogueDeliveryPayload delivery = axDelivery.awaitPayload(DialogueDeliveryPayload.class);

        DialogueLlmUsageAuthorizationResultPayload ownerResult = authorize(
                delivery.sessionId(),
                "module.ax",
                "ax",
                "turn-3"
        );
        DialogueLlmUsageAuthorizationResultPayload strangerResult = authorize(
                delivery.sessionId(),
                "module.maid",
                "maid",
                "turn-3"
        );

        assertTrue(ownerResult.allowed());
        assertFalse(strangerResult.allowed());
        assertEquals("NOT_SESSION_OWNER", strangerResult.reasonCode());
    }

    private DialogueLlmUsageAuthorizationResultPayload authorize(String sessionId, String moduleId, String participantId, String turnId) {
        DialogueLlmUsageAuthorizationRequestPayload payload = new DialogueLlmUsageAuthorizationRequestPayload(sessionId, moduleId, participantId, turnId, System.currentTimeMillis());
        TianshuEnvelope requestEnvelope = EnvelopeBuilder.requestCapability(
                moduleId,
                ProtocolCapabilities.DIALOGUE_LLM_USAGE_AUTHORIZE,
                PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_REQUEST,
                payload
        ).build();
        RecordingHandler resultHandler = new RecordingHandler();
        registerResponseHandler(requestEnvelope, PayloadType.DIALOGUE_LLM_USAGE_AUTHORIZATION_RESULT, DialogueLlmUsageAuthorizationResultPayload.class, resultHandler);
        runtime.submit(requestEnvelope);
        return resultHandler.awaitPayload(DialogueLlmUsageAuthorizationResultPayload.class);
    }

    private void registerParticipant(DialogueParticipantDescriptor descriptor) {
        runtime.submit(EnvelopeBuilder.commandToCapability(
                descriptor.moduleId(),
                ProtocolCapabilities.DIALOGUE_PARTICIPANT_REGISTER,
                PayloadType.DIALOGUE_PARTICIPANT_REGISTER,
                new DialogueParticipantRegisterPayload(descriptor, System.currentTimeMillis())
        ).build());
        await(() -> runtime.deadLetters().snapshot(16).isEmpty());
    }

    private DialogueArbitrationRequestPayload request(String requestId, String playerId, String turnId, long sourceSessionId, String text, List<String> wakeWords, List<String> itemIds) {
        return new DialogueArbitrationRequestPayload(requestId, "module.ir", playerId, turnId, sourceSessionId, text, text, wakeWords, itemIds, System.currentTimeMillis(), System.currentTimeMillis() + 10_000L);
    }

    private DialogueParticipantDescriptor participant(String participantId, String moduleId, String routeCapability, int priority, DialogueClaimProfile profile) {
        return new DialogueParticipantDescriptor(
                participantId,
                moduleId,
                participantId,
                priority,
                List.of(),
                List.of(),
                List.of(),
                profile,
                routeCapability,
                DialogueTurnProcessingPolicy.DEFAULT
        );
    }

    private DialogueParticipantDescriptor participantWithVoiceTriggers(String participantId, String moduleId, String routeCapability, int priority, DialogueClaimProfile profile, DialogueVoiceTriggerGroup voiceTriggerGroup) {
        return new DialogueParticipantDescriptor(
                participantId,
                moduleId,
                participantId,
                priority,
                List.of(),
                List.of(),
                List.of(),
                profile,
                voiceTriggerGroup,
                routeCapability,
                DialogueTurnProcessingPolicy.DEFAULT
        );
    }

    private void registerDelivery(String moduleId, String capabilityId, RecordingHandler handler) {
        runtime.registerModule(
                descriptor(moduleId, new CapabilityDescriptor(
                        capabilityId,
                        PayloadType.DIALOGUE_DELIVERY,
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

    private void registerPresenceContextHandler(MutablePresenceContextHandler handler) {
        runtime.registerModule(
                descriptor("test.presence", new CapabilityDescriptor(
                        ProtocolCapabilities.PRESENCE_QUERY_CONTEXT,
                        PayloadType.PRESENCE_CONTEXT_QUERY,
                        PresenceContextQueryPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.REQUEST),
                        Priority.LOW,
                        CompletionPolicy.MANUAL_COMPLETE
                )),
                handler
        );
    }

    private PresenceContextSnapshotPayload interactionContextPayload(
            String playerId,
            String dimensionId,
            String heldItemId,
            String equippedItemIds,
            String targetId,
            String targetTypeId,
            String targetName,
            String targetDistance
    ) {
        return PresenceContextSnapshotPayload.success("test.presence.context", List.of(
                new PresenceContextSnapshotPayload.FactPayload(
                        PresenceContextFactIds.INTERACTION_CONTEXT,
                        "interaction",
                        84,
                        "presence",
                        "interaction",
                        List.of(),
                        System.currentTimeMillis(),
                        120_000L,
                        Map.of(
                                "playerId", playerId,
                                "dimensionId", dimensionId,
                                "heldItemId", heldItemId,
                                "equippedItemIds", equippedItemIds,
                                "crosshairTargetId", targetId,
                                "crosshairTargetTypeId", targetTypeId,
                                "crosshairTargetDisplayName", targetName,
                                "crosshairTargetDistance", targetDistance
                        )
                )
        ));
    }

    private void subscribeOwnerPreview(RecordingHandler handler) {
        runtime.subscribeTopic(
                descriptor("test.preview", List.of()),
                new TopicSubscriptionDescriptor(
                        ProtocolTopics.DIALOGUE_OWNER_PREVIEW,
                        PayloadType.DIALOGUE_OWNER_PREVIEW,
                        DialogueOwnerPreviewPayload.class,
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
                        ProtocolTopics.DIALOGUE_SESSION_EVENTS,
                        PayloadType.DIALOGUE_SESSION_EVENT,
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
        long deadline = System.currentTimeMillis() + 3_000L;
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

    private static final class MutablePresenceContextHandler implements EnvelopeHandler {
        private final AtomicReference<PresenceContextSnapshotPayload> payload = new AtomicReference<>(PresenceContextSnapshotPayload.success("empty", List.of()));
        private final AtomicInteger captureCount = new AtomicInteger();

        @Override
        public void handle(TianshuEnvelope envelope, ProtocolContext context) {
            captureCount.incrementAndGet();
            context.submit(EnvelopeBuilder.responseTo(
                    "test.presence",
                    envelope,
                    PayloadType.PRESENCE_CONTEXT_SNAPSHOT,
                    payload.get()
            ).build());
            context.complete(envelope.envelopeId());
        }

        private void payload(PresenceContextSnapshotPayload value) {
            payload.set(value == null ? PresenceContextSnapshotPayload.success("empty", List.of()) : value);
        }

        private int requestCount() {
            return captureCount.get();
        }
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
            long deadline = System.currentTimeMillis() + 3_000L;
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
    }
}
