package com.rheinmetal.tianshu.api.entity;

import java.util.Objects;

/** The result of an action that does not move the body. */
public record ActionResult(ActionOutcome outcome, String detail) {

    public ActionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
    }

    public static ActionResult done(String detail) {
        return new ActionResult(ActionOutcome.DONE, detail);
    }

    public static ActionResult rejected(String detail) {
        return new ActionResult(ActionOutcome.REJECTED, detail);
    }

    public static ActionResult failed(String detail) {
        return new ActionResult(ActionOutcome.FAILED, detail);
    }
}