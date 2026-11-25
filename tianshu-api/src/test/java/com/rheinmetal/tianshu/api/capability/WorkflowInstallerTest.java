package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installing a workflow: one call from the model, several capabilities underneath, and a row that says who owns it.
 */
class WorkflowInstallerTest {

    private static final ToolCaller CALLER = ToolCaller.agent("tianshu", false, ToolAutonomy.CONSERVATIVE);

    private final List<CapabilityId> installed = new ArrayList<>();
    private Runnable removal;

    @AfterEach
    void cleanUp() {
        if (removal != null) {
            removal.run();
            removal = null;
        }
        installed.forEach(CapabilityCatalog::unregister);
        installed.clear();
        CapabilitySubscriptions.clear();
    }

    @Test
    void aWorkflowIsOneNamedToolThatRunsItsSteps() {
        AtomicInteger calls = new AtomicInteger();
        registerStep("test:step-a", "provider-a", calls);
        registerStep("test:step-b", "provider-b", calls);

        WorkflowDeclaration plan = publish("test:chain", "collect something", 3, false,
                WorkflowStep.of("test:step-a"), WorkflowStep.of("test:step-b"));

        assertEquals(CapabilityKind.WORKFLOW, CapabilityCatalog.find(plan.id()).orElseThrow().kind(),
                "the row is a workflow, not a tool row claiming the kernel as provider");
        assertEquals("provider-lab", CapabilityCatalog.find(plan.id()).orElseThrow().provider(),
                "a shared workflow must stay attributable to the module that published it");
        assertTrue(TianshuTools.catalog(CALLER, ToolSide.SERVER).stream()
                        .anyMatch(spec -> spec.id().toString().equals("test:chain")),
                "the model sees it under its own name");

        JsonObject answer = CapabilityCatalog.invoke(plan.id(), new JsonObject(), CALLER, ToolSide.SERVER).join();

        assertTrue(answer.get("ok").getAsBoolean(), answer.toString());
        assertEquals(2, calls.get());
    }

    @Test
    void removingTheWorkflowTakesBothRegistrationsOut() {
        registerStep("test:step-a", "provider-a", new AtomicInteger());
        WorkflowDeclaration plan = publish("test:chain", "temporary", 1, false, WorkflowStep.of("test:step-a"));

        removal.run();
        removal = null;

        assertTrue(CapabilityCatalog.find(plan.id()).isEmpty(), "removal takes the row out");
        assertFalse(TianshuTools.catalog(CALLER, ToolSide.SERVER).stream()
                .anyMatch(spec -> spec.id().toString().equals("test:chain")), "removal takes the tool out too");
    }

    @Test
    void aFailedStepKeepsTheWorkflowsOwnNameInTheError() {
        registerStep("test:step-a", "provider-a", new AtomicInteger());
        registerRefusingStep("test:step-bad", "provider-b");

        WorkflowDeclaration plan = publish("test:chain", "fails halfway", 1, false,
                WorkflowStep.of("test:step-a"), WorkflowStep.of("test:step-bad"));

        JsonObject answer = CapabilityCatalog.invoke(plan.id(), new JsonObject(), CALLER, ToolSide.SERVER).join();

        assertFalse(answer.get("ok").getAsBoolean());
        String error = answer.get("error").getAsString();
        assertTrue(error.contains("test:chain"), error);
        assertTrue(error.contains("test:step-bad"), error);
        assertTrue(error.contains("provider-b"), error);
    }

    @Test
    void aDeclaredCostIsVisibleBeforeAnyoneRunsIt() {
        registerStep("test:step-a", "provider-a", new AtomicInteger());
        WorkflowDeclaration plan = publish("test:cost", "a long one", 90, true, WorkflowStep.of("test:step-a"));

        JsonObject attributes = CapabilityCatalog.find(plan.id()).orElseThrow().attributes();
        assertEquals(90, attributes.get("expectedSeconds").getAsInt());
        assertTrue(attributes.get("changesWorld").getAsBoolean());
        assertNotNull(attributes.get("steps"));
    }

    private WorkflowDeclaration publish(String id, String description, int seconds, boolean changesWorld,
                                        WorkflowStep... steps) {
        WorkflowDeclaration plan = new WorkflowDeclaration(CapabilityId.parse(id), description, List.of(steps),
                seconds, changesWorld);
        // Publishing openly plus the consumer's subscription: the workflow row carries the provider's half of the
        // sharing declaration, and this is the consumer's half.
        CapabilitySubscriptions.declare("tianshu",
                CapabilitySubscription.everythingFrom("provider-lab", "provider-a", "provider-b"));
        removal = WorkflowInstaller.install(plan, "provider-lab", ToolSide.SERVER, ToolPermission.EVERYONE,
                CapabilitySharing.ALL);
        return plan;
    }

    private void registerStep(String id, String provider, AtomicInteger calls) {
        CapabilityId capability = CapabilityId.parse(id);
        installed.add(capability);
        CapabilityCatalog.register(new CapabilityDescriptor(capability, CapabilityKind.of("test"),
                CapabilityBinding.LOCAL, "a test step", ToolSide.SERVER, ToolPermission.EVERYONE, provider,
                opened()), (context, args) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(ok());
                });
    }

    private void registerRefusingStep(String id, String provider) {
        CapabilityId capability = CapabilityId.parse(id);
        installed.add(capability);
        CapabilityCatalog.register(new CapabilityDescriptor(capability, CapabilityKind.of("test"),
                CapabilityBinding.LOCAL, "refuses", ToolSide.SERVER, ToolPermission.EVERYONE, provider,
                new JsonObject()), (context, args) -> {
                    JsonObject refusal = new JsonObject();
                    refusal.addProperty("ok", false);
                    refusal.addProperty("code", "NO_PERMISSION");
                    refusal.addProperty("error", "nope");
                    return CompletableFuture.completedFuture(refusal);
                });
    }

    private static JsonObject ok() {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        return envelope;
    }

    /** Steps are opened rows: the default subscription accepts what a provider opened. */
    private static com.google.gson.JsonObject opened() {
        com.google.gson.JsonObject attributes = new com.google.gson.JsonObject();
        attributes.addProperty("share", "all");
        return attributes;
    }
}
