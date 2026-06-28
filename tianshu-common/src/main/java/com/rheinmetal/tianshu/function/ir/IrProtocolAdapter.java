package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ia.payload.DialogueArbitrationRequestPayload;
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
import com.rheinmetal.tianshu.protocol.payload.AsrTextPayload;
import com.rheinmetal.tianshu.protocol.payload.IrParsePayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;

public final class IrProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.ir";
    public static final String SOURCE_ID = "module.ir";

    public IrProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard());
    }

    public void subscribeAsrFinalText(EnvelopeHandler handler) {
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

    public void registerParseCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.IR_PARSE,
                PayloadType.IR_PARSE,
                IrParsePayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.REQUEST, PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope buildPresenceContextQuery(TianshuEnvelope parent, PresenceContextQueryPayload payload) {
        return buildRequestCapability(parent, ProtocolCapabilities.PRESENCE_QUERY_CONTEXT, PayloadType.PRESENCE_CONTEXT_QUERY, payload);
    }

    public TianshuEnvelope submitPresenceContextQuery(TianshuEnvelope envelope) {
        return submitPrepared(envelope);
    }

    public int presenceContextProviderCount() {
        return runtime().capabilities().findCapability(ProtocolCapabilities.PRESENCE_QUERY_CONTEXT).size();
    }

    public void registerPresenceContextSnapshotResponse(String requestEnvelopeId, EnvelopeHandler handler) {
        registerResponseHandler(
                requestEnvelopeId,
                PayloadType.PRESENCE_CONTEXT_SNAPSHOT,
                PresenceContextSnapshotPayload.class,
                BrokerType.BOUNDED_QUEUE,
                EnumSet.of(PacketType.RESPONSE),
                Priority.LOW,
                CompletionPolicy.MANUAL_COMPLETE,
                handler,
                defaults()
        );
    }

    public void unregisterPresenceContextResponses(String requestEnvelopeId) {
        unregisterResponseHandlers(requestEnvelopeId);
    }

    public TianshuEnvelope publishResult(TianshuEnvelope parent, IrResultPayload payload) {
        return publishTopic(parent, ProtocolTopics.IR_RESULT, PayloadType.IR_RESULT, payload);
    }

    public TianshuEnvelope commandDialogueArbitration(TianshuEnvelope parent, DialogueArbitrationRequestPayload payload) {
        return commandCapability(parent, ProtocolCapabilities.DIALOGUE_ARBITRATE, PayloadType.DIALOGUE_ARBITRATION_REQUEST, payload);
    }
}
