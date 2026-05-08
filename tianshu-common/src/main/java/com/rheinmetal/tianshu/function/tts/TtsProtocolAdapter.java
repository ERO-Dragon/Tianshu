package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.StreamTextPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.util.EnumSet;

public final class TtsProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.tts";
    public static final String SOURCE_ID = "module.tts";

    public TtsProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withThreadPolicy(ThreadPolicy.IO_BLOCKING).withConcurrency(1, 64));
    }

    public ProtocolTaskHandle submitTtsTask(String envelopeId, ExecutionLane lane, Runnable task) {
        String laneName = lane == ExecutionLane.TTS_AUTOREGRESSIVE ? "autoregressive" : "fast";
        int queueCapacity = lane == ExecutionLane.TTS_AUTOREGRESSIVE ? 1 : 4;
        return submitTask(
                taskSpec(lane)
                        .envelopeId(envelopeId)
                        .concurrencyKey(MODULE_ID + ":synthesis:" + laneName)
                        .maxConcurrency(1)
                        .queueCapacity(queueCapacity)
                        .build(),
                task
        );
    }

    public void registerSpeakCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.TTS_SPEAK,
                PayloadType.TTS_TEXT,
                TtsSpeakPayload.class,
                BrokerType.EXCLUSIVE_INTERRUPT,
                EnumSet.of(PacketType.COMMAND, PacketType.STREAM_CHUNK, PacketType.STREAM_END),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void subscribeLlmStream(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.LLM_STREAM,
                PayloadType.LLM_TEXT_CHUNK,
                StreamTextPayload.class,
                BrokerType.EXCLUSIVE_INTERRUPT,
                EnumSet.of(PacketType.STREAM_CHUNK, PacketType.STREAM_END),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }
}
