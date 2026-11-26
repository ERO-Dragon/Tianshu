package com.rheinmetal.tianshu.lab;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.WorkflowDeclaration;
import com.rheinmetal.tianshu.api.capability.WorkflowInstaller;
import com.rheinmetal.tianshu.api.capability.WorkflowStep;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.List;

/**
 * The reference composite capability: gather a number of one block type nearby.
 *
 * <p>It lives here, not in the base, for a reason worth keeping: the base provides the <em>channel</em> and ships no
 * workflows of its own, so the first workflow anyone reads is one a module published — through the same registration path
 * a third-party module would use, and removed again when the module stops.</p>
 *
 * <p>Every step is a capability the base already has, which is the point of publishing steps as data: nothing here
 * reaches into game code, it says <em>what to do in which order</em>, and the base's executor does it as the caller.</p>
 */
public final class LabWorkflow {

    /** Namespace of everything this bench publishes. */
    public static final String NAMESPACE = "tianshu_lab";

    /** Tool ids the plan is written in terms of — the base's own capabilities, nothing lab-specific. */
    private static final String SCAN = "minecraft:entity.scan";
    private static final String MOVE = "minecraft:entity.move";
    private static final String MINE = "minecraft:entity.mine";
    private static final String PICKUP = "minecraft:entity.pickup";

    private LabWorkflow() {
    }

    /**
     * What the bench publishes: a four-step plan with a declared cost.
     *
     * <p>The cost is not decoration — a model decides whether to run this from the declaration alone, and an operator
     * reads it to decide whether the plan is acceptable on their server.</p>
     */
    public static WorkflowDeclaration gather() {
        return new WorkflowDeclaration(
                CapabilityId.of(NAMESPACE, "workflow.gather"),
                "Gathers a number of one block type from the blocks around the body: scans, walks to each hit, mines it "
                        + "and picks up the drop. Stops when the count is reached, the inventory is full, or the scan "
                        + "finds nothing.",
                List.of(
                        step(SCAN),
                        step(MOVE),
                        step(MINE),
                        step(PICKUP)),
                40,
                true, ActorRef.Kind.BODY);
    }

    /** Publishes the reference workflow; the returned action removes it again. */
    public static Runnable install() {
        return WorkflowInstaller.install(gather(), LabModuleRegistration.MOD_ID, ToolSide.SERVER,
                ToolPermission.EVERYONE, com.rheinmetal.tianshu.api.capability.CapabilitySharing.ALL);
    }

    private static WorkflowStep step(String capability) {
        return WorkflowStep.of(capability);
    }
}