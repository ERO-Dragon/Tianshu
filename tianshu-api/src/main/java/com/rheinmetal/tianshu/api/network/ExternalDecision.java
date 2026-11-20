package com.rheinmetal.tianshu.api.network;

/**
 * What the platform decided about one outbound request.
 *
 * <p>A refusal always carries a reason a person can read and a model can act on: "denied" alone leaves the caller
 * guessing whether the host was not on the list, the scheme was wrong, or the address was private.</p>
 *
 * @param allowed whether the request may proceed
 * @param reason  why, in one sentence; empty when allowed
 */
public record ExternalDecision(boolean allowed, String reason) {

    public ExternalDecision {
        reason = reason == null ? "" : reason.trim();
    }

    public static ExternalDecision allow() {
        return new ExternalDecision(true, "");
    }

    public static ExternalDecision deny(String reason) {
        return new ExternalDecision(false, reason);
    }
}