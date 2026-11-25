package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * The model-facing handle of a published workflow: one named tool, one call, several steps.
 *
 * <p>This is what keeps the tool surface honest. A workflow appears in the tool list under the id its provider gave it
 * ({@code modA:mine-vein}) — not under a generic "run a workflow" entry — and calling it produces exactly one model
 * round trip, which is the only reason a workflow is worth publishing instead of letting the model drive the steps.</p>
 *
 * <p>The tool is a thin adapter: the run itself belongs to {@link WorkflowExecutor}, so the capability-table route and
 * this route cannot drift apart.</p>
 */
final class WorkflowTool implements TianshuTool<JsonObject, JsonObject> {

    private final WorkflowDeclaration plan;
    private final ToolPermission permission;

    WorkflowTool(WorkflowDeclaration plan, ToolPermission permission) {
        this.plan = plan;
        this.permission = permission;
    }

    @Override
    public ToolId id() {
        return ToolId.of(plan.id().namespace(), plan.id().name());
    }

    @Override
    public ToolCategory category() {
        // A plan that touches the world is a write wherever it came from; a read-only plan stays a read.
        return plan.changesWorld() ? ToolCategory.SERVER_WRITE : ToolCategory.SERVER_READ;
    }

    @Override
    public ToolPolicy policy() {
        return ToolPolicy.forCategory(category()).withPermission(permission);
    }

    @Override
    public String description() {
        return plan.description() + " (" + plan.stepCount() + " steps, about " + plan.expectedSeconds() + "s"
                + (plan.changesWorld() ? ", changes the world" : "") + ")";
    }

    @Override
    public Type argsType() {
        return JsonObject.class;
    }

    @Override
    public Type resultType() {
        return JsonObject.class;
    }

    @Override
    public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
        return WorkflowExecutor.run(plan, context.caller(), context.side(), context.cancellation())
                .thenApply(WorkflowTool::envelope);
    }

    private static JsonObject envelope(WorkflowRun run) {
        JsonObject answer = new JsonObject();
        JsonObject data = new JsonObject();
        data.addProperty("workflow", run.workflow().toString());
        data.addProperty("executedSteps", run.executedSteps());
        data.addProperty("totalSteps", run.totalSteps());
        if (run.cancelled()) {
            answer.addProperty("ok", true);
            answer.addProperty("cancelled", true);
            answer.addProperty("text", run.workflow() + " " + run.failure());
        } else if (run.completed()) {
            answer.addProperty("ok", true);
            answer.addProperty("text", run.workflow() + " finished in " + run.executedSteps() + " steps");
        } else {
            answer.addProperty("ok", false);
            answer.addProperty("code", "FAILED");
            answer.addProperty("error", run.workflow() + " " + run.failure());
            data.addProperty("failedStep", run.failedStep());
        }
        answer.add("data", data);
        return answer;
    }
}