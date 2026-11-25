package com.rheinmetal.tianshu.api.game;

/**
 * What happened to a command request.
 *
 * <p>Three outcomes, and the middle one is the point: a request that needs approval neither ran nor failed — it is
 * waiting for a human, and it says so with the token that human has to approve. Folding that into "failed" would make
 * every refused request look like a broken one, and the caller would retry instead of asking.</p>
 */
public record CommandResult(Status status, String output, String message, String approvalToken) {

    /** How the request ended. */
    public enum Status {
        /** The command ran; {@link #output()} carries what it said. */
        EXECUTED,
        /** Nothing ran yet: an operator has to approve {@link #approvalToken()} first. */
        PENDING_APPROVAL,
        /** Refused: absent, expired or already used token, wrong actor, blank line, insufficient level. */
        REJECTED
    }

    public CommandResult {
        status = status == null ? Status.REJECTED : status;
        output = output == null ? "" : output;
        message = message == null ? "" : message;
        approvalToken = approvalToken == null ? "" : approvalToken;
    }

    public static CommandResult executed(String output) {
        return new CommandResult(Status.EXECUTED, output, "", "");
    }

    public static CommandResult pending(String token, String message) {
        return new CommandResult(Status.PENDING_APPROVAL, "", message, token);
    }

    public static CommandResult rejected(String message) {
        return new CommandResult(Status.REJECTED, "", message, "");
    }

    public boolean ran() {
        return status == Status.EXECUTED;
    }
}