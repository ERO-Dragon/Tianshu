package com.rheinmetal.tianshu.function.GeminiCard;

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
import com.rheinmetal.tianshu.protocol.payload.EmptyPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;

public final class GeminiCardProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "client.geminicard";
    public static final String SOURCE_ID = "client.geminicard";

    public GeminiCardProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.mainThreadUi());
    }

    public TianshuEnvelope publishHoverStable(GeminiCardHoverPayload payload) {
        return publishTopic(ProtocolTopics.ITEM_HOVER_STABLE, PayloadType.SNAPSHOT, payload, AdapterDefaults.highFrequencyFact());
    }

    public TianshuEnvelope publishHoverStable(TianshuEnvelope parent, GeminiCardHoverPayload payload) {
        return publishTopic(parent, ProtocolTopics.ITEM_HOVER_STABLE, PayloadType.SNAPSHOT, payload, AdapterDefaults.highFrequencyFact());
    }

    public TianshuEnvelope publishHoverCleared() {
        return publishTopic(ProtocolTopics.ITEM_HOVER_CLEARED, PayloadType.NONE, new EmptyPayload(), AdapterDefaults.highFrequencyFact());
    }

    public TianshuEnvelope publishHoverCleared(TianshuEnvelope parent) {
        return publishTopic(parent, ProtocolTopics.ITEM_HOVER_CLEARED, PayloadType.NONE, new EmptyPayload(), AdapterDefaults.highFrequencyFact());
    }

    public void subscribeAnalysisReady(EnvelopeHandler handler) {
        subscribeTopic(ProtocolTopics.ITEM_ANALYSIS_READY, PayloadType.CUSTOM, GeminiCardAnalysisResultPayload.class, BrokerType.MAIN_THREAD, EnumSet.of(PacketType.EVENT), Priority.LOW, CompletionPolicy.AUTO_COMPLETE_ON_RETURN, handler, AdapterDefaults.mainThreadUi());
    }

    public void registerShowCapability(EnvelopeHandler handler) {
        registerCapability(ProtocolCapabilities.GEMINI_CARD_SHOW, PayloadType.NONE, EmptyPayload.class, BrokerType.MAIN_THREAD, EnumSet.of(PacketType.COMMAND), Priority.LOW, CompletionPolicy.AUTO_COMPLETE_ON_RETURN, handler, AdapterDefaults.mainThreadUi());
    }
}
