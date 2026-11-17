package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.DropResult;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.entity.EntityItemPort;
import com.rheinmetal.tianshu.api.entity.EquipRequest;
import com.rheinmetal.tianshu.api.entity.EquipResult;
import com.rheinmetal.tianshu.api.entity.PickupResult;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Handing something to someone is aimed at that someone — not a drop at the body's own feet.
 *
 * <p>The platform's job is to carry the intent through unchanged: target, slot and count. Who is in reach, and what
 * happens when nobody is, belongs to the adapter that owns the world.</p>
 */
class BodyGiveToolTest {

    private static final String BODY = "lab:giver";

    private final List<String> calls = new ArrayList<>();
    private Runnable unregister;

    @BeforeEach
    void setUp() {
        unregister = TestBodies.register(BODY, EntityCapability.INVENTORY);
        TianshuTools.register(new BodyGiveTool());
        TianshuTools.setEnabled(BodyGiveTool.ID, true);
    }

    @AfterEach
    void clean() {
        TianshuTools.clearEnabledOverride(BodyGiveTool.ID);
        TianshuTools.unregister(BodyGiveTool.ID);
        unregister.run();
        TianshuRuntimePorts.clear();
    }

    @Test
    void theTargetSlotAndCountReachThePortUnchanged() throws Exception {
        install();

        JsonObject args = new JsonObject();
        args.addProperty("body", BODY);
        args.addProperty("slot", 2);
        args.addProperty("count", 5);
        args.addProperty("target", "Steve");

        JsonObject envelope = TianshuTools.callJson(BodyGiveTool.ID, args, ToolCaller.agent(true), ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);

        assertTrue(envelope.get("ok").getAsBoolean(), envelope.toString());
        assertEquals(List.of("handTo 2 5 Steve"), calls);
    }

    @Test
    void aHandOverWithNoTargetIsRefusedBeforeThePort() {
        install();

        JsonObject args = new JsonObject();
        args.addProperty("body", BODY);
        args.addProperty("slot", 0);
        args.addProperty("count", 1);

        assertTrue(refusalCode(args).contains("INVALID_ARGS"), "a hand-over without a receiver is malformed");
        assertTrue(calls.isEmpty(), "nothing should have been handed over");
    }

    private void install() {
        TianshuRuntimePorts.register(EntityItemPort.class, new EntityItemPort() {
            @Override
            public CompletableFuture<PickupResult> pickup(ActorRef actor, int radius) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count) {
                return CompletableFuture.completedFuture(new DropResult("minecraft:stone", count));
            }

            @Override
            public CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            @Override
            public CompletableFuture<DropResult> handTo(ActorRef actor, int slot, int count, String target) {
                calls.add("handTo " + slot + " " + count + " " + target);
                return CompletableFuture.completedFuture(new DropResult("minecraft:stone", count));
            }
        });
    }

    private String refusalCode(JsonObject args) {
        try {
            JsonObject envelope = TianshuTools.callJson(BodyGiveTool.ID, args, ToolCaller.agent(true), ToolSide.SERVER)
                    .get(2, TimeUnit.SECONDS);
            return envelope.toString();
        } catch (ExecutionException failure) {
            return String.valueOf(failure.getCause());
        } catch (Exception other) {
            return other.getClass().getSimpleName();
        }
    }
}