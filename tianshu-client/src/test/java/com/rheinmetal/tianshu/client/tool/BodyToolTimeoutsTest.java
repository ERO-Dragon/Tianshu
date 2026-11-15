package com.rheinmetal.tianshu.client.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Long body operations must not fall back to the 2 s category default, or the channel aborts them. */
class BodyToolTimeoutsTest {

    @Test
    void longOperationsCarryTheirOwnTimeout() {
        assertEquals(35_000L, new BodyMoveTool().policy().timeoutMillis());
        assertEquals(70_000L, new BodyMineTool().policy().timeoutMillis());
        assertEquals(25_000L, new ItemUseTool().policy().timeoutMillis());
        assertEquals(125_000L, new BodyCollectTool().policy().timeoutMillis());
        assertEquals(305_000L, new BodyFollowTool().policy().timeoutMillis());
    }

    @Test
    void oneShotOperationsKeepTheDefault() {
        assertEquals(2_000L, new BodyLookTool().policy().timeoutMillis());
        assertEquals(2_000L, new BodyPickupTool().policy().timeoutMillis());
        assertEquals(2_000L, new BodyScanTool().policy().timeoutMillis());
    }
}