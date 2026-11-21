package com.rheinmetal.tianshu.lab.acceptance;

/**
 * One acceptance case's verdict: what it checked, whether the property held, and the evidence either way.
 *
 * <p>A case that cannot run must say so ({@link Status#SKIP}) instead of passing quietly. The whole point of this
 * bench is that "green" later becomes evidence someone will believe, so a check that silently does nothing is the
 * one outcome that is not allowed.</p>
 */
public record AcceptanceOutcome(String id, String title, Status status, String detail) {

    public enum Status {
        PASS,
        FAIL,
        SKIP
    }

    public static AcceptanceOutcome pass(String id, String title, String detail) {
        return new AcceptanceOutcome(id, title, Status.PASS, detail);
    }

    public static AcceptanceOutcome fail(String id, String title, String detail) {
        return new AcceptanceOutcome(id, title, Status.FAIL, detail);
    }

    public static AcceptanceOutcome skip(String id, String title, String reason) {
        return new AcceptanceOutcome(id, title, Status.SKIP, reason);
    }

    public boolean failed() {
        return status == Status.FAIL;
    }
}