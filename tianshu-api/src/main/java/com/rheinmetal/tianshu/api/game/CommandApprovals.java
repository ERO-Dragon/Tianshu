package com.rheinmetal.tianshu.api.game;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The tokens that let one command run once, after a human approved it.
 *
 * <p>A token is bound to <em>who asked</em> and <em>what they asked for</em>, because a token that only says "approved"
 * can be carried to a different command. It is single-use and it expires: approval is for the thing that was asked,
 * not for a mood.</p>
 *
 * <p>The registry is process-wide with an explicit {@link #clear()}, like the tool registry: a session that stops must
 * not leave approvals behind for the next one.</p>
 */
public final class CommandApprovals {

    private static final CommandApprovals SHARED = new CommandApprovals(Duration.ofMinutes(5), Clock.systemUTC());
    private static final int MAX_PENDING = 32;

    private final Duration lifetime;
    private final Clock clock;
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    CommandApprovals(Duration lifetime, Clock clock) {
        this.lifetime = lifetime == null ? Duration.ofMinutes(5) : lifetime;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public static CommandApprovals shared() {
        return SHARED;
    }

    /** A test instance with its own clock, so expiry can be exercised without waiting. */
    public static CommandApprovals with(Duration lifetime, Clock clock) {
        return new CommandApprovals(lifetime, clock);
    }

    /**
     * Records a request and returns the token to show the operator.
     *
     * <p>The same actor asking for the same command gets the same token back rather than a second one: the operator
     * should approve one thing once, not a queue of duplicates.</p>
     */
    public synchronized String request(String actorId, String line) {
        prune();
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            Pending existing = entry.getValue();
            if (existing.actorId.equals(actorId) && existing.line.equals(line) && !existing.approved) {
                return entry.getKey();
            }
        }
        while (pending.size() >= MAX_PENDING) {
            String oldest = pending.keySet().iterator().next();
            pending.remove(oldest);
        }
        String token = newToken();
        pending.put(token, new Pending(actorId, line, clock.instant().plus(lifetime)));
        return token;
    }

    /** Marks a token approved; false when it is unknown or already expired. */
    public synchronized boolean approve(String token) {
        prune();
        Pending entry = pending.get(normalise(token));
        if (entry == null) {
            return false;
        }
        entry.approved = true;
        return true;
    }

    /**
     * Takes the approval, consuming it, when it matches this actor and this command.
     *
     * <p>Empty means "not approved, expired, used, or issued for something else" — and the caller must not be able to
     * tell which, or it becomes an oracle for guessing tokens.</p>
     */
    public synchronized Optional<String> consume(String token, String actorId, String line) {
        prune();
        String key = normalise(token);
        Pending entry = pending.get(key);
        if (entry == null || !entry.approved) {
            return Optional.empty();
        }
        if (!entry.actorId.equals(actorId) || !entry.line.equals(line)) {
            return Optional.empty();
        }
        pending.remove(key);
        return Optional.of(entry.line);
    }

    /** How many approvals are waiting; for diagnostics and the operator's own listing. */
    public synchronized int pendingCount() {
        prune();
        return pending.size();
    }

    /** The commands waiting for approval, as operator-facing lines. */
    public synchronized java.util.List<String> pendingLines() {
        prune();
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            Pending value = entry.getValue();
            lines.add(entry.getKey() + (value.approved ? " (approved)" : "") + " — " + value.actorId + ": " + value.line);
        }
        return java.util.List.copyOf(lines);
    }

    /** Drops every approval; a stopped session must not hand the next one pre-approved commands. */
    public synchronized void clear() {
        pending.clear();
    }

    private void prune() {
        Instant now = clock.instant();
        pending.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
    }

    private static String normalise(String token) {
        return token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static final class Pending {
        private final String actorId;
        private final String line;
        private final Instant expiresAt;
        private boolean approved;

        private Pending(String actorId, String line, Instant expiresAt) {
            this.actorId = actorId;
            this.line = line;
            this.expiresAt = expiresAt;
        }
    }
}