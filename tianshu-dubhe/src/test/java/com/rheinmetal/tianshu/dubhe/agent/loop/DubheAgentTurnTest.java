package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillBudget;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillRegistry;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheAgentTurnTest {

    private static final DubheScope SCOPE =
            new DubheScope("player-1", "overworld", "玩家", DubheScopeKind.LOCAL_WORLD, true);

    private static DubheSkillSpec spec(String id, long timeoutMillis) {
        return DubheSkillSpec.builder(id, "测试技能 " + id)
                .budget(DubheSkillBudget.of(timeoutMillis, 2))
                .build();
    }

    private static DubheStepOutcome<DubheAgentModelReply> answer(String text) {
        return new DubheStepOutcome.Finished<>(DubheAgentModelReply.answer(text));
    }

    private static DubheStepOutcome<DubheAgentModelReply> call(String id, String skill) {
        return new DubheStepOutcome.Finished<>(DubheAgentModelReply.calls(
                List.of(new DubheSkillCall(id, skill, new JsonObject()))));
    }

    @SafeVarargs
    private static DubheAgentModel scripted(DubheStepOutcome<DubheAgentModelReply>... steps) {
        Deque<DubheStepOutcome<DubheAgentModelReply>> queue = new ArrayDeque<>(List.of(steps));
        return request -> {
            if (queue.isEmpty()) {
                throw new IllegalStateException("scripted model ran out of replies");
            }
            return queue.removeFirst();
        };
    }

    private static DubheAgentTurn turn(DubheSkillRegistry registry, DubheAgentModel model) {
        return new DubheAgentTurn("task-1", "暮色森林怎么进", SCOPE, registry,
                DubheAgentContextBudget.forWindow(32_768), null, model);
    }

    private static DubheRunOutcome<DubheAgentAnswer> run(DubheAgentTurn turn) {
        return new DubheAgentLoop(DubheRunPolicy.simple()).run(turn, null);
    }

    @Test
    void withoutAnySkillTheAgentAnswersOnItsOwn() {
        DubheRunOutcome<DubheAgentAnswer> outcome = run(turn(new DubheSkillRegistry(), scripted(answer("我直接答"))));

        assertEquals(DubheRunTermination.COMPLETED, outcome.termination());
        assertEquals("我直接答", outcome.result().text());
        assertEquals(List.of(), outcome.result().usedSkills());
    }

    @Test
    void aCalledSkillFeedsItsResultIntoTheNextStep() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(spec("echo", 1_000L), (context, args) -> {
            JsonObject result = new JsonObject();
            result.addProperty("fact", "铁的熔点 1538 度");
            return CompletableFuture.completedFuture(result);
        });

        List<DubheAgentRequest> seen = new ArrayList<>();
        DubheAgentModel scripted = scripted(call("c1", "echo"), answer("结论"));
        DubheAgentModel recording = request -> {
            seen.add(request);
            return scripted.next(request);
        };

        DubheRunOutcome<DubheAgentAnswer> outcome = run(turn(registry, recording));

        assertEquals(DubheRunTermination.COMPLETED, outcome.termination());
        assertEquals(List.of("echo"), outcome.result().usedSkills());
        assertEquals(List.of("echo"), seen.get(0).directory().stream().map(DubheSkillSpec::id).toList());
        assertTrue(seen.get(1).transcript().stream()
                .anyMatch(entry -> entry.text().contains("铁的熔点")), "the result reached the next request");
        assertTrue(seen.get(1).transcript().stream()
                .anyMatch(entry -> entry.kind() == DubheAgentEntry.Kind.TOOL_RESULT
                        && entry.toolCallId().equals("c1")), "the tool message answers the call id");
    }

    @Test
    void anUnknownSkillBecomesAReadableFactInsteadOfACrash() {
        DubheAgentTurn turn = turn(new DubheSkillRegistry(),
                scripted(call("c1", "nope"), answer("换个办法")));

        DubheRunOutcome<DubheAgentAnswer> outcome = run(turn);

        assertEquals(DubheRunTermination.COMPLETED, outcome.termination());
        assertTrue(turn.entries().stream()
                .anyMatch(entry -> entry.text().contains("unknown skill: nope")));
        assertEquals(List.of(), outcome.result().usedSkills());
    }

    @Test
    void aSkillTimeoutIsReportedToTheModelNotThrown() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(spec("slow", 30L), (context, args) -> new CompletableFuture<>());
        DubheAgentTurn turn = turn(registry, scripted(call("c1", "slow"), answer("没查到")));

        DubheRunOutcome<DubheAgentAnswer> outcome = run(turn);

        assertEquals(DubheRunTermination.COMPLETED, outcome.termination());
        assertTrue(turn.entries().stream().anyMatch(entry -> entry.text().startsWith("timeout after")));
        assertEquals(List.of(), outcome.result().usedSkills());
    }

    @Test
    void aThrownSkillBecomesAFailedFact() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(spec("broken", 1_000L),
                (context, args) -> CompletableFuture.failedFuture(new IllegalStateException("boom")));
        DubheAgentTurn turn = turn(registry, scripted(call("c1", "broken"), answer("失败了")));

        DubheRunOutcome<DubheAgentAnswer> outcome = run(turn);

        assertEquals(DubheRunTermination.COMPLETED, outcome.termination());
        assertTrue(turn.entries().stream().anyMatch(entry -> entry.text().contains("failed: boom")));
    }

    @Test
    void theSameCallRepeatedIsStoppedByNoProgressDetection() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(spec("echo", 1_000L),
                (context, args) -> CompletableFuture.completedFuture(new JsonObject()));

        DubheAgentModel alwaysSame = request -> call("c1", "echo");
        DubheAgentTurn turn = new DubheAgentTurn("task-1", "问题", SCOPE, registry,
                DubheAgentContextBudget.forWindow(32_768), null, alwaysSame);

        DubheRunOutcome<DubheAgentAnswer> outcome =
                new DubheAgentLoop(DubheRunPolicy.complex()).run(turn, null);

        assertEquals(DubheRunTermination.ABORT_NO_PROGRESS, outcome.termination());
    }

    @Test
    void aLongTaskCompactsBeforeAskingTheModelAgain() {
        DubheSkillRegistry registry = new DubheSkillRegistry();
        registry.register(spec("bulk", 1_000L), (context, args) -> {
            JsonObject result = new JsonObject();
            result.addProperty("data", "中".repeat(5_000));
            return CompletableFuture.completedFuture(result);
        });
        AtomicInteger compactions = new AtomicInteger();
        DubheAgentCompactor compactor = (goal, previous, evicted) -> {
            compactions.incrementAndGet();
            return new DubheAgentDigest(goal, List.of(), List.of("fact"), List.of(), List.of(), "next");
        };
        DubheAgentTurn turn = new DubheAgentTurn("task-1", "问题", SCOPE, registry,
                DubheAgentContextBudget.forWindow(16_384), compactor, scripted(
                        call("c1", "bulk"),
                        call("c2", "bulk"),
                        answer("收尾")));

        DubheRunOutcome<DubheAgentAnswer> outcome = run(turn);

        assertEquals(DubheRunTermination.COMPLETED, outcome.termination());
        assertTrue(compactions.get() >= 1, "compaction ran before the next request");
        assertTrue(turn.transcript().entries().stream()
                .anyMatch(entry -> entry.kind() == DubheAgentEntry.Kind.DIGEST));
        assertEquals(List.of("bulk"), outcome.result().usedSkills());
    }
}