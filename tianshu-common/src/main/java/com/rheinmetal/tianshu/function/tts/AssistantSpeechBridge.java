package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequestSource;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.StreamTextPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;

public final class AssistantSpeechBridge extends AbstractProtocolAdapter implements TianshuManagedModule {
    public static final String MODULE_ID = "module.assistant_speech_bridge";
    public static final String SOURCE_ID = "module.assistant_speech_bridge";

    public AssistantSpeechBridge(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        subscribeLlmStream(this::handleLlmStream);
    }

    private void subscribeLlmStream(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.LLM_STREAM,
                PayloadType.LLM_TEXT_CHUNK,
                StreamTextPayload.class,
                BrokerType.EXCLUSIVE_INTERRUPT,
                EnumSet.of(PacketType.STREAM_CHUNK, PacketType.STREAM_END),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    private void handleLlmStream(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof StreamTextPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "LLM stream payload is invalid", null);
            return;
        }
        TtsSpeakPayload ttsPayload = new TtsSpeakPayload(
                payload.text(),
                payload.index(),
                Math.abs(envelope.traceId().hashCode()),
                false,
                TtsRequestSource.ASSISTANT.name().toLowerCase()
        );
        submitToCapability(envelope, ProtocolCapabilities.TTS_SPEAK, envelope.header().packetType(), PayloadType.TTS_TEXT, ttsPayload);
        context.complete(envelope.envelopeId());
    }
}
