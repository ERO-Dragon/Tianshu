package com.rheinmetal.tianshu.dubhe.agent.loop;

/**
 * What one model step produced: words, a skill call, or both.
 *
 * <p>A reply with neither is rejected — it would leave the loop with nothing to append and nothing to do, which
 * is exactly the "silent stall" the report contract exists to prevent.</p>
 *
 * @param text the agent's own words for this step, may be blank when it only calls a skill
 * @param call the skill call, {@code null} when this step is the final answer
 */
public record DubheAgentModelReply(String text, DubheSkillCall call) {

    public DubheAgentModelReply {
        text = text == null ? "" : text.trim();
        if (text.isEmpty() && call == null) {
            throw new IllegalArgumentException("a model reply must either say something or call a skill");
        }
    }

    public static DubheAgentModelReply answer(String text) {
        return new DubheAgentModelReply(text, null);
    }

    public static DubheAgentModelReply call(DubheSkillCall call) {
        return new DubheAgentModelReply("", call);
    }

    public boolean finished() {
        return call == null;
    }
}