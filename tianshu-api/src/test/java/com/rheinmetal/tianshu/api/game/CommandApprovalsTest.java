package com.rheinmetal.tianshu.api.game;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approvals: one token, one command, one time, and only for the actor who asked.
 *
 * <p>Every case here is a way a "just add a command tool" implementation goes wrong: a token that outlives its use, a token
 * reused for a different command, a token that never expires, or a request that quietly runs without approval.</p>
 */
class CommandApprovalsTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final CommandApprovals approvals = CommandApprovals.with(Duration.ofMinutes(5), clock);

    @Test
    void aRequestWaitsAndRunsNothing() {
        String token = approvals.request("body-1", "gamemode creative");

        assertEquals(1, approvals.pendingCount());
        assertTrue(approvals.consume(token, "body-1", "gamemode creative").isEmpty(),
                "without an operator's approval nothing may run");
    }

    @Test
    void anApprovedTokenRunsExactlyOnce() {
        String token = approvals.request("body-1", "gamemode creative");
        assertTrue(approvals.approve(token));

        Optional<String> first = approvals.consume(token, "body-1", "gamemode creative");
        assertEquals(Optional.of("gamemode creative"), first);
        assertTrue(approvals.consume(token, "body-1", "gamemode creative").isEmpty(), "approval is one-shot");
    }

    @Test
    void aTokenIsBoundToItsCommand() {
        String token = approvals.request("body-1", "gamemode creative");
        approvals.approve(token);

        assertTrue(approvals.consume(token, "body-1", "give @s diamond 64").isEmpty(),
                "an approved token must not be carried to another command");
        assertTrue(approvals.consume(token, "body-1", "gamemode creative").isPresent(),
                "and a mismatched attempt must not have burned the original");
    }

    @Test
    void aTokenIsBoundToTheActorWhoAsked() {
        String token = approvals.request("body-1", "gamemode creative");
        approvals.approve(token);

        assertTrue(approvals.consume(token, "body-2", "gamemode creative").isEmpty());
    }

    @Test
    void aTokenExpires() {
        String token = approvals.request("body-1", "gamemode creative");
        approvals.approve(token);

        clock.advance(Duration.ofMinutes(6));

        assertTrue(approvals.consume(token, "body-1", "gamemode creative").isEmpty());
        assertEquals(0, approvals.pendingCount(), "expired requests are pruned, not kept forever");
    }

    @Test
    void askingTwiceForTheSameThingYieldsOneToken() {
        String first = approvals.request("body-1", "gamemode creative");
        String second = approvals.request("body-1", "gamemode creative");

        assertEquals(first, second, "the operator should approve one thing once");
        assertEquals(1, approvals.pendingCount());
    }

    @Test
    void differentCommandsGetDifferentTokens() {
        String first = approvals.request("body-1", "gamemode creative");
        String second = approvals.request("body-1", "time set day");

        assertNotEquals(first, second);
        assertEquals(2, approvals.pendingCount());
    }

    @Test
    void clearingDropsEveryApproval() {
        String token = approvals.request("body-1", "gamemode creative");
        approvals.approve(token);

        approvals.clear();

        assertTrue(approvals.consume(token, "body-1", "gamemode creative").isEmpty());
        assertEquals(0, approvals.pendingCount());
    }

    @Test
    void aTokenIsCaseInsensitiveWhenTypedByAHuman() {
        String token = approvals.request("body-1", "gamemode creative");
        assertTrue(approvals.approve(token.toLowerCase(java.util.Locale.ROOT)));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}