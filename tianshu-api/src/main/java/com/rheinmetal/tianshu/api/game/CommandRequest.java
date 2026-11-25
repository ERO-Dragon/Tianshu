package com.rheinmetal.tianshu.api.game;

/**
 * One command to run, as the actor, written the way it would be typed without the leading slash.
 *
 * <p>No target and no permission argument: the target is the actor itself and the permission comes from the server's own
 * setting. Anything that lets a caller name a different target or a higher level turns a scoped capability into a shell.</p>
 */
public record CommandRequest(String line, String approval) {

    public CommandRequest {
        line = line == null ? "" : line.trim();
        approval = approval == null ? "" : approval.trim();
        if (line.startsWith("/")) {
            // Typed the way a player types it: accepting the slash would mean two spellings of the same command and one
            // of them untested.
            line = line.substring(1).trim();
        }
    }

    /** A request for approval — nothing runs yet. */
    public static CommandRequest pending(String line) {
        return new CommandRequest(line, "");
    }

    /** The same request, carrying the one-shot token an operator approved. */
    public CommandRequest withApproval(String token) {
        return new CommandRequest(line, token);
    }

    public boolean hasApproval() {
        return !approval.isBlank();
    }
}