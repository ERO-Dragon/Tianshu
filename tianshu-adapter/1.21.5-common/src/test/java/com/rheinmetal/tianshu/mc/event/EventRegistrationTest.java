package com.rheinmetal.tianshu.mc.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EventRegistrationTest {
    @Test
    void registersImmediatelyAndUnregistersOnlyOnce() {
        List<String> operations = new ArrayList<>();

        EventRegistration registration = EventRegistration.register(
                () -> operations.add("register"),
                () -> operations.add("unregister")
        );
        registration.close();
        registration.close();

        assertEquals(List.of("register", "unregister"), operations);
    }
}
