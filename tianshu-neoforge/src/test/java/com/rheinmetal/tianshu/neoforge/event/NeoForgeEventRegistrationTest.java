package com.rheinmetal.tianshu.neoforge.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NeoForgeEventRegistrationTest {
    @Test
    void registersImmediatelyAndUnregistersOnlyOnce() {
        List<String> operations = new ArrayList<>();

        NeoForgeEventRegistration registration = NeoForgeEventRegistration.register(
                () -> operations.add("register"),
                () -> operations.add("unregister")
        );
        registration.close();
        registration.close();

        assertEquals(List.of("register", "unregister"), operations);
    }
}
