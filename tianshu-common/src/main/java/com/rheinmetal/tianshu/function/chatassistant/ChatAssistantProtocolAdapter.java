package com.rheinmetal.tianshu.function.chatassistant;

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
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.payload.VoiceTriggerPayload;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.time.Duration;
import java.util.EnumSet;

public final class ChatAssistantProtocolAdapter extends AbstractProtocolAdapter {
    public static final String MODULE_ID = "module.chat_assistant";
    public static final String SOURCE_ID = "module.chat_assistant";

    public ChatAssistantProtocolAdapter(ProtocolRuntime runtime) {
        super(MODULE_ID, SOURCE_ID, runtime, AdapterDefaults.standard().withConcurrency(1, 32));
    }

    public void registerVoiceWords() {
        ChatAssistantVoiceWords.Words words = ChatAssistantVoiceWords.load();
        registerVoiceTrigger(words.hotwords(), words.extraWords());
    }

    public void registerVoiceTriggerCapability(EnvelopeHandler handler) {
        registerCapability(
                MODULE_ID,
                PayloadType.VOICE_TRIGGER,
                VoiceTriggerPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.COMMAND),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void registerInterruptCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.CHAT_ASSISTANT_INTERRUPT,
                PayloadType.CUSTOM,
                ChatAssistantInterruptPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.COMMAND),
                Priority.NORMAL,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public void registerIncomingChatCapability(EnvelopeHandler handler) {
        registerCapability(
                ProtocolCapabilities.CHAT_ASSISTANT_INCOMING_CHAT,
                PayloadType.CUSTOM,
                ChatAssistantIncomingChatPayload.class,
                BrokerType.STATELESS_FAST_PATH,
                EnumSet.of(PacketType.EVENT),
                Priority.LOW,
                CompletionPolicy.AUTO_COMPLETE_ON_RETURN,
                handler,
                defaults()
        );
    }

    public TianshuEnvelope sendClientEvent(ChatAssistantClientEventPayload payload) {
        return commandCapability(ProtocolCapabilities.CHAT_ASSISTANT_CLIENT_EVENT, PayloadType.CUSTOM, payload, AdapterDefaults.mainThreadUi());
    }

    public TianshuEnvelope sendChatMessage(ChatAssistantSendPayload payload) {
        return commandCapability(ProtocolCapabilities.CHAT_ASSISTANT_SEND, PayloadType.TEXT, payload, AdapterDefaults.mainThreadUi());
    }

    public TianshuEnvelope speak(String text) {
        return commandCapability(ProtocolCapabilities.TTS_SPEAK, PayloadType.TTS_TEXT, new TtsSpeakPayload(text, 0, 0L, false, ""));
    }

    public TianshuEnvelope speakInterrupting(String text) {
        return commandCapability(ProtocolCapabilities.TTS_SPEAK, PayloadType.TTS_TEXT, new TtsSpeakPayload(text, 0, 0L, true, ""));
    }

    public ProtocolTaskHandle scheduleTimeout(Runnable task, long delayMillis) {
        return runtime().executors().schedule(
                taskSpec(ExecutionLane.SCHEDULED)
                        .concurrencyKey(MODULE_ID + ":timeout")
                        .maxConcurrency(1)
                        .queueCapacity(4)
                        .build(),
                task,
                Duration.ofMillis(Math.max(0L, delayMillis))
        );
    }
}
