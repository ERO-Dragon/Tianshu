package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who sees which capability: the provider's declaration and the consumer's subscription, both required.
 *
 * <p>Two failures are easy to ship and hard to notice, so both are asserted: a base row that a module cannot see (the
 * assistant loses its own tools), and a third-party row that a module sees without asking (installing two mods opens
 * both to each other).</p>
 */
class CapabilityVisibilityTest {

    private static final ToolCaller CONSUMER =
            ToolCaller.agent("changsui", false, ToolAutonomy.CONSERVATIVE);
    private static final ToolCaller BASE = ToolCaller.agent("tianshu", false, ToolAutonomy.CONSERVATIVE);

    private final List<CapabilityId> registered = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        registered.forEach(CapabilityCatalog::unregister);
        registered.clear();
        CapabilitySubscriptions.clear();
    }

    @Test
    void aBaseRowIsOpenToEveryoneWithoutADeclaration() {
        CapabilityDescriptor row = register("test:base-read", CapabilityKind.RESOURCE, "tianshu", null);

        assertEquals(CapabilitySharing.Scope.ALL, CapabilitySharing.of(row).scope(),
                "the base is what everything is built on: no declaration means open");
        assertTrue(visibleTo(CONSUMER, row));
    }

    @Test
    void aModuleRowIsPrivateUntilItSaysOtherwise() {
        CapabilityDescriptor row = register("test:private", CapabilityKind.RESOURCE, "modA", null);

        assertEquals(CapabilitySharing.Scope.NONE, CapabilitySharing.of(row).scope(),
                "an undeclared share must fail closed");
        assertFalse(visibleTo(CONSUMER, row));
    }

    @Test
    void bothSidesHaveToAgree() {
        JsonObject shareWithChangsui = new JsonObject();
        shareWithChangsui.addProperty("share", "changsui");
        CapabilityDescriptor shared = register("test:shared", CapabilityKind.RESOURCE, "modA", shareWithChangsui);

        // Provider opened it, consumer declared nothing: still invisible.
        assertFalse(visibleTo(CONSUMER, shared), "a grant nobody accepted is not a subscription");

        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modA"));
        assertTrue(visibleTo(CONSUMER, shared));

        // And the other direction: consumer asks, provider never opened.
        CapabilityDescriptor closed = register("test:closed", CapabilityKind.RESOURCE, "modB", null);
        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modB"));
        assertFalse(visibleTo(CONSUMER, closed), "asking for something nobody shares gets nothing");
    }

    @Test
    void compositesAreNotSubscribedByDefault() {
        JsonObject open = new JsonObject();
        open.addProperty("share", "all");
        CapabilityDescriptor workflow = register("test:flow", CapabilityKind.WORKFLOW, "modA", open);

        assertEquals(CapabilitySubscription.DEFAULT, CapabilitySubscriptions.ruleFor("changsui"));
        assertFalse(visibleTo(CONSUMER, workflow), "someone else's sequencing does not appear unasked");

        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.everythingFrom("modA"));
        assertTrue(visibleTo(CONSUMER, workflow));
    }

    @Test
    void atomicOnlySubscriptionsHideCompositesAndTheReverse() {
        JsonObject open = new JsonObject();
        open.addProperty("share", "all");
        CapabilityDescriptor atomic = register("test:atomic", CapabilityKind.RESOURCE, "modA", open);
        CapabilityDescriptor workflow = register("test:flow", CapabilityKind.WORKFLOW, "modA", open);

        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modA"));
        assertTrue(visibleTo(CONSUMER, atomic));
        assertFalse(visibleTo(CONSUMER, workflow));

        CapabilitySubscriptions.declare("changsui",
                new CapabilitySubscription(false, java.util.Set.of("modA"), false, true));
        assertFalse(visibleTo(CONSUMER, atomic));
        assertTrue(visibleTo(CONSUMER, workflow));
    }

    @Test
    void aModuleAlwaysSeesWhatItPublishedItself() {
        CapabilityDescriptor own = register("test:own", CapabilityKind.WORKFLOW, "changsui", null);

        assertTrue(visibleTo(ToolCaller.agent("changsui", false, ToolAutonomy.CONSERVATIVE), own),
                "a provider must be able to use what it published");
    }

    @Test
    void theBaseCallerSeesTheBaseRows() {
        CapabilityDescriptor base = register("test:base", CapabilityKind.RESOURCE, "tianshu", null);

        assertTrue(visibleTo(BASE, base), "the assistant must not lose the base's own capabilities");
    }

    @Test
    void aPlayerSessionIsNotNarrowedByModuleDeclarations() {
        CapabilityDescriptor privateRow = register("test:private", CapabilityKind.RESOURCE, "modA", null);

        assertTrue(visibleTo(ToolCaller.player("uuid", true), privateRow),
                "sharing is a module-to-module arrangement, not a restriction on the human's own session");
    }

    @Test
    void theAuditTellsAPromiseApartFromAMissingMod() {
        register("test:silent", CapabilityKind.RESOURCE, "modA", null);
        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modA", "modGhost"));

        List<String> problems = CapabilitySubscriptions.audit();

        assertTrue(problems.stream().anyMatch(line -> line.contains("modA") && line.contains("shares nothing")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(line -> line.contains("modGhost") && line.contains("not installed")),
                problems.toString());
    }

    private static boolean visibleTo(ToolCaller caller, CapabilityDescriptor row) {
        return CapabilityExposure.exposed(caller, ToolSide.SERVER).stream()
                .anyMatch(exposed -> exposed.id().equals(row.id()));
    }

    private CapabilityDescriptor register(String id, CapabilityKind kind, String provider, JsonObject attributes) {
        CapabilityId capability = CapabilityId.parse(id);
        registered.add(capability);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(capability, kind, CapabilityBinding.LOCAL,
                "a test row", ToolSide.SERVER, ToolPermission.EVERYONE, provider,
                attributes == null ? new JsonObject() : attributes);
        CapabilityCatalog.register(descriptor, (context, args) -> CompletableFuture.completedFuture(new JsonObject()));
        return descriptor;
    }
}