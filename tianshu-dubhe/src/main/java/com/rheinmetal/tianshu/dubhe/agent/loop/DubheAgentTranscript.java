package com.rheinmetal.tianshu.dubhe.agent.loop;

import java.util.ArrayList;
import java.util.List;

/**
 * The agent task's transcript: brief, assistant turns with their tool calls, tool results, and one digest.
 *
 * <p>Three behaviours live here so the loop stays simple:</p>
 * <ul>
 *   <li><b>Clamp</b> — a single tool result is truncated to head + tail around the middle marker, so one noisy
 *       result cannot crowd out the task.</li>
 *   <li><b>Compact</b> — once the estimate passes the budget's compaction point, the oldest complete tool rounds
 *       are handed to a {@link DubheAgentCompactor} and replaced by one digest. The brief is never evicted, and the
 *       newest rounds stay verbatim.</li>
 *   <li><b>Bound</b> — a digest that exceeds its own ceiling is rejected instead of silently defeating compaction;
 *       the transcript also refuses to report success when it is still over the limit afterwards.</li>
 * </ul>
 *
 * <p>A round starts at an assistant entry that requests tool calls; its tool results follow. Compaction only cuts
 * whole rounds, so the replayed conversation stays valid for the provider.</p>
 */
public final class DubheAgentTranscript {

    private static final String CLAMP_MARKER = "\n…[clamped]…\n";

    private final DubheAgentContextBudget budget;
    private final String goal;
    private final List<DubheAgentEntry> entries = new ArrayList<>();

    private DubheAgentDigest digest;
    private int compactions;

    public DubheAgentTranscript(DubheAgentContextBudget budget, String goal) {
        if (budget == null) {
            throw new IllegalArgumentException("budget must not be null");
        }
        this.budget = budget;
        this.goal = goal == null ? "" : goal.trim();
        entries.add(new DubheAgentEntry(DubheAgentEntry.Kind.BRIEF, this.goal));
    }

    /** Appends one entry; tool results are clamped on the way in. */
    public synchronized void append(DubheAgentEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        if (entry.kind() == DubheAgentEntry.Kind.BRIEF || entry.kind() == DubheAgentEntry.Kind.DIGEST) {
            throw new IllegalArgumentException(
                    "the brief and the digest are managed by the transcript, not appended: " + entry.kind());
        }
        if (entry.kind() == DubheAgentEntry.Kind.TOOL_RESULT && entry.toolCallId().isBlank()) {
            throw new IllegalArgumentException("a tool result must name the call it answers");
        }
        String text = entry.kind() == DubheAgentEntry.Kind.TOOL_RESULT
                ? clamp(entry.text(), budget.toolResultTokens())
                : entry.text();
        entries.add(new DubheAgentEntry(entry.kind(), text, entry.toolCallId(), entry.toolCalls()));
    }

    public synchronized int estimatedTokens() {
        int total = 0;
        for (DubheAgentEntry entry : entries) {
            total += entry.tokens();
        }
        return total;
    }

    public synchronized boolean needsCompaction() {
        return estimatedTokens() > budget.compactAtTokens();
    }

    /**
     * Compacts the oldest complete tool rounds into a digest and reports it.
     *
     * @return the current digest, which is unchanged when compaction was not needed
     * @throws IllegalStateException when the compactor returns nothing, returns an oversized digest, or the
     *                               transcript is still over the limit afterwards
     */
    public synchronized DubheAgentDigest compact(DubheAgentCompactor compactor) {
        if (compactor == null) {
            throw new IllegalArgumentException("compactor must not be null");
        }
        int limit = budget.compactAtTokens();
        if (estimatedTokens() <= limit) {
            return digest;
        }

        int firstKept = keepBoundary();
        List<DubheAgentEntry> evicted = new ArrayList<>();
        for (int index = 1; index < firstKept; index++) {
            DubheAgentEntry entry = entries.get(index);
            if (entry.kind() == DubheAgentEntry.Kind.DIGEST) {
                continue;
            }
            evicted.add(entry);
        }
        if (evicted.isEmpty()) {
            throw new IllegalStateException(
                    "transcript is over its limit but holds no evictable rounds; keep the brief and digest small");
        }

        DubheAgentDigest next = compactor.compact(goal, digest, evicted);
        if (next == null) {
            throw new IllegalStateException("compactor returned no digest");
        }
        if (next.tokens() > budget.digestTokens()) {
            throw new IllegalStateException(
                    "digest is " + next.tokens() + " tokens, over the " + budget.digestTokens() + " ceiling");
        }

        List<DubheAgentEntry> kept = new ArrayList<>(entries.subList(firstKept, entries.size()));
        entries.clear();
        entries.add(new DubheAgentEntry(DubheAgentEntry.Kind.BRIEF, goal));
        entries.add(new DubheAgentEntry(DubheAgentEntry.Kind.DIGEST, render(next)));
        entries.addAll(kept);
        digest = next;
        compactions++;

        if (estimatedTokens() > limit) {
            throw new IllegalStateException(
                    "after compaction the transcript is still " + estimatedTokens() + " > " + limit
                            + "; the digest or the recent rounds are too large");
        }
        return digest;
    }

    /** Immutable view, in request order (brief first, digest second). */
    public synchronized List<DubheAgentEntry> entries() {
        return List.copyOf(entries);
    }

    public synchronized DubheAgentDigest digest() {
        return digest;
    }

    public synchronized int compactions() {
        return compactions;
    }

    public synchronized int limitTokens() {
        return budget.compactAtTokens();
    }

    /**
     * Index of the first kept entry: everything before it is evicted.
     *
     * <p>A round begins at an assistant entry that requests tool calls; keeping the newest complete rounds keeps
     * every replayed assistant/tool pair together.</p>
     */
    private int keepBoundary() {
        int roundsKept = 0;
        int firstKeptRound = -1;
        for (int index = entries.size() - 1; index >= 1; index--) {
            DubheAgentEntry entry = entries.get(index);
            if (!entry.hasToolCalls()) {
                continue;
            }
            roundsKept++;
            if (roundsKept == DubheAgentContextBudget.KEEP_RECENT_ROUNDS) {
                firstKeptRound = index;
            } else if (roundsKept > DubheAgentContextBudget.KEEP_RECENT_ROUNDS) {
                return firstKeptRound;
            }
        }
        if (firstKeptRound > 0) {
            return firstKeptRound;
        }
        return Math.max(1, entries.size() - 2);
    }

    private static String render(DubheAgentDigest value) {
        return "<progress>" + value.toJson() + "</progress>";
    }

    /**
     * Keeps the head and the tail within {@code maxTokens}; the middle becomes a marker.
     */
    static String clamp(String text, int maxTokens) {
        if (text == null || text.isEmpty() || DubheAgentTokenEstimator.estimate(text) <= maxTokens) {
            return text == null ? "" : text;
        }
        int markerTokens = DubheAgentTokenEstimator.estimate(CLAMP_MARKER);
        int sideTokens = Math.max(1, (maxTokens - markerTokens) / 2);

        int headEnd = 0;
        double used = 0.0;
        while (headEnd < text.length()) {
            int codePoint = text.codePointAt(headEnd);
            double weight = DubheAgentTokenEstimator.weightOf(codePoint);
            if (used + weight > sideTokens) {
                break;
            }
            used += weight;
            headEnd += Character.charCount(codePoint);
        }

        int tailStart = text.length();
        used = 0.0;
        while (tailStart > headEnd) {
            int codePoint = text.codePointBefore(tailStart);
            double weight = DubheAgentTokenEstimator.weightOf(codePoint);
            if (used + weight > sideTokens) {
                break;
            }
            used += weight;
            tailStart -= Character.charCount(codePoint);
        }

        return text.substring(0, headEnd) + CLAMP_MARKER + text.substring(tailStart);
    }
}