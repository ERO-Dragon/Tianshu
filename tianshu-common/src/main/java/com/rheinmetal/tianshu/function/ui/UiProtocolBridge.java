package com.rheinmetal.tianshu.function.ui;

import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.event.UiAsrTextEvent;
import com.rheinmetal.tianshu.event.UiLlmEndEvent;
import com.rheinmetal.tianshu.event.UiLlmTextEvent;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.StreamTextPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;

public final class UiProtocolBridge extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.ui";
    public static final String SOURCE_ID = "module.ui";

    private final TianshuEventBus eventBus;

    public UiProtocolBridge(ProtocolRuntime runtime, TianshuEventBus eventBus) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
        this.eventBus = eventBus;
    }

    public void register() {
        subscribeAsrFinalText(this::handleAsrFinalText);
        subscribeLlmStream(this::handleLlmStream);
    }

    private void subscribeAsrFinalText(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.INPUT_ASR_FINAL_TEXT,
                PayloadType.ASR_TEXT,
                AsrTextPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    private void subscribeLlmStream(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.LLM_STREAM,
                PayloadType.LLM_TEXT_CHUNK,
                StreamTextPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.STREAM_CHUNK, PacketType.STREAM_END),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    private void handleAsrFinalText(TianshuEnvelope envelope, com.rheinmetal.tianshu.protocol.runtime.ProtocolContext context) {
        if (envelope.payload() instanceof AsrTextPayload payload) {
            eventBus.publishEvent(new UiAsrTextEvent(payload.text(), payload.turnId(), payload.sessionId()));
        }
    }

    private void handleLlmStream(TianshuEnvelope envelope, com.rheinmetal.tianshu.protocol.runtime.ProtocolContext context) {
        if (!(envelope.payload() instanceof StreamTextPayload payload)) {
            return;
        }
        long sessionId = eventBus.getActiveSessionId();
        if (envelope.header().packetType() == PacketType.STREAM_END || payload.last()) {
            eventBus.publishEvent(new UiLlmEndEvent(payload.index(), sessionId));
            return;
        }
        eventBus.publishEvent(new UiLlmTextEvent(payload.text(), payload.index(), sessionId));
    }
}
