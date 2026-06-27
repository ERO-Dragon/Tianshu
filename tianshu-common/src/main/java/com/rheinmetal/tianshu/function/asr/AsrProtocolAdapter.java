package com.rheinmetal.tianshu.function.asr;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.RuntimeInterruptPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.EnumSet;

public final class AsrProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.asr";
    public static final String SOURCE_ID = "module.asr";

    public AsrProtocolAdapter(ProtocolRuntime runtime) {
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
        return publishTopic(ProtocolTopics.INPUT_ASR_FINAL_TEXT, PayloadType.ASR_TEXT, payload);
    }

    public TianshuEnvelope publishSpeechActivity(AsrSpeechActivityPayload payload) {
        return publishTopic(ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY, PayloadType.ASR_SPEECH_ACTIVITY, payload);
    }

    public TianshuEnvelope publishModuleStatus(ModuleStatus status) {
        if (status == null) {
            return null;
        }
        return publishTopic(ProtocolTopics.MODULE_STATUS, PayloadType.MODULE_STATUS, new ModuleStatusPayload(status));
    }

    public ProtocolTaskHandle submitRecognitionTask(String taskName, Runnable task) {
        return submitTask(
                taskSpec(ExecutionLane.ASR_STREAM)
                        .concurrencyKey(MODULE_ID + ":recognition")
                        .maxConcurrency(1)
                        .queueCapacity(2)
                        .build(),
                task
        );
    }
}


