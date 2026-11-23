package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * The structured progress digest written by compaction (G49).
 *
 * <p>It is rendered as JSON, not prose: the agent's language may change between tasks, and the fields exist so
 * "already ruled out" survives compaction — a prose summary loses that and the agent repeats the same lookup.
 * Field labels are therefore language-neutral.</p>
 *
 * @param goal     what the task is trying to answer
 * @param done     steps already taken
 * @param facts    findings worth keeping
 * @param ruledOut approaches already excluded, so they are not retried
 * @param open     questions still unanswered
 * @param next     the intended next step
 */
public record DubheAgentDigest(
        String goal,
        List<String> done,
        List<String> facts,
        List<String> ruledOut,
        List<String> open,
        String next
) {

    public DubheAgentDigest {
        goal = goal == null ? "" : goal.trim();
        done = done == null ? List.of() : List.copyOf(done);
        facts = facts == null ? List.of() : List.copyOf(facts);
        ruledOut = ruledOut == null ? List.of() : List.copyOf(ruledOut);
        open = open == null ? List.of() : List.copyOf(open);
        next = next == null ? "" : next.trim();
    }

    /** The JSON body; {@link DubheAgentTranscript} wraps it in its own tags. */
    public String toJson() {
        JsonObject body = new JsonObject();
        body.addProperty("goal", goal);
        addStrings(body, "done", done);
        addStrings(body, "facts", facts);
        addStrings(body, "ruledOut", ruledOut);
        addStrings(body, "open", open);
        body.addProperty("next", next);
        return body.toString();
    }

    public int tokens() {
        return DubheAgentTokenEstimator.estimate(toJson());
    }

    private static void addStrings(JsonObject body, String key, List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        body.add(key, array);
    }
}