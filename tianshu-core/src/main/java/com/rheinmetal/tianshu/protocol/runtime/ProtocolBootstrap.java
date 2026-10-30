package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;

public final class ProtocolBootstrap {
    private ProtocolBootstrap() {
    }

    public static ProtocolRuntime create(MainThreadExecutor mainThreadExecutor) {
        ProtocolRuntime runtime = new ProtocolRuntime(mainThreadExecutor);
        registerTopics(runtime);
        return runtime;
    }

    public static ProtocolRuntime create(MainThreadExecutor mainThreadExecutor, ProtocolRuntimePolicy runtimePolicy) {
        ProtocolRuntime runtime = new ProtocolRuntime(mainThreadExecutor, runtimePolicy);
        registerTopics(runtime);
        return runtime;
    }

    public static ProtocolRuntime create(MainThreadExecutor mainThreadExecutor, TriggerRegistry triggerRegistry) {
        ProtocolRuntime runtime = new ProtocolRuntime(mainThreadExecutor, triggerRegistry);
        registerTopics(runtime);
        return runtime;
    }

    public static ProtocolRuntime create(MainThreadExecutor mainThreadExecutor, TriggerRegistry triggerRegistry, ProtocolRuntimePolicy runtimePolicy) {
        ProtocolRuntime runtime = new ProtocolRuntime(mainThreadExecutor, triggerRegistry, runtimePolicy);
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
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.TTS_REQUEST_STATUS, PayloadType.TTS_REQUEST_STATUS, DeliveryPolicy.WAIT_IN_QUEUE, 64));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.MODULE_STATUS, PayloadType.MODULE_STATUS, DeliveryPolicy.LATEST_ONLY, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.DIALOGUE_SESSION_EVENTS, PayloadType.DIALOGUE_SESSION_EVENT, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        // Raw world events are dropped by aggregation, never by delivery: a consumer that windows them must see
        // every occurrence, and an absent consumer completes silently (a registered topic with no subscriber).
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.WORLD_EVENT, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        // The rows above this comment are the four original modules' topics and are still registered by the
        // kernel; presence, added later, registers its own topics in PresenceProtocolAdapter#registerOwnedTopics.
        // New modules should follow that pattern: register while the session is assembled, not here.
    }
}

