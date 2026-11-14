package com.rheinmetal.tianshu.api.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRateLimiterTest {

    @Test
    void aPlayerBucketRunsOutButTrustedCallersPass() {
        ToolRateLimiter limiter = new ToolRateLimiter();
        ToolId id = ToolId.of("test", "read");
        ToolCaller player = ToolCaller.player("p1", false);

        for (int call = 0; call < (int) ToolRateLimiter.READ_CALLS_PER_SECOND; call++) {
            assertTrue(limiter.allow(id, ToolCategory.SERVER_READ, player));
        }
        assertFalse(limiter.allow(id, ToolCategory.SERVER_READ, player));

        assertTrue(limiter.allow(id, ToolCategory.SERVER_READ, ToolCaller.agent(false)));
        assertTrue(limiter.allow(id, ToolCategory.SERVER_READ, ToolCaller.mod("m")));
    }

    @Test
    void writesGetTheirOwnStrictBucketAndCallersAreIndependent() {
        ToolRateLimiter limiter = new ToolRateLimiter();
        ToolId write = ToolId.of("test", "write");
        ToolCaller player = ToolCaller.player("p1", false);

        for (int call = 0; call < (int) ToolRateLimiter.WRITE_CALLS_PER_SECOND; call++) {
            assertTrue(limiter.allow(write, ToolCategory.SERVER_WRITE, player));
        }
        assertFalse(limiter.allow(write, ToolCategory.SERVER_WRITE, player));
        assertTrue(limiter.allow(write, ToolCategory.SERVER_WRITE, ToolCaller.player("p2", false)));

        limiter.clearCaller("p2");
        assertTrue(limiter.allow(write, ToolCategory.SERVER_WRITE, ToolCaller.player("p2", false)));
    }
}
