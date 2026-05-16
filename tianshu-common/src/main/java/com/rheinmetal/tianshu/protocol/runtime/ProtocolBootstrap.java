package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;

public final class ProtocolBootstrap {
    private ProtocolBootstrap() {
    }

    public static ProtocolRuntime create(MainThreadExecutor mainThreadExecutor) {
        ProtocolRuntime runtime = new ProtocolRuntime(mainThreadExecutor);
        registerTopics(runtime);
        return runtime;
    }

    private static void registerTopics(ProtocolRuntime runtime) {
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.LLM_STREAM, PayloadType.LLM_TEXT_CHUNK, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.UI_STATUS, PayloadType.TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 60));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.DEBUG_TRACE, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 30));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.HOVER_STATE, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.CROSSHAIR_STATE, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.TICK_STATE, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ALERT_THREAT, PayloadType.ALERT, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ALERT_CLEARED, PayloadType.ALERT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.SYSTEM_DANGER_MODE_CHANGED, PayloadType.SYSTEM_STATE, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_ASR_FINAL_TEXT, PayloadType.ASR_TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.IR_RESULT, PayloadType.IR_RESULT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.IR_COMMAND_EXECUTED, PayloadType.IR_RESULT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.SYSTEM_RUNTIME_INTERRUPT, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.TTS_PLAYBACK, PayloadType.STATUS, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_KEY_ACTION, PayloadType.INPUT_ACTION, DeliveryPolicy.WAIT_IN_QUEUE, 60));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_CHAT_TEXT, PayloadType.TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_HOVER_ITEM, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ITEM_HOVER_STABLE, PayloadType.SNAPSHOT, DeliveryPolicy.LATEST_ONLY, 10));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ITEM_HOVER_CLEARED, PayloadType.NONE, DeliveryPolicy.LATEST_ONLY, 10));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.ITEM_ANALYSIS_READY, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.FEEDBACK_EMIT, PayloadType.FEEDBACK, DeliveryPolicy.WAIT_IN_QUEUE, 80));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.SETTINGS_EVENT, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.DIALOGUE_SESSION_EVENTS, PayloadType.DIALOGUE_SESSION_EVENT, DeliveryPolicy.WAIT_IN_QUEUE, 40));
    }
}
