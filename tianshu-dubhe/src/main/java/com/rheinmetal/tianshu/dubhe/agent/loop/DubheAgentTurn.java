package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillContext;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillRegistry;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.weaving.contract.LLMToolCallPayload;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One step of an agent task: compact if needed, ask the model, then run the skills it called.
 *
 * <p>It is a {@link DubheStepSource}, so {@link DubheAgentLoop} keeps owning step/time limits, cancellation and
 * no-progress detection. This class owns only what is specific to a skill task: the transcript, the directory and
 * call execution.</p>
 *
 * <p>Tool calls arrive through native tool calling; the assistant message and its tool messages are replayed into
 * the transcript so the next request is a valid provider conversation. Skill failures are data, not exceptions: a
 * timeout or a thrown executor becomes a tool result the model can read, so it may pick another skill or finish
 * with a blocked fact. Only an interrupted wait aborts the step.</p>
 */
public final class DubheAgentTurn implements DubheStepSource<DubheAgentAnswer> {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String taskId;
    private final String question;
    private final DubheScope scope;
    private final DubheSkillRegistry registry;
    private final DubheAgentContextBudget budget;
    private final DubheAgentCompactor compactor;
    private final DubheAgentModel model;
    private final DubheAgentTranscript transcript;
    private final Set<String> usedSkills = new LinkedHashSet<>();

    public DubheAgentTurn(
            String taskId,
            String question,
            DubheScope scope,
            DubheSkillRegistry registry,
            DubheAgentContextBudget budget,
            DubheAgentCompactor compactor,
            DubheAgentModel model
    ) {
        this.taskId = taskId == null ? "" : taskId.trim();
        this.question = question == null ? "" : question.trim();
        this.scope = scope == null ? DubheScope.unknown() : scope;
        this.registry = registry == null ? new DubheSkillRegistry() : registry;
        this.budget = budget == null ? DubheAgentContextBudget.forWindow(32_768) : budget;
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        this.compactor = compactor;
        this.model = model;
        this.transcript = new DubheAgentTranscript(this.budget, this.question);
    }

    @Override
    public DubheStepOutcome<DubheAgentAnswer> next(DubheStepContext context) {
        if (transcript.needsCompaction()) {
            if (compactor == null) {
                throw new IllegalStateException("the task needs compaction but no compactor was wired");
            }
            transcript.compact(compactor);
        }

        DubheStepOutcome<DubheAgentModelReply> modelStep = model.next(new DubheAgentRequest(
                taskId, question, transcript.entries(), directory(),
                context.remainingMillis(), context.cancellation()));

        if (modelStep instanceof DubheStepOutcome.Finished<?> finished) {
            return handleReply((DubheAgentModelReply) finished.result(), context);
        }
        if (modelStep instanceof DubheStepOutcome.Aborted<?> aborted) {
            return new DubheStepOutcome.Aborted<>(aborted.cancellation(), aborted.expiry());
        }
        if (modelStep instanceof DubheStepOutcome.Rejected<?> rejected) {
            return new DubheStepOutcome.Rejected<>(rejected.failure());
        }
        throw new IllegalStateException("unknown model step outcome: " + modelStep);
    }

    public DubheAgentTranscript transcript() {
        return transcript;
    }

    List<DubheAgentEntry> entries() {
        return transcript.entries();
    }

    private DubheStepOutcome<DubheAgentAnswer> handleReply(DubheAgentModelReply reply, DubheStepContext context) {
        List<DubheSkillCall> calls = normalizedCalls(reply.calls());
        List<LLMToolCallPayload> replay = calls.stream()
                .map(call -> new LLMToolCallPayload(call.id(), call.skillId(), call.arguments().toString()))
                .toList();
        if (!reply.text().isBlank() || !replay.isEmpty()) {
            transcript.append(DubheAgentEntry.assistant(reply.text(), replay));
        }
        if (calls.isEmpty()) {
            return new DubheStepOutcome.Finished<>(new DubheAgentAnswer(reply.report(), List.copyOf(usedSkills)));
        }
        return invokeAll(calls, context);
    }

    private DubheStepOutcome<DubheAgentAnswer> invokeAll(List<DubheSkillCall> calls, DubheStepContext step) {
        List<String> names = new ArrayList<>(calls.size());
        List<String> canonicals = new ArrayList<>(calls.size());
        for (DubheSkillCall call : calls) {
            names.add(call.skillId());
            canonicals.add(canonical(call.arguments()));
            DubheStepOutcome<DubheAgentAnswer> aborted = invoke(call, step);
            if (aborted != null) {
                return aborted;
            }
        }
        return new DubheStepOutcome.Continue<>(String.join(",", names), String.join("|", canonicals));
    }

    /** Runs one call; returns an aborted outcome when the wait was interrupted, otherwise {@code null}. */
    private DubheStepOutcome<DubheAgentAnswer> invoke(DubheSkillCall call, DubheStepContext step) {
        Optional<DubheSkill> found = registry.find(call.skillId());
        if (found.isEmpty()) {
            transcript.append(DubheAgentEntry.toolResult(call.id(), "unknown skill: " + call.skillId()));
            return null;
        }

        DubheSkill skill = found.get();
        long timeout = Math.max(1L, Math.min(skill.spec().budget().timeoutMillis(), step.remainingMillis()));
        try {
            JsonObject result = skill.executor()
                    .execute(new DubheSkillContext(taskId, question, scope), call.arguments())
                    .get(timeout, TimeUnit.MILLISECONDS);
            transcript.append(DubheAgentEntry.toolResult(
                    call.id(), result == null ? "{}" : result.toString()));
            usedSkills.add(skill.id());
        } catch (TimeoutException timeoutException) {
            transcript.append(DubheAgentEntry.toolResult(call.id(), "timeout after " + timeout + "ms"));
        } catch (ExecutionException executionException) {
            transcript.append(DubheAgentEntry.toolResult(
                    call.id(), "failed: " + messageOf(executionException.getCause())));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new DubheStepOutcome.Aborted<>(
                    DubheTurnCancellation.expired("skill wait interrupted"), DubheRunExpiry.NONE);
        }
        return null;
    }

    private List<DubheSkillCall> normalizedCalls(List<DubheSkillCall> calls) {
        List<DubheSkillCall> normalized = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            DubheSkillCall call = calls.get(index);
            String id = call.id().isBlank() ? "call-" + index : call.id();
            normalized.add(new DubheSkillCall(id, call.skillId(), call.arguments()));
        }
        return normalized;
    }

    private List<DubheSkillSpec> directory() {
        return registry.all().stream().map(DubheSkill::spec).toList();
    }

    private static String canonical(JsonObject arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        TreeMap<String, JsonElement> sorted = new TreeMap<>();
        arguments.entrySet().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return GSON.toJson(sorted);
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}