package com.rheinmetal.tianshu.protocol.registry;

import com.rheinmetal.tianshu.protocol.DeliveryPolicy;
import com.rheinmetal.tianshu.protocol.PayloadType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopicRegistryTest {
    @Test
    void acceptsRepeatedRegistrationOfSameDescriptor() {
        TopicRegistry registry = new TopicRegistry();
        TopicDescriptor descriptor = new TopicDescriptor("TEST.TOPIC", PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 40);

        registry.register(descriptor);

        assertDoesNotThrow(() -> registry.register(new TopicDescriptor("TEST.TOPIC", PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 40)));
    }

    @Test
    void rejectsRepeatedRegistrationWithDifferentDescriptor() {
        TopicRegistry registry = new TopicRegistry();
        registry.register(new TopicDescriptor("TEST.TOPIC", PayloadType.CUSTOM, DeliveryPolicy.WAIT_IN_QUEUE, 40));

        assertThrows(IllegalStateException.class,
                () -> registry.register(new TopicDescriptor("TEST.TOPIC", PayloadType.NONE, DeliveryPolicy.WAIT_IN_QUEUE, 40)));
    }
}
