package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit has a caller in production: a broken declaration is reported at start-up instead of leaving a module quietly
 * blind to the capabilities it thought it subscribed to.
 */
class CapabilitySubscriptionReportTest {

    private final List<CapabilityId> registered = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        registered.forEach(CapabilityCatalog::unregister);
        registered.clear();
        CapabilitySubscriptions.clear();
    }

    @Test
    void aSubscriptionToASilentProviderIsReported() {
        register("test:silent", "modA", false);
        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modA"));

        List<String> reported = new ArrayList<>();
        CapabilitySubscriptions.report(reported::add);

        assertEquals(1, reported.size(), reported.toString());
        assertTrue(reported.get(0).contains("modA") && reported.get(0).contains("shares nothing"), reported.get(0));
    }

    @Test
    void aSubscriptionThatWorksSaysNothing() {
        register("test:open", "modA", true);
        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modA"));

        List<String> reported = new ArrayList<>();
        CapabilitySubscriptions.report(reported::add);

        assertTrue(reported.isEmpty(), reported.toString());
    }

    @Test
    void aBaseSessionSaysNothing() {
        register("test:base", "tianshu", false);

        List<String> reported = new ArrayList<>();
        CapabilitySubscriptions.report(reported::add);

        assertTrue(reported.isEmpty(), "the base's own rows never need a subscription: " + reported);
    }

    @Test
    void aNullSinkIsNotACrash() {
        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modGhost"));

        CapabilitySubscriptions.report(null);
    }

    private void register(String id, String provider, boolean open) {
        CapabilityId capability = CapabilityId.parse(id);
        registered.add(capability);
        JsonObject attributes = new JsonObject();
        if (open) {
            attributes.addProperty("share", "all");
        }
        CapabilityDescriptor descriptor = new CapabilityDescriptor(capability, CapabilityKind.RESOURCE,
                CapabilityBinding.LOCAL, "a test row", ToolSide.SERVER, ToolPermission.EVERYONE, provider, attributes);
        CapabilityCatalog.register(descriptor, (context, args) ->
                CompletableFuture.completedFuture(new JsonObject()));
    }
}