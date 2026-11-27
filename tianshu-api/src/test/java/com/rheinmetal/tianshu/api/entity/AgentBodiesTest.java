package com.rheinmetal.tianshu.api.entity;

import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentBodiesTest {

    @AfterEach
    void clean() {
        EntityActors.clearForTests();
    }

    @Test
    void aProviderIsResolvableUntilItsRegistrationIsDropped() {
        EntityActor body = body("body-1", Set.of());
        Runnable unregister = EntityActors.register(id -> "body-1".equals(id) ? Optional.of(body) : Optional.empty());

        assertEquals(Optional.of(body), EntityActors.resolve("body-1"));
        assertTrue(EntityActors.resolve("body-2").isEmpty());

        unregister.run();
        assertTrue(EntityActors.resolve("body-1").isEmpty());
    }

    @Test
    void aServerEntryPointBindsTheRequestToItsOwnModuleAndCreatingPlayer() {
        UUID player = UUID.randomUUID();
        var request = ToolCaller.mod("maid").actingFor(player.toString());
        var controller = BodyController.forRequest("maid", request);
        assertEquals(player, controller.playerId());
        assertTrue(controller.refusal("maid", player).isEmpty());
        assertEquals(BodyFailure.PLAYER_DENIED.id(), controller.refusal("maid", null).orElseThrow().wireMessage());
        assertEquals(BodyFailure.CONTROLLER_MISMATCH,
                assertThrows(BodyToolException.class, () -> BodyController.forRequest("other", request)).reason());
        assertTrue(BodyController.forRequest("maid", ToolCaller.mod("maid")).refusal("maid", null).isEmpty());
    }

    @Test
    void anExternalBodyWithoutAPlayerOwnerDoesNotGrantDirectPlayerAccessByDefault() {
        EntityActors.register(id -> Optional.of(body(id, Set.of())));
        var caller = ToolCaller.player(UUID.randomUUID().toString(), true);
        assertEquals(BodyFailure.PLAYER_DENIED.id(), EntityActors.checkAccess(caller, "body-1").orElseThrow().wireMessage());
    }

    @Test
    void aPlayerIsNeverCheckedForBodyCapabilities() {
        assertTrue(EntityActors.checkCapability(ActorRef.player("p", true), EntityCapability.MINE).isEmpty());
    }

    @Test
    void actionsComeFromTheProviderThatOwnsTheBody() {
        EntityActions handlers = new EntityActions() {
        };
        EntityProvider provider = new EntityProvider() {
            @Override
            public Optional<EntityActor> resolve(String bodyId) {
                return "body-1".equals(bodyId) ? Optional.of(body("body-1", Set.of())) : Optional.empty();
            }

            @Override
            public Optional<EntityActions> actions(String bodyId) {
                return "body-1".equals(bodyId) ? Optional.of(handlers) : Optional.empty();
            }
        };
        EntityActors.register(provider);

        assertEquals(Optional.of(provider), EntityActors.owner("body-1"));
        assertEquals(Optional.of(handlers), EntityActors.actions("body-1"));
        assertTrue(EntityActors.actions("body-2").isEmpty());
    }

    @Test
    void aProviderWithoutHandlersOffersNoActions() {
        EntityActors.register(id -> "body-1".equals(id)
                ? Optional.of(body("body-1", Set.of()))
                : Optional.empty());

        assertTrue(EntityActors.actions("body-1").isEmpty());
    }

    @Test
    void accessIsJudgedByTheOwningProviderForPlayerCalls() {
        EntityProvider provider = new EntityProvider() {
            @Override
            public Optional<EntityActor> resolve(String bodyId) {
                return "body-1".equals(bodyId) ? Optional.of(body("body-1", Set.of())) : Optional.empty();
            }

            @Override
            public Optional<com.rheinmetal.tianshu.api.tool.ToolException> checkAccess(
                    com.rheinmetal.tianshu.api.tool.ToolCaller caller, String bodyId) {
                return Optional.of(new com.rheinmetal.tianshu.api.tool.ToolException(
                        com.rheinmetal.tianshu.api.tool.ToolError.NO_PERMISSION, "denied"));
            }
        };
        EntityActors.register(provider);

        assertTrue(EntityActors.checkAccess(
                com.rheinmetal.tianshu.api.tool.ToolCaller.player("p", true), "body-1").isPresent());
        assertTrue(EntityActors.checkAccess(
                com.rheinmetal.tianshu.api.tool.ToolCaller.agent(true), "body-1").isPresent());
    }

    @Test
    void aBodyThatDeclaresTheCapabilityPasses() {
        EntityActors.register(id -> "body-1".equals(id)
                ? Optional.of(body("body-1", Set.of(EntityCapability.MOVE, EntityCapability.MINE)))
                : Optional.empty());

        assertTrue(EntityActors.checkCapability(ActorRef.body("body-1", true), EntityCapability.MINE).isEmpty());
    }

    @Test
    void aBodyWithoutTheCapabilityIsRefusedAsUnsupported() {
        EntityActors.register(id -> "float".equals(id)
                ? Optional.of(body("float", Set.of(EntityCapability.MOVE, EntityCapability.LOOK)))
                : Optional.empty());

        ToolException refusal = EntityActors.checkCapability(ActorRef.body("float", true), EntityCapability.MINE)
                .orElseThrow();

        assertEquals(ToolError.UNSUPPORTED_ACTOR, refusal.code());
        assertTrue(refusal.getMessage().contains("float"));
    }

    @Test
    void aMissingBodyIsUnavailableRatherThanUnsupported() {
        ToolException refusal = EntityActors.checkCapability(ActorRef.body("gone", true), EntityCapability.MOVE)
                .orElseThrow();

        assertEquals(ToolError.UNAVAILABLE, refusal.code());
    }

    private static EntityActor body(String id, Set<EntityCapability> capabilities) {
        return new EntityActor() {
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
            public Set<EntityCapability> capabilities() {
                return capabilities;
            }
        };
    }
}