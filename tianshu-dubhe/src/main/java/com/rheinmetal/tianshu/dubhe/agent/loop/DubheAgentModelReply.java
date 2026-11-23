package com.rheinmetal.tianshu.dubhe.agent.loop;

import java.util.List;

/**
 * What one model step produced: words, skill calls, or the final report.
 *
 * <p>A step is either "still working" (it calls skills) or "finished" (it carries a report); the two are never
 * mixed, because a report produced alongside calls would make the run end before the calls were executed.</p>
 */
public record DubheAgentModelReply(String text, List<DubheSkillCall> calls, DubheAgentReport report) {

    public DubheAgentModelReply {
        text = text == null ? "" : text.trim();
        calls = calls == null ? List.of() : List.copyOf(calls);
        if (calls.isEmpty() && report == null) {
            throw new IllegalArgumentException("a finished reply must carry a report");
        }
        if (!calls.isEmpty() && report != null) {
            throw new IllegalArgumentException("a reply that calls skills cannot also be the final report");
        }
    }

    public static DubheAgentModelReply calls(List<DubheSkillCall> calls) {
        return new DubheAgentModelReply("", calls, null);
    }

    public static DubheAgentModelReply working(String text, List<DubheSkillCall> calls) {
        return new DubheAgentModelReply(text, calls, null);
    }

    public static DubheAgentModelReply finished(DubheAgentReport report) {
        return new DubheAgentModelReply("", List.of(), report);
    }

    public boolean finished() {
        return calls.isEmpty();
    }

    public boolean hasCalls() {
        return !calls.isEmpty();
    }
}