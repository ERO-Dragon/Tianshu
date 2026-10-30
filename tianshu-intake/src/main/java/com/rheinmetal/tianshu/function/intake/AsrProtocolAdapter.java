package com.rheinmetal.tianshu.function.intake;

import com.rheinmetal.tianshu.function.intake.AsrProtocolAdapter;

import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;

import com.rheinmetal.tianshu.function.presence.protocol.PresencePayloadTypes;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.function.intake.protocol.IntakeTopics;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceActivityIds;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.ia.contract.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.function.intake.contract.AsrTextPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrResultPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.function.presence.payload.PresenceActivityType;
import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;

public final class AsrProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.asr";
    public static final String SOURCE_ID = AsrProtocolAdapter.MODULE_ID;

    /**
     * Registers the topics this module produces.
     *
     * <p>Topic ownership follows payload ownership: the producer declares its own route. The kernel used to
     * register these for the four original modules, so deleting a module still left its vocabulary behind.</p>
     */
    public void registerOwnedTopics(com.rheinmetal.tianshu.protocol.runtime.ModuleProtocolAccess protocol) {
        if (protocol == null) {
            return;
        }
        protocol.registerTopic(new com.rheinmetal.tianshu.protocol.registry.TopicDescriptor(
                IntakeTopics.INPUT_ASR_SPEECH_ACTIVITY, PayloadType.of("ASR_SPEECH_ACTIVITY"),
                DeliveryPolicy.LATEST_ONLY, 20));
        protocol.registerTopic(new com.rheinmetal.tianshu.protocol.registry.TopicDescriptor(
                IntakeTopics.INPUT_ASR_FINAL_TEXT, PayloadType.of("ASR_TEXT"),
                DeliveryPolicy.WAIT_IN_QUEUE, 20));
        protocol.registerTopic(new com.rheinmetal.tianshu.protocol.registry.TopicDescriptor(
                IntakeTopics.IR_RESULT, PayloadType.of("IR_RESULT"),
                DeliveryPolicy.WAIT_IN_QUEUE, 20));
    }

    /**
     * Long enough for the longest sentence a player may hold the key through, because the turn always ends
     * with an explicit {@code ENDED}: this TTL only matters when the client dies mid-turn.
     */
    private static final long LISTENING_TTL_MILLIS = 120_000L;
    private static final long RECOGNITION_TTL_MILLIS = 30_000L;
    private static final long MODEL_LOADING_TTL_MILLIS = 120_000L;

    public AsrProtocolAdapter(ModuleRuntimeAccess runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }

    public void subscribeRuntimeInterrupt(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.SYSTEM_RUNTIME_INTERRUPT,
                PayloadType.CUSTOM,
                RuntimeInterruptPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope publishFinalText(AsrTextPayload payload) {
        return publishTopic(IntakeTopics.INPUT_ASR_FINAL_TEXT, PayloadType.of("ASR_TEXT"), payload);
    }

    public TianshuEnvelope publishSpeechActivity(AsrSpeechActivityPayload payload) {
        return publishTopic(IntakeTopics.INPUT_ASR_SPEECH_ACTIVITY, PayloadType.of("ASR_SPEECH_ACTIVITY"), payload);
    }

    public TianshuEnvelope publishPresenceActivity(PresenceActivityPayload payload) {
        return publishTopic(PresenceTopics.ACTIVITY, PresencePayloadTypes.ACTIVITY, payload);
    }

    public TianshuEnvelope publishListeningActivity(boolean speaking, long sessionId) {
        String activityId = PresenceActivityIds.listening(sessionId);
        return publishPresenceActivity(speaking
                ? PresenceActivityPayload.started(activityId, PresenceActivityType.LISTENING, LISTENING_TTL_MILLIS)
                : PresenceActivityPayload.ended(activityId, PresenceActivityType.LISTENING));
    }

    public TianshuEnvelope publishRecognitionActivity(boolean running, long sessionId) {
        String activityId = "asr.recognition." + sessionId;
        return publishPresenceActivity(running
                ? PresenceActivityPayload.started(activityId, PresenceActivityType.PROCESSING_TASK, RECOGNITION_TTL_MILLIS)
                : PresenceActivityPayload.ended(activityId, PresenceActivityType.PROCESSING_TASK));
    }

    public TianshuEnvelope publishLoadingActivity(boolean loading) {
        return publishPresenceActivity(loading
                ? PresenceActivityPayload.started("asr.model.load", PresenceActivityType.LOADING, MODEL_LOADING_TTL_MILLIS)
                : PresenceActivityPayload.ended("asr.model.load", PresenceActivityType.LOADING));
    }

    public TianshuEnvelope publishModuleStatus(ModuleStatus status) {
        if (status == null) {
            return null;
        }
        return publishTopic(ProtocolTopics.MODULE_STATUS, PayloadType.MODULE_STATUS, new ModuleStatusPayload(status));
    }

    public ProtocolTaskHandle submitRecognitionTask(String taskName, Runnable task) {
        return submitTask(
                taskSpec(ExecutionLane.AUDIO_INPUT_STREAM)
                        .concurrencyKey(MODULE_ID + ":recognition")
                        .maxConcurrency(1)
                        .queueCapacity(2)
                        .build(),
                task
        );
    }

    // ---------------------------------------------------------------- speech-text repair (IR lane)

    /**
     * Publishes the repaired analysis of one speech turn.
     *
     * <p>The repair lane lives inside the speech module, but its output stays a public topic: a
     * consumer that only wants the corrected text does not have to know which module produced it.</p>
     */
    public TianshuEnvelope publishIrResult(TianshuEnvelope parent, IrResultPayload payload) {
        return publishTopic(parent, IntakeTopics.IR_RESULT, PayloadType.of("IR_RESULT"), payload);
    }
}


