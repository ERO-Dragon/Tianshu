package com.rheinmetal.tianshu.client.chatassistant;

import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.ProtocolCapabilities;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.adapter.AbstractProtocolAdapter;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantClientEventPayload;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantIncomingChatPayload;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantInterruptPayload;
import com.rheinmetal.tianshu.protocol.payload.ChatAssistantSendPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.EnumSet;

final class ChatAssistantClientProtocolAdapter extends AbstractProtocolAdapter {
    static final String MODULE_ID = "client.chat_assistant";
    static final String SOURCE_ID = "client.chat_assistant";

    ChatAssistantClientProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.mainThreadUi());
    }

    void registerClientEventCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.CHAT_ASSISTANT_CLIENT_EVENT,
                PayloadType.CUSTOM,
                ChatAssistantClientEventPayload.class,
                BrokerType.MAIN_THREAD,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                AdapterDefaults.mainThreadUi()
        );
    }

    void registerSendCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.CHAT_ASSISTANT_SEND,
                PayloadType.TEXT,
                ChatAssistantSendPayload.class,
                BrokerType.MAIN_THREAD,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                AdapterDefaults.mainThreadUi()
        );
    }

    TianshuEnvelope sendInterrupt(ChatAssistantInterruptPayload payload) {
        return commandCapability(ProtocolCapabilities.CHAT_ASSISTANT_INTERRUPT, PayloadType.CUSTOM, payload);
    }

    TianshuEnvelope sendIncomingChat(ChatAssistantIncomingChatPayload payload) {
        return submitToCapability(ProtocolCapabilities.CHAT_ASSISTANT_INCOMING_CHAT, PacketType.EVENT, PayloadType.CUSTOM, payload);
    }
}
