package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.Objects;

/**
 * Installs a published workflow: one capability row, and one tool the model can call.
 *
 * <p>Two registrations, and both are needed for different readers. The capability row (kind {@link CapabilityKind#WORKFLOW},
 * provider = the module) is what a consumer subscribes to and what a failure is attributed to. The tool entry is what
 * puts the workflow in front of a model under its own name.</p>
 *
 * <p>They are installed here together on purpose: a workflow that exists in one table but not the other would be either
 * invisible to the model or unaccountable when it fails. The row is registered directly rather than through
 * {@code publishTool}, because a tool row would claim the kernel as its provider and lose the module attribution that
 * makes a shared workflow answerable.</p>
 */
public final class WorkflowInstaller {

    private WorkflowInstaller() {
    }

    /**
     * Publishes {@code plan} and returns the action that removes it again.
     *
     * <p>Removal takes both registrations out, so a module that unloads leaves neither a stale tool nor a row pointing at
     * a handler that no longer exists.</p>
     */
    public static Runnable install(WorkflowDeclaration plan, String provider, ToolSide side, ToolPermission permission) {
        Objects.requireNonNull(plan, "plan");
        String owner = provider == null || provider.isBlank() ? "unknown" : provider;
        ToolPermission effective = permission == null ? ToolPermission.EVERYONE : permission;

        WorkflowTool tool = new WorkflowTool(plan, effective);
        TianshuTools.register(tool);
        // The tool registry mirrors every tool into the table (that is how the model's list and the table stay in step).
        // A workflow needs its own row, though: kind WORKFLOW so a consumer can subscribe to composites separately, and
        // provider = the module, so a failure can be attributed to whoever published the plan instead of to the kernel.
        // So the mirror is dropped and replaced by the authoritative row below.
        CapabilityCatalog.removeTool(plan.id());

        JsonObject attributes = new JsonObject();
        attributes.addProperty("steps", plan.stepCount());
        attributes.addProperty("expectedSeconds", plan.expectedSeconds());
        attributes.addProperty("changesWorld", plan.changesWorld());
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                plan.id(),
                CapabilityKind.WORKFLOW,
                CapabilityBinding.LOCAL,
                plan.description(),
                side == null ? ToolSide.SERVER : side,
                effective,
                owner,
                attributes);
        CapabilityCatalog.register(descriptor,
                (context, args) -> WorkflowExecutor.run(plan, context.caller(), context.side(),
                        com.rheinmetal.tianshu.api.tool.CancellationSignal.NEVER).thenApply(WorkflowInstaller::envelope));

        // Unregistering the tool also drops its row, so there is nothing to remove twice.
        return () -> TianshuTools.unregister(tool.id());
    }

    private static JsonObject envelope(WorkflowRun run) {
        JsonObject answer = new JsonObject();
        if (run.completed() || run.cancelled()) {
            answer.addProperty("ok", true);
            answer.addProperty("text", run.completed()
                    ? run.workflow() + " finished in " + run.executedSteps() + " steps"
                    : run.workflow() + " " + run.failure());
            return answer;
        }
        answer.addProperty("ok", false);
        answer.addProperty("code", "FAILED");
        answer.addProperty("error", run.workflow() + " " + run.failure());
        return answer;
    }
}