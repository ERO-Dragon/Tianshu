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
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.UI_STATUS, PayloadType.TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 60));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.DEBUG_TRACE, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 30));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_ASR_FINAL_TEXT, PayloadType.ASR_TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_KEY_ACTION, PayloadType.INPUT_ACTION, DeliveryPolicy.WAIT_IN_QUEUE, 60));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.INPUT_CHAT_TEXT, PayloadType.TEXT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.IR_RESULT, PayloadType.IR_RESULT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.IR_COMMAND_EXECUTED, PayloadType.IR_RESULT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.SYSTEM_RUNTIME_INTERRUPT, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.CORE_READY, PayloadType.CORE_LIFECYCLE_EVENT, DeliveryPolicy.LATEST_ONLY, 10));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.CORE_SHUTDOWN, PayloadType.CORE_LIFECYCLE_EVENT, DeliveryPolicy.LATEST_ONLY, 10));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.CORE_CAPABILITY_CHANGED, PayloadType.CORE_CAPABILITY_PROBE, DeliveryPolicy.LATEST_ONLY, 10));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.CORE_LIFECYCLE, PayloadType.CORE_LIFECYCLE_EVENT, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.RESOURCE_RELOADED, PayloadType.RESOURCE_RELOAD_EVENT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.LANGUAGE_RELOADED, PayloadType.RESOURCE_RELOAD_EVENT, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.RESOURCE_EVENTS, PayloadType.RESOURCE_RELOAD_EVENT, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.STATE_SUMMARY_CHANGED, PayloadType.STATE_SUMMARY, DeliveryPolicy.LATEST_ONLY, 80));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.GUI_CONTRIBUTION_CHANGED, PayloadType.GUI_CONTRIBUTION, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.TTS_PLAYBACK, PayloadType.STATUS, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.FEEDBACK_EMIT, PayloadType.FEEDBACK, DeliveryPolicy.WAIT_IN_QUEUE, 80));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.SETTINGS_EVENT, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 20));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.DIALOGUE_SESSION_EVENTS, PayloadType.DIALOGUE_SESSION_EVENT, DeliveryPolicy.WAIT_IN_QUEUE, 40));
    }
}
