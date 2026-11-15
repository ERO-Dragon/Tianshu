package com.rheinmetal.tianshu.api.agent;

import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBodiesTest {

    @AfterEach
    void clean() {
        AgentBodies.clearForTests();
    }

    @Test
    void aProviderIsResolvableUntilItsRegistrationIsDropped() {
        AgentBody body = body("body-1", Set.of());
        Runnable unregister = AgentBodies.register(id -> "body-1".equals(id) ? Optional.of(body) : Optional.empty());

        assertEquals(Optional.of(body), AgentBodies.resolve("body-1"));
        assertTrue(AgentBodies.resolve("body-2").isEmpty());

        unregister.run();
        assertTrue(AgentBodies.resolve("body-1").isEmpty());
    }

    @Test
    void aPlayerIsNeverCheckedForBodyCapabilities() {
        assertTrue(AgentBodies.checkCapability(ActorRef.player("p", true), BodyCapability.MINE).isEmpty());
    }

    @Test
    void actionsComeFromTheProviderThatOwnsTheBody() {
        AgentBodyActions handlers = new AgentBodyActions() {
        };
        AgentBodyProvider provider = new AgentBodyProvider() {
            @Override
            public Optional<AgentBody> resolve(String bodyId) {
                return "body-1".equals(bodyId) ? Optional.of(body("body-1", Set.of())) : Optional.empty();
            }

            @Override
            public Optional<AgentBodyActions> actions(String bodyId) {
                return "body-1".equals(bodyId) ? Optional.of(handlers) : Optional.empty();
            }
        };
        AgentBodies.register(provider);

        assertEquals(Optional.of(provider), AgentBodies.owner("body-1"));
        assertEquals(Optional.of(handlers), AgentBodies.actions("body-1"));
        assertTrue(AgentBodies.actions("body-2").isEmpty());
    }

    @Test
    void aProviderWithoutHandlersOffersNoActions() {
        AgentBodies.register(id -> "body-1".equals(id)
                ? Optional.of(body("body-1", Set.of()))
                : Optional.empty());

        assertTrue(AgentBodies.actions("body-1").isEmpty());
    }

    @Test
    void aBodyThatDeclaresTheCapabilityPasses() {
        AgentBodies.register(id -> "body-1".equals(id)
                ? Optional.of(body("body-1", Set.of(BodyCapability.MOVE, BodyCapability.MINE)))
                : Optional.empty());

        assertTrue(AgentBodies.checkCapability(ActorRef.body("body-1", true), BodyCapability.MINE).isEmpty());
    }

    @Test
    void aBodyWithoutTheCapabilityIsRefusedAsUnsupported() {
        AgentBodies.register(id -> "float".equals(id)
                ? Optional.of(body("float", Set.of(BodyCapability.MOVE, BodyCapability.LOOK)))
                : Optional.empty());

        ToolException refusal = AgentBodies.checkCapability(ActorRef.body("float", true), BodyCapability.MINE)
                .orElseThrow();

        assertEquals(ToolError.UNSUPPORTED_ACTOR, refusal.code());
        assertTrue(refusal.getMessage().contains("float"));
    }

    @Test
    void aMissingBodyIsUnavailableRatherThanUnsupported() {
        ToolException refusal = AgentBodies.checkCapability(ActorRef.body("gone", true), BodyCapability.MOVE)
                .orElseThrow();

        assertEquals(ToolError.UNAVAILABLE, refusal.code());
    }

    private static AgentBody body(String id, Set<BodyCapability> capabilities) {
        return new AgentBody() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean operator() {
                return true;
            }

            @Override
            public ActorPosition position() {
                return new ActorPosition("minecraft:overworld", 0, 64, 0);
            }

            @Override
            public Set<BodyCapability> capabilities() {
                return capabilities;
            }
        };
    }
}