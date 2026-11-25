package com.rheinmetal.tianshu.lab;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.CapabilityDescriptor;
import com.rheinmetal.tianshu.protocol.registry.EnvelopeHandler;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolCapabilityRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.trigger.TriggerAccess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bench declares its capabilities through the same adapter an outside module uses.
 *
 * <p>It is worth a test of its own because the declaration is the one part of the bench that runs inside the host's
 * {@code register} phase, where a throw is swallowed as "an optional module failed" and nothing else says so.</p>
 */
class LabProtocolAdapterTest {

    @Test
    void everyCapabilityTheBenchOwnsIsDeclaredUnderItsModule() {
        RecordingRuntime runtime = new RecordingRuntime();
        LabProtocolAdapter adapter = new LabProtocolAdapter(runtime);

        adapter.registerDelivery(LabProtocolAdapter.ALPHA_CAPABILITY, (envelope, context) -> { });
        adapter.registerEcho((envelope, context) -> { });

        assertEquals(List.of(LabProtocolAdapter.ALPHA_CAPABILITY, LabProtocolAdapter.ECHO_CAPABILITY),
                List.copyOf(runtime.declared.keySet()));
        assertEquals(List.of(LabProtocolAdapter.MODULE_ID, LabProtocolAdapter.MODULE_ID),
                runtime.modules);
    }

    /** Answers nothing and records what a module declared; the adapter only ever registers. */
    private static final class RecordingRuntime implements ModuleRuntimeAccess {

        private final Map<String, CapabilityDescriptor> declared = new LinkedHashMap<>();
        private final List<String> modules = new ArrayList<>();

        @Override
        public void registerModule(ModuleDescriptor descriptor, EnvelopeHandler handler) {
            modules.add(descriptor.moduleId());
            for (CapabilityDescriptor capability : descriptor.capabilities()) {
                declared.put(capability.capabilityId(), capability);
            }
        }

        @Override
        public void registerTopic(TopicDescriptor descriptor) {
        }

        @Override
        public void registerResponseHandler(String requestEnvelopeId, ModuleDescriptor descriptor,
                                            CapabilityDescriptor capabilityDescriptor, EnvelopeHandler handler) {
        }

        @Override
        public void unregisterResponseHandlers(String requestEnvelopeId) {
        }

        @Override
        public void subscribeTopic(ModuleDescriptor moduleDescriptor, TopicSubscriptionDescriptor descriptor,
                                   EnvelopeHandler handler) {
        }

        @Override
        public void submit(TianshuEnvelope envelope) {
        }

        @Override
        public TriggerAccess triggers() {
            return null;
        }

        @Override
        public ProtocolTaskHandle submit(ProtocolTaskSpec spec, Runnable task) {
            return null;
        }

        @Override
        public <T> ProtocolTaskHandle submit(ProtocolTaskSpec spec, Callable<T> task) {
            return null;
        }

        @Override
        public ProtocolTaskHandle schedule(ProtocolTaskSpec spec, Runnable task, Duration delay) {
            return null;
        }

        @Override
        public List<ProtocolCapabilityRegistration> capabilityRegistrations(String capabilityId) {
            CapabilityDescriptor capability = declared.get(capabilityId);
            return capability == null ? List.of()
                    : List.of(new ProtocolCapabilityRegistration(LabProtocolAdapter.MODULE_ID, capability));
        }

        @Override
        public int topicSubscriberCount(String topicId) {
            return 0;
        }
    }
}