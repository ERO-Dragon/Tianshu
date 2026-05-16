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
import com.rheinmetal.tianshu.protocol.payload.VoiceTriggerPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerDeliveryTarget;

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
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope publishResult(TianshuEnvelope parent, IrResultPayload payload) {
        return publishTopic(parent, ProtocolTopics.IR_RESULT, PayloadType.IR_RESULT, payload);
    }

    public TianshuEnvelope dispatchVoiceTrigger(TianshuEnvelope parent, VoiceTriggerDeliveryTarget target, VoiceTriggerPayload payload) {
        return commandCapability(parent, target.capabilityId(), PayloadType.VOICE_TRIGGER, payload);
    }

    public TianshuEnvelope requestDialogueArbitration(TianshuEnvelope parent, DialogueArbitrationRequestPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.DIALOGUE_ARBITRATE, PayloadType.DIALOGUE_ARBITRATION_REQUEST, payload);
    }
}
