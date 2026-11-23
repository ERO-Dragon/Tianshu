package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentEntry;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DubhePausedTaskRegistryTest {

    private static DubhePausedTask paused(long createdAtMillis) {
        return new DubhePausedTask(
                "dubhe.delegate.old",
                "session",
                DubheScope.unknown(),
                "暮色森林怎么进",
                List.of(new DubheAgentEntry(DubheAgentEntry.Kind.BRIEF, "暮色森林怎么进")),
                null,
                1,
                createdAtMillis);
    }

    @Test
    void aPausedTaskIsKeptPerSessionUntilItExpires() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry();
        registry.pause(paused(1_000L));

        assertNotNull(registry.paused("session", 2_000L));
        assertNull(registry.paused("session", 1_000L + DubheDelegateRegistry.PAUSED_TTL_MILLIS + 1L));
        assertNull(registry.paused("session", 2_000L), "the expired task is dropped");
    }

    @Test
    void aNewerPauseReplacesTheOlderOne() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry();
        registry.pause(paused(1_000L));
        registry.pause(new DubhePausedTask(
                "dubhe.delegate.new", "session", DubheScope.unknown(), "另一个问题",
                List.of(new DubheAgentEntry(DubheAgentEntry.Kind.BRIEF, "另一个问题")), null, 0, 2_000L));

        assertEquals("dubhe.delegate.new", registry.paused("session", 3_000L).taskId());
    }

    @Test
    void clearingDropsTheTask() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry();
        registry.pause(paused(1_000L));
        registry.clearPaused("session");

        assertNull(registry.paused("session", 2_000L));
    }
}