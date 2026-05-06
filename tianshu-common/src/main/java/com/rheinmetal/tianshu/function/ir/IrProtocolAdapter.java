package com.rheinmetal.tianshu.function.ir;

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
import com.rheinmetal.tianshu.protocol.payload.LlmCommandRepairPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmCommandRepairResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmIntentClassifyPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmIntentClassifyResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmPromptPayload;
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

    public void subscribeIntentClassifyResult(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.LLM_INTENT_CLASSIFY_RESULT,
                PayloadType.LLM_INTENT_CLASSIFY,
                LlmIntentClassifyResultPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void subscribeCommandRepairResult(EnvelopeHandler handler) {
        subscribeTopic(
                ProtocolTopics.LLM_COMMAND_REPAIR_RESULT,
                PayloadType.LLM_COMMAND_REPAIR,
                LlmCommandRepairResultPayload.class,
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

    public TianshuEnvelope publishResult(IrResultPayload payload) {
        return publishTopic(ProtocolTopics.IR_RESULT, PayloadType.IR_RESULT, payload);
    }

    public TianshuEnvelope requestLlmChat(TianshuEnvelope parent, LlmPromptPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.LLM_CHAT, PayloadType.LLM_PROMPT, payload);
    }

    public TianshuEnvelope requestIntentClassify(TianshuEnvelope parent, LlmIntentClassifyPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.LLM_INTENT_CLASSIFY, PayloadType.LLM_INTENT_CLASSIFY, payload);
    }

    public TianshuEnvelope requestCommandRepair(TianshuEnvelope parent, LlmCommandRepairPayload payload) {
        return requestCapability(parent, ProtocolCapabilities.LLM_COMMAND_REPAIR, PayloadType.LLM_COMMAND_REPAIR, payload);
    }
}
