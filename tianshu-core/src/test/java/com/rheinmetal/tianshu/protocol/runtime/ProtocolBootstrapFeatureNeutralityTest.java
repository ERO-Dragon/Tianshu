package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.registry.TopicDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A kernel without the presence artifact must know none of its vocabulary.
 *
 * <p>This is the runtime counterpart of the build's isolation gates: those check source dependencies, this checks
 * what a kernel actually registers at startup. The platform's own game event topic stays — it is host vocabulary —
 * while a feature's topics and payload names only exist once that feature is installed.</p>
 *
 * <p>The test lives in the kernel module on purpose: presence is not on this test classpath, so nothing here can
 * register presence's payload names by loading a class.</p>
 */
final class ProtocolBootstrapFeatureNeutralityTest {

    @Test
    void aKernelStartsWithRegisterRegistersNoFeatureTopic() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            assertTrue(runtime.topics().snapshot().stream()
                            .noneMatch(descriptor -> descriptor.topicId().startsWith("PRESENCE.")),
                    "the kernel must not keep a feature's topic alive");

            TopicDescriptor worldEvent = runtime.topics().find(ProtocolTopics.WORLD_EVENT).orElseThrow();
            assertTrue(worldEvent.topicId().startsWith("GAME."),
                    "the game event stream is host vocabulary and stays in the kernel");
        }
    }

    @Test
    void aKernelShipsNoFeaturePayloadName() {
        assertTrue(PayloadType.values().stream()
                        .noneMatch(type -> type.name().startsWith("PRESENCE")),
                "the kernel must not ship a feature's payload names");
    }
}
