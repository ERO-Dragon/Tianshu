package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.entity.ActorPosition;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityProvider;
import com.rheinmetal.tianshu.api.entity.BodyFailure;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.entity.EntityCapability;

import java.util.Optional;
import java.util.Set;

/** Test-only agent bodies, so a tool test can state what a body can do without a game side. */
public final class TestBodies {

    private TestBodies() {
    }

    /** Action fixtures grant their test player direct access to the body under test. */
    public interface PlayerFixture extends EntityProvider {
        @Override
        default Optional<ToolException> checkAccess(ToolCaller caller, String bodyId) {
            return caller.kind() == ToolCaller.Kind.PLAYER && caller.id().equals("p")
                    ? Optional.empty() : Optional.of(BodyFailure.PLAYER_DENIED.exception());
        }
    }

    /** Registers a body that declares exactly {@code capabilities}; the returned runnable unregisters it. */
    static Runnable register(String id, EntityCapability... capabilities) {
        Set<EntityCapability> declared = Set.of(capabilities);
        EntityActor body = new EntityActor() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String ownerModule() {
                return com.rheinmetal.tianshu.api.tool.ToolCaller.agent(false).id();
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
            public Set<EntityCapability> capabilities() {
                return declared;
            }
        };
        PlayerFixture provider = resolved -> id.equals(resolved)
                ? Optional.of(body)
                : Optional.empty();
        return EntityActors.register(provider);
    }
}