package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.agent.ActorPosition;
import com.rheinmetal.tianshu.api.agent.AgentBodies;
import com.rheinmetal.tianshu.api.agent.AgentBody;
import com.rheinmetal.tianshu.api.agent.BodyCapability;

import java.util.Optional;
import java.util.Set;

/** Test-only agent bodies, so a tool test can state what a body can do without a game side. */
final class TestBodies {

    private TestBodies() {
    }

    /** Registers a body that declares exactly {@code capabilities}; the returned runnable unregisters it. */
    static Runnable register(String id, BodyCapability... capabilities) {
        Set<BodyCapability> declared = Set.of(capabilities);
        AgentBody body = new AgentBody() {
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
                return declared;
            }
        };
        return AgentBodies.register(resolved -> id.equals(resolved)
                ? Optional.of(body)
                : Optional.empty());
    }
}