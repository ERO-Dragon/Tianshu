package com.rheinmetal.tianshu.api.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBodiesTest {

    @AfterEach
    void clean() {
        AgentBodies.clearForTests();
    }

    @Test
    void aProviderIsResolvableUntilItsRegistrationIsDropped() {
        AgentBody body = new AgentBody() {
            @Override
            public String id() {
                return "body-1";
            }

            @Override
            public boolean operator() {
                return false;
            }

            @Override
            public ActorPosition position() {
                return new ActorPosition("minecraft:overworld", 0, 64, 0);
            }
        };
        Runnable unregister = AgentBodies.register(id -> "body-1".equals(id) ? Optional.of(body) : Optional.empty());

        assertEquals(Optional.of(body), AgentBodies.resolve("body-1"));
        assertTrue(AgentBodies.resolve("body-2").isEmpty());

        unregister.run();
        assertTrue(AgentBodies.resolve("body-1").isEmpty());
    }
}
