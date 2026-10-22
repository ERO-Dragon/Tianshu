package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委派注册表：5 秒复用窗口（§5.4）与 taskId 幂等（§6.3 / A4）是<b>两件不同的事</b>，不能互相替代。
 */
class AXDelegateRegistryTest {

    private static final long WINDOW = AXDelegateRegistry.DEFAULT_REPLAY_WINDOW_MILLIS;

    @Test
    void normalizeFoldsWidthWhitespacePunctuationAndCase() {
        assertEquals("helloworld", AXDelegateRegistry.normalize("  Ｈｅｌｌｏ　 World！ "));
        assertEquals("暮色森林怎么进", AXDelegateRegistry.normalize("暮色森林怎么进？"));
        assertEquals(AXDelegateRegistry.normalize("ABC"), AXDelegateRegistry.normalize("ａｂｃ。"));
    }

    @Test
    void replayKeyIsStableForTheSameNormalizedQuery() {
        assertEquals(AXDelegateRegistry.replayKey("铁砧怎么用"),
                AXDelegateRegistry.replayKey(" 铁砧怎么用？ "));
        assertNotEquals(AXDelegateRegistry.replayKey("铁砧怎么用"),
                AXDelegateRegistry.replayKey("附魔台怎么用"));
    }

    @Test
    void sameQueryInsideTheWindowReusesTheTicket() {
        AXDelegateRegistry registry = new AXDelegateRegistry(WINDOW);

        AXDelegateRegistry.Reservation first = registry.open(AXScope.unknown(), "session", "铁砧怎么用", 1_000L);
        AXDelegateRegistry.Reservation second = registry.open(AXScope.unknown(), "session", "铁砧怎么用？", 1_000L + WINDOW - 1);

        assertTrue(first.created());
        assertFalse(second.created(), "窗口内的同一句话不得新建 Run（A7）");
        assertSame(first.ticket(), second.ticket());
    }

    @Test
    void sameQueryOutsideTheWindowCreatesANewTicket() {
        AXDelegateRegistry registry = new AXDelegateRegistry(WINDOW);

        AXDelegateRegistry.Reservation first = registry.open(AXScope.unknown(), "session", "铁砧怎么用", 1_000L);
        AXDelegateRegistry.Reservation second = registry.open(AXScope.unknown(), "session", "铁砧怎么用", 1_000L + WINDOW + 1);

        assertTrue(second.created(), "窗口外一律新建——v2 起没有键级去重");
        assertNotEquals(first.ticket().taskId(), second.ticket().taskId());
    }

    @Test
    void differentQueryAlwaysCreatesANewTicket() {
        AXDelegateRegistry registry = new AXDelegateRegistry(WINDOW);

        assertTrue(registry.open(AXScope.unknown(), "session", "铁砧怎么用", 1_000L).created());
        assertTrue(registry.open(AXScope.unknown(), "session", "附魔台怎么用", 1_000L).created());
    }

    @Test
    void reuseIsPerSession() {
        AXDelegateRegistry registry = new AXDelegateRegistry(WINDOW);

        assertTrue(registry.open(AXScope.unknown(), "session-a", "铁砧怎么用", 1_000L).created());
        assertTrue(registry.open(AXScope.unknown(), "session-b", "铁砧怎么用", 1_000L).created(),
                "归属隔离：跨会话不可读（§5.3）");
    }

    @Test
    void terminalIsWrittenExactlyOnce() {
        AXDelegateRegistry registry = new AXDelegateRegistry(WINDOW);
        AXDelegateTicket ticket = registry.open(AXScope.unknown(), "session", "铁砧怎么用", 1_000L).ticket();

        assertTrue(ticket.markRunning());
        assertFalse(ticket.markRunning(), "PENDING 只能转 RUNNING 一次");
        assertTrue(ticket.terminate(AXRunTermination.COMPLETED, "铁砧可以修复工具。", java.util.List.of("wiki")));
        assertFalse(ticket.terminate(AXRunTermination.FAILED, "", java.util.List.of()),
                "同一 taskId 只允许一条终态（A4）");
        assertEquals(AXRunTermination.COMPLETED, ticket.termination());
        assertEquals("铁砧可以修复工具。", ticket.conclusionText());
        assertTrue(ticket.isTerminal());
    }

    @Test
    void retainedTicketsAreBoundedPerSession() {
        AXDelegateRegistry registry = new AXDelegateRegistry(WINDOW);
        for (int index = 0; index < AXDelegateRegistry.MAX_RETAINED_TICKETS_PER_SESSION + 4; index++) {
            AXDelegateTicket ticket = registry.open(AXScope.unknown(), "session", "问题" + index, 1_000L + index).ticket();
            ticket.markRunning();
            ticket.terminate(AXRunTermination.COMPLETED, "结论", java.util.List.of());
        }

        assertEquals(AXDelegateRegistry.MAX_RETAINED_TICKETS_PER_SESSION, registry.retainedCount("session"));
    }

    @Test
    void clearSessionDropsEverything() {
        AXDelegateRegistry registry = new AXDelegateRegistry(WINDOW);
        registry.open(AXScope.unknown(), "session", "铁砧怎么用", 1_000L);

        registry.clearSession("session");

        assertEquals(0, registry.retainedCount("session"));
    }
}
