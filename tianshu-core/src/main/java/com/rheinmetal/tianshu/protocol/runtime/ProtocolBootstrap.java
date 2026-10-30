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
        // Platform vocabulary only. A feature's topics are declared by the module that produces them, in its
        // own register(); the kernel no longer knows a feature's topic names.
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.SYSTEM_RUNTIME_INTERRUPT, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 40));
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.MODULE_STATUS, PayloadType.MODULE_STATUS, DeliveryPolicy.LATEST_ONLY, 40));
        // Raw world events are dropped by aggregation, never by delivery: a consumer that windows them must see
        // every occurrence, and an absent consumer completes silently (a registered topic with no subscriber).
        runtime.registerTopic(new TopicDescriptor(ProtocolTopics.WORLD_EVENT, PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 40));
    }
}

