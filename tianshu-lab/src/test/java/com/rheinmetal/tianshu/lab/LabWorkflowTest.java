package com.rheinmetal.tianshu.lab;

import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.capability.WorkflowDeclaration;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference workflow, checked as a module would have to check its own: it is written in capabilities that exist, it
 * declares what it costs, and publishing it leaves exactly one row and one tool behind — until it is removed.
 */
class LabWorkflowTest {

    private static final ToolCaller CALLER = ToolCaller.agent("tianshu", false, ToolAutonomy.CONSERVATIVE);

    @org.junit.jupiter.api.AfterEach
    void cleanSubscriptions() {
        com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions.clear();
    }

    @Test
    void thePlanIsWrittenInTheBasesOwnCapabilities() {
        WorkflowDeclaration gather = LabWorkflow.gather();

        assertEquals(4, gather.stepCount());
        assertEquals("minecraft:entity.scan", gather.steps().get(0).capability().toString());
        assertEquals("minecraft:entity.move", gather.steps().get(1).capability().toString());
        assertEquals("minecraft:entity.mine", gather.steps().get(2).capability().toString());
        assertEquals("minecraft:entity.pickup", gather.steps().get(3).capability().toString());
        assertEquals("tianshu_lab", gather.id().namespace());
    }

    @Test
    void itDeclaresWhatRunningItCosts() {
        WorkflowDeclaration gather = LabWorkflow.gather();

        assertEquals(40, gather.expectedSeconds(), "a model decides whether to run this from the cost alone");
        assertTrue(gather.changesWorld(), "it mines blocks: calling it a read would hide that");
        assertFalse(gather.description().isBlank());
    }

    @Test
    void installingItLeavesOneWorkflowRowAndOneToolAndRemovalTakesBothOut() {
        // Subscribing is the consumer half; the bench workflow itself is published openly, which is why the consumer's
        // declaration is what makes it appear here.
        com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions.declare("tianshu",
                com.rheinmetal.tianshu.api.capability.CapabilitySubscription.everythingFrom("tianshu_lab"));
        Runnable removal = LabWorkflow.install();
        try {
            var row = CapabilityCatalog.find(LabWorkflow.gather().id()).orElseThrow();
            assertEquals(CapabilityKind.WORKFLOW, row.kind());
            assertEquals(LabModuleRegistration.MOD_ID, row.provider(), "a shared workflow stays attributable to its module");
            assertEquals(4, row.attributes().get("steps").getAsInt());
            assertEquals("minecraft:entity.scan", row.attributes().getAsJsonArray("stepList").get(0).getAsString(),
                    "the composition is readable without running it");
            assertTrue(TianshuTools.catalog(CALLER, ToolSide.SERVER).stream()
                    .anyMatch(spec -> spec.id().toString().equals("tianshu_lab:workflow.gather")));
        } finally {
            removal.run();
        }

        assertTrue(CapabilityCatalog.find(LabWorkflow.gather().id()).isEmpty());
        assertFalse(TianshuTools.catalog(CALLER, ToolSide.SERVER).stream()
                .anyMatch(spec -> spec.id().toString().equals("tianshu_lab:workflow.gather")));
    }
}