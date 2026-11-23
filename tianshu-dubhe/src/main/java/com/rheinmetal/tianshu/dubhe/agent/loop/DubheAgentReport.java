package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the agent hands back for one task (G52).
 *
 * <p>Three fields, no enum: the state is derived from which of them is present. The prompt asks for brevity; the
 * only hard rule here is a token ceiling that exists to stop a runaway answer from flooding speech and history,
 * not to shape normal answers.</p>
 *
 * @param result    what was accomplished; self-contained and checkable
 * @param block     why it could not be done (a human sentence), or blank
 * @param need      a decision the player must make, or blank
 * @param truncated whether the ceiling cut the text; a truncation is a model failure worth logging
 */
public record DubheAgentReport(String result, String block, String need, boolean truncated) {

    /** Ceiling for the whole report; normal answers are far below it. */
    public static final int MAX_TOKENS = 400;

    public DubheAgentReport {
        result = result == null ? "" : result.trim();
        block = block == null ? "" : block.trim();
        need = need == null ? "" : need.trim();
        if (result.isEmpty() && block.isEmpty() && need.isEmpty()) {
            throw new IllegalArgumentException("a report must say something: result, block or need");
        }
    }

    public DubheAgentReport(String result, String block, String need) {
        this(result, block, need, false);
    }

    public boolean hasResult() {
        return !result.isEmpty();
    }

    public boolean hasBlock() {
        return !block.isEmpty();
    }

    /**
     * The one line simple speaks and appends to history: the result, or the block reason when nothing was done,
     * with the pending decision appended when there is one.
     */
    public String speech() {
        StringBuilder line = new StringBuilder(hasResult() ? result : block);
        if (!need.isEmpty()) {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(need);
        }
        return line.toString();
    }

    /** Applies the ceiling per field; the flag records that a model failure had to be cut. */
    public DubheAgentReport clamped() {
        String boundedResult = clamp(result);
        String boundedBlock = clamp(block);
        String boundedNeed = clamp(need);
        boolean cut = !boundedResult.equals(result) || !boundedBlock.equals(block) || !boundedNeed.equals(need);
        return cut ? new DubheAgentReport(boundedResult, boundedBlock, boundedNeed, true) : this;
    }

    /**
     * Parses the final answer. A model may wrap the JSON in prose or fences; the first object is taken, and a
     * payload with none of the three keys fails instead of becoming an empty success.
     */
    public static DubheAgentReport fromJson(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no report object in the answer");
        }
        JsonObject body = JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
        return new DubheAgentReport(string(body, "result"), string(body, "block"), string(body, "need"));
    }

    private static String string(JsonObject body, String key) {
        JsonElement value = body.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String clamp(String text) {
        if (DubheAgentTokenEstimator.estimate(text) <= MAX_TOKENS) {
            return text;
        }
        // Simple head trim: the report is short by design, so a cut here already means the model misbehaved.
        int end = text.length();
        while (end > 0 && DubheAgentTokenEstimator.estimate(text.substring(0, end)) > MAX_TOKENS) {
            end -= Math.max(1, end / 20);
        }
        return text.substring(0, end).trim();
    }
}