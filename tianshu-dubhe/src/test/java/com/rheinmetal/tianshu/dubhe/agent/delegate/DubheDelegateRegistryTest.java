package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunTermination;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委派注册表：5 秒复用窗口（§5.4）与 taskId 幂等（§6.3 / A4）是<b>两件不同的事</b>，不能互相替代。
 */
class DubheDelegateRegistryTest {

    private static final long WINDOW = DubheDelegateRegistry.DEFAULT_REPLAY_WINDOW_MILLIS;

    @Test
    void normalizeFoldsWidthWhitespacePunctuationAndCase() {
        assertEquals("helloworld", DubheDelegateRegistry.normalize("  Ｈｅｌｌｏ　 World！ "));
        assertEquals("暮色森林怎么进", DubheDelegateRegistry.normalize("暮色森林怎么进？"));
        assertEquals(DubheDelegateRegistry.normalize("ABC"), DubheDelegateRegistry.normalize("ａｂｃ。"));
    }

    @Test
    void replayKeyIsStableForTheSameNormalizedQuery() {
        assertEquals(DubheDelegateRegistry.replayKey("铁砧怎么用"),
                DubheDelegateRegistry.replayKey(" 铁砧怎么用？ "));
        assertNotEquals(DubheDelegateRegistry.replayKey("铁砧怎么用"),
                DubheDelegateRegistry.replayKey("附魔台怎么用"));
    }

    @Test
    void sameQueryInsideTheWindowReusesTheTicket() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry(WINDOW);

        DubheDelegateRegistry.Reservation first = registry.open(DubheScope.unknown(), "session", "铁砧怎么用", 1_000L);
        DubheDelegateRegistry.Reservation second = registry.open(DubheScope.unknown(), "session", "铁砧怎么用？", 1_000L + WINDOW - 1);

        assertTrue(first.created());
        assertFalse(second.created(), "窗口内的同一句话不得新建 Run（A7）");
        assertSame(first.ticket(), second.ticket());
    }

    @Test
    void sameQueryOutsideTheWindowCreatesANewTicket() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry(WINDOW);

        DubheDelegateRegistry.Reservation first = registry.open(DubheScope.unknown(), "session", "铁砧怎么用", 1_000L);
        DubheDelegateRegistry.Reservation second = registry.open(DubheScope.unknown(), "session", "铁砧怎么用", 1_000L + WINDOW + 1);

        assertTrue(second.created(), "窗口外一律新建——v2 起没有键级去重");
        assertNotEquals(first.ticket().taskId(), second.ticket().taskId());
    }

    @Test
    void differentQueryAlwaysCreatesANewTicket() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry(WINDOW);

        assertTrue(registry.open(DubheScope.unknown(), "session", "铁砧怎么用", 1_000L).created());
        assertTrue(registry.open(DubheScope.unknown(), "session", "附魔台怎么用", 1_000L).created());
    }

    @Test
    void reuseIsPerSession() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry(WINDOW);

        assertTrue(registry.open(DubheScope.unknown(), "session-a", "铁砧怎么用", 1_000L).created());
        assertTrue(registry.open(DubheScope.unknown(), "session-b", "铁砧怎么用", 1_000L).created(),
                "归属隔离：跨会话不可读（§5.3）");
    }

    @Test
    void terminalIsWrittenExactlyOnce() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry(WINDOW);
        DubheDelegateTicket ticket = registry.open(DubheScope.unknown(), "session", "铁砧怎么用", 1_000L).ticket();

        assertTrue(ticket.markRunning());
        assertFalse(ticket.markRunning(), "PENDING 只能转 RUNNING 一次");
        assertTrue(ticket.terminate(DubheRunTermination.COMPLETED, "铁砧可以修复工具。"));
        assertFalse(ticket.terminate(DubheRunTermination.FAILED, ""),
                "同一 taskId 只允许一条终态（A4）");
        assertEquals(DubheRunTermination.COMPLETED, ticket.termination());
        assertEquals("铁砧可以修复工具。", ticket.reportText());
        assertTrue(ticket.isTerminal());
    }

    @Test
    void retainedTicketsAreBoundedPerSession() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry(WINDOW);
        for (int index = 0; index < DubheDelegateRegistry.MAX_RETAINED_TICKETS_PER_SESSION + 4; index++) {
            DubheDelegateTicket ticket = registry.open(DubheScope.unknown(), "session", "问题" + index, 1_000L + index).ticket();
            ticket.markRunning();
            ticket.terminate(DubheRunTermination.COMPLETED, "结论");
        }

        assertEquals(DubheDelegateRegistry.MAX_RETAINED_TICKETS_PER_SESSION, registry.retainedCount("session"));
    }

    @Test
    void clearSessionDropsEverything() {
        DubheDelegateRegistry registry = new DubheDelegateRegistry(WINDOW);
        registry.open(DubheScope.unknown(), "session", "铁砧怎么用", 1_000L);

        registry.clearSession("session");

        assertEquals(0, registry.retainedCount("session"));
    }
}
