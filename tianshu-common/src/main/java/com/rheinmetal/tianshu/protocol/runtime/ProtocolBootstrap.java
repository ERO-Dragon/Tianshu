package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;

public final class ProtocolBootstrap {
    private ProtocolBootstrap() {
    }

    public static ProtocolRuntime create(MainThreadExecutor mainThreadExecutor) {
        ProtocolRuntime runtime = new ProtocolRuntime(mainThreadExecutor);
        registerTopics(runtime);
        return runtime;
    }

    public static ProtocolRuntime create(MainThreadExecutor mainThreadExecutor, VoiceTriggerRegistry voiceTriggerRegistry) {
        ProtocolRuntime runtime = new ProtocolRuntime(mainThreadExecutor, voiceTriggerRegistry);
        registerTopics(runtime);
        return runtime;
    }

    private static void registerTopics(ProtocolRuntime runtime) {
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_ASR_SPEECH_ACTIVITY, PayloadType.ASR_SPEECH_ACTIVITY, DeliveryPolicy.LATEST_ONLY, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_ASR_FINAL_TEXT, PayloadType.ASR_TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.IR_RESULT, PayloadType.IR_RESULT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.SYSTEM_RUNTIME_INTERRUPT, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.LLM_STATUS, PayloadType.LLM_STATUS, DeliveryPolicy.LATEST_ONLY, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.TTS_PLAYBACK, PayloadType.TTS_PLAYBACK_STATUS, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.MODULE_STATUS, PayloadType.MODULE_STATUS, DeliveryPolicy.LATEST_ONLY, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.DIALOGUE_SESSION_EVENTS, PayloadType.DIALOGUE_SESSION_EVENT, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.DIALOGUE_OWNER_PREVIEW, PayloadType.DIALOGUE_OWNER_PREVIEW, DeliveryPolicy.LATEST_ONLY, 10));
    }
}

