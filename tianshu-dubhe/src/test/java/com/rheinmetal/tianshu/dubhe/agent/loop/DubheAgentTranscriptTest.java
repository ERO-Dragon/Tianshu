package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.rheinmetal.tianshu.weaving.contract.LLMToolCallPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheAgentTranscriptTest {

    private static final DubheAgentContextBudget BUDGET = DubheAgentContextBudget.forWindow(32_768);

    private static String text(int length) {
        return "中".repeat(length);
    }

    private static DubheAgentEntry call(String id) {
        return DubheAgentEntry.assistant("", List.of(new LLMToolCallPayload(id, "skill", "{}")));
    }

    private static DubheAgentCompactor digestWriter() {
        return (goal, previous, evicted) -> new DubheAgentDigest(
                goal, List.of("evicted=" + evicted.size()), List.of("fact"), List.of(), List.of(), "next");
    }

    @Test
    void aToolResultIsClampedOnTheWayIn() {
        DubheAgentTranscript transcript = new DubheAgentTranscript(BUDGET, "问题");
        transcript.append(DubheAgentEntry.toolResult("call-1", text(20_000)));

        DubheAgentEntry stored = transcript.entries().get(transcript.entries().size() - 1);
        assertTrue(stored.tokens() <= BUDGET.toolResultTokens(), "clamped to " + stored.tokens());
        assertTrue(stored.text().contains("[clamped]"), "the marker shows the middle was folded");
    }

    @Test
    void theBriefAndTheDigestAreManagedByTheTranscript() {
        DubheAgentTranscript transcript = new DubheAgentTranscript(BUDGET, "问题");

        assertThrows(IllegalArgumentException.class,
                () -> transcript.append(new DubheAgentEntry(DubheAgentEntry.Kind.BRIEF, "别的简报")));
        assertThrows(IllegalArgumentException.class,
                () -> transcript.append(new DubheAgentEntry(DubheAgentEntry.Kind.DIGEST, "摘要")));
    }

    @Test
    void aToolResultMustNameTheCallItAnswers() {
        DubheAgentTranscript transcript = new DubheAgentTranscript(BUDGET, "问题");

        assertThrows(IllegalArgumentException.class,
                () -> transcript.append(new DubheAgentEntry(DubheAgentEntry.Kind.TOOL_RESULT, "结果")));
    }

    @Test
    void compactionDoesNothingWhileUnderTheLimit() {
        DubheAgentTranscript transcript = new DubheAgentTranscript(BUDGET, "问题");
        transcript.append(DubheAgentEntry.assistant("短句"));
        AtomicInteger calls = new AtomicInteger();

        DubheAgentDigest result = transcript.compact((goal, previous, evicted) -> {
            calls.incrementAndGet();
            return new DubheAgentDigest(goal, List.of(), List.of(), List.of(), List.of(), "");
        });

        assertNull(result);
        assertEquals(0, calls.get());
        assertEquals(0, transcript.compactions());
    }

    @Test
    void compactionReplacesTheOldestRoundsWithOneDigest() {
        DubheAgentTranscript transcript = new DubheAgentTranscript(BUDGET, "暮色森林怎么进");
        for (int round = 0; round < 6; round++) {
            transcript.append(call("call-" + round));
            transcript.append(DubheAgentEntry.toolResult("call-" + round, text(5_000)));
            transcript.append(DubheAgentEntry.assistant("step-" + round));
        }
        assertTrue(transcript.needsCompaction());

        DubheAgentDigest digest = transcript.compact(digestWriter());

        assertEquals("暮色森林怎么进", digest.goal());
        assertEquals(1, transcript.compactions());
        assertEquals(DubheAgentEntry.Kind.BRIEF, transcript.entries().get(0).kind());
        assertEquals(DubheAgentEntry.Kind.DIGEST, transcript.entries().get(1).kind());
        assertTrue(transcript.estimatedTokens() <= transcript.limitTokens());
        long keptRounds = transcript.entries().stream().filter(DubheAgentEntry::hasToolCalls).count();
        assertEquals(DubheAgentContextBudget.KEEP_RECENT_ROUNDS, keptRounds);
    }

    @Test
    void aSecondCompactionReceivesThePreviousDigest() {
        DubheAgentTranscript transcript = new DubheAgentTranscript(BUDGET, "问题");
        AtomicReference<DubheAgentDigest> seenPrevious = new AtomicReference<>();
        DubheAgentCompactor compactor = (goal, previous, evicted) -> {
            seenPrevious.set(previous);
            return new DubheAgentDigest(goal, List.of(), List.of(), List.of(), List.of(), "");
        };

        for (int round = 0; round < 6; round++) {
            transcript.append(call("call-" + round));
            transcript.append(DubheAgentEntry.toolResult("call-" + round, text(5_000)));
        }
        transcript.compact(compactor);
        assertNull(seenPrevious.get());

        for (int round = 6; round < 11; round++) {
            transcript.append(call("call-" + round));
            transcript.append(DubheAgentEntry.toolResult("call-" + round, text(5_000)));
        }
        transcript.compact(compactor);

        assertEquals(2, transcript.compactions());
        assertEquals("问题", seenPrevious.get().goal());
    }

    @Test
    void anOversizedDigestIsRejectedRatherThanSilentlyAccepted() {
        DubheAgentTranscript transcript = new DubheAgentTranscript(BUDGET, "问题");
        for (int round = 0; round < 6; round++) {
            transcript.append(call("call-" + round));
            transcript.append(DubheAgentEntry.toolResult("call-" + round, text(5_000)));
        }

        DubheAgentCompactor bloated = (goal, previous, evicted) -> new DubheAgentDigest(
                goal, List.of(), List.of(text(3_000)), List.of(), List.of(), "");

        assertThrows(IllegalStateException.class, () -> transcript.compact(bloated));
    }

    @Test
    void anOverLimitTranscriptWithoutEvictableRoundsIsReported() {
        DubheAgentTranscript transcript = new DubheAgentTranscript(
                DubheAgentContextBudget.forWindow(8_192), "问题");
        transcript.append(DubheAgentEntry.assistant(text(4_000)));

        assertThrows(IllegalStateException.class, () -> transcript.compact(digestWriter()));
    }
}