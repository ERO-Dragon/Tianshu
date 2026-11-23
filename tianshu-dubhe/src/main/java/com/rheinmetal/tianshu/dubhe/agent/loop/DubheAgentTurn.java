package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillContext;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillRegistry;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One step of an agent task: compact if needed, ask the model, then run the skill it called.
 *
 * <p>It is a {@link DubheStepSource}, so {@link DubheAgentLoop} keeps owning step/time limits, cancellation and
 * no-progress detection. This class owns only what is specific to a skill task: the transcript, the directory and
 * the call execution.</p>
 *
 * <p>Skill failures are data, not exceptions: a timeout or a thrown executor becomes a tool result the model can
 * read, so it may pick another skill or finish with {@code blocked} facts. Only an interrupted wait aborts the
 * step.</p>
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

        DubheAgentModelReply reply = model.next(
                new DubheAgentRequest(taskId, question, transcript.entries(), directory()));
        if (!reply.text().isBlank()) {
            transcript.append(new DubheAgentEntry(DubheAgentEntry.Kind.ASSISTANT, reply.text()));
        }
        if (reply.finished()) {
            return new DubheStepOutcome.Finished<>(new DubheAgentAnswer(transcriptText(), List.copyOf(usedSkills)));
        }
        return invoke(reply.call(), context);
    }

    public DubheAgentTranscript transcript() {
        return transcript;
    }

    List<DubheAgentEntry> entries() {
        return transcript.entries();
    }

    private DubheStepOutcome<DubheAgentAnswer> invoke(DubheSkillCall call, DubheStepContext step) {
        String canonical = canonical(call.arguments());
        transcript.append(new DubheAgentEntry(DubheAgentEntry.Kind.TOOL_CALL, call.skillId() + " " + canonical));

        Optional<DubheSkill> found = registry.find(call.skillId());
        if (found.isEmpty()) {
            transcript.append(new DubheAgentEntry(
                    DubheAgentEntry.Kind.TOOL_RESULT, "unknown skill: " + call.skillId()));
            return new DubheStepOutcome.Continue<>(call.skillId(), canonical);
        }

        DubheSkill skill = found.get();
        long timeout = Math.max(1L, Math.min(skill.spec().budget().timeoutMillis(), step.remainingMillis()));
        try {
            JsonObject result = skill.executor()
                    .execute(new DubheSkillContext(taskId, question, scope), call.arguments())
                    .get(timeout, TimeUnit.MILLISECONDS);
            transcript.append(new DubheAgentEntry(
                    DubheAgentEntry.Kind.TOOL_RESULT, result == null ? "{}" : result.toString()));
            usedSkills.add(skill.id());
        } catch (TimeoutException timeoutException) {
            transcript.append(new DubheAgentEntry(
                    DubheAgentEntry.Kind.TOOL_RESULT, "timeout after " + timeout + "ms"));
        } catch (ExecutionException executionException) {
            transcript.append(new DubheAgentEntry(
                    DubheAgentEntry.Kind.TOOL_RESULT, "failed: " + messageOf(executionException.getCause())));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new DubheStepOutcome.Aborted<>(
                    DubheTurnCancellation.expired("skill wait interrupted"), DubheRunExpiry.NONE);
        }
        return new DubheStepOutcome.Continue<>(skill.id(), canonical);
    }

    private List<com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec> directory() {
        return registry.all().stream().map(DubheSkill::spec).toList();
    }

    private String transcriptText() {
        StringBuilder text = new StringBuilder();
        for (DubheAgentEntry entry : transcript.entries()) {
            if (entry.kind() == DubheAgentEntry.Kind.ASSISTANT) {
                text.append(entry.text());
            }
        }
        return text.toString();
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