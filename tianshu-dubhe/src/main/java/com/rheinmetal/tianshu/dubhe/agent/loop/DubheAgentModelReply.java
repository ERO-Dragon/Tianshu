package com.rheinmetal.tianshu.dubhe.agent.loop;

import java.util.List;

/**
 * What one model step produced: words, one or more skill calls, or both.
 *
 * <p>A reply with neither is rejected — it would leave the loop with nothing to append and nothing to do, which
 * is exactly the "silent stall" the report contract exists to prevent.</p>
 */
public record DubheAgentModelReply(String text, List<DubheSkillCall> calls) {

    public DubheAgentModelReply {
        text = text == null ? "" : text.trim();
        calls = calls == null ? List.of() : List.copyOf(calls);
        if (text.isEmpty() && calls.isEmpty()) {
            throw new IllegalArgumentException("a model reply must either say something or call a skill");
        }
    }

    public static DubheAgentModelReply answer(String text) {
        return new DubheAgentModelReply(text, List.of());
    }

    public static DubheAgentModelReply calls(List<DubheSkillCall> calls) {
        return new DubheAgentModelReply("", calls);
    }

    public boolean finished() {
        return calls.isEmpty();
    }

    public boolean hasCalls() {
        return !calls.isEmpty();
    }
}