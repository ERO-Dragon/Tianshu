package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRequestHandler;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 委派骨架：立即返回 ticket、Complex 请求的形状、结论与失败都投递、5 秒复用与 taskId 幂等（§5 / §6）。
 *
 * <p><b>驱动纪律</b>（本类踩过的坑，写死在这里）：run 必须跑在<b>自己的线程</b>上。若在测试线程里内联跑
 * run，run 会阻塞等一个"只有测试线程返回后才可能回灌"的结果，于是每个用例白等 COMPLEX 的 120s 挂钟——
 * 测试结论全变成 {@code ABORT_TIMEOUT}，整套 {@code check} 被拖到十分钟。内联不是"跑得快"，而是把事件
 * 通路拆掉（§12.8 同款约束）。</p>
 *
 * <p>因此这里的时序是：{@code delegate} 在测试线程返回 → 测试线程把 run 交给一条守护线程 → 等请求出现 →
 * 测试线程回灌结果 → {@link com.rheinmetal.tianshu.function.auxilium.core.agent.AXStepSignal} 用事件唤醒
 * 循环线程。没有 50ms 轮询累积，也没有任何真实 120s 等待：{@code await} 的 5s 只是失败保护，正常路径是
 * 毫秒级。</p>
 */
class AXDelegateServiceTest {

    @Test
    void delegateReturnsImmediatelyAndTheRunSendsOneComplexRequest() {
        Fixture fixture = new Fixture();

        AXDelegateTicket ticket = fixture.service.delegate(AXScope.unknown(), "session", "铁砧怎么修复工具");

        assertNotNull(ticket);
        assertEquals(1, fixture.pendingRuns.size(), "ticket 登记后立即返回，检索交给后台 run（§5.1 ⑤）");
        assertTrue(fixture.requests.isEmpty(), "返回时不得阻塞在任何检索 I/O 上");
        assertTrue(fixture.deliveries.get(0).isAck(), "先给回执（§6.2 两条不同资源键）");
        assertEquals(AXDelegateOutcomeKind.ACK_SPEECH_KEY, fixture.deliveries.get(0).utteranceKey());

        fixture.driveRun();
        fixture.awaitRequest();

        LLMPromptRequestPayload request = fixture.lastRequest();
        assertEquals(AXDelegateService.DELEGATE_LANE, request.lane(), "Complex 跑在 TASK 车道（§3.2）");
        assertTrue(request.toolsJson().isEmpty(), "本片不含真实工具定义");
        assertEquals(AXDelegateConclusionParser.MAX_CONCLUSION_TOKENS, request.maxTokens());
        assertTrue(request.requestId().startsWith("ax.delegate."));
        assertEquals(ticket.taskId(), fixture.taskOf(request.requestId()));

        fixture.answer("{\"conclusion\":\"铁砧可以修复工具。\"}");
        fixture.awaitTerminalDelivery(ticket);
    }

    @Test
    void okConclusionIsAppendedAsAnEventAndDeliveredOnce() {
        Fixture fixture = new Fixture();
        AXDelegateTicket ticket = fixture.service.delegate(AXScope.unknown(), "session", "铁砧怎么修复工具");
        fixture.driveRun();
        fixture.awaitRequest();

        fixture.answer("{\"conclusion\":\"铁砧可以修复工具。\",\"sources\":[\"wiki\"]}");
        fixture.awaitTerminalDelivery(ticket);

        assertEquals(AXRunTermination.COMPLETED, ticket.termination());
        assertEquals(1, fixture.ledger.size(), "每个 taskId 恰好一条终态（A4）");
        AXDelegateEventLedger.Entry entry = fixture.ledger.find(ticket.taskId());
        assertEquals(AXDelegateOutcomeKind.OK, entry.kind());
        assertEquals("铁砧可以修复工具。", entry.text());
        assertEquals(List.of("wiki"), entry.sources());
        assertTrue(entry.appendedToHistory());

        assertEquals(1, fixture.appends.size(), "② <event> 追加必达");
        assertEquals("铁砧可以修复工具。", fixture.appends.get(0).text(), "历史条目就是生产者渲染的那句话，AX 不再加前缀");

        AXDelegateDelivery last = fixture.lastDelivery();
        assertEquals(AXDelegateOutcomeKind.OK, last.kind());
        assertEquals("铁砧可以修复工具。", last.text());
        assertNull(last.utteranceKey(), "结论播报读正文本身，不再二次改写（§6.2）");
    }

    @Test
    void failedResultIsStillDeliveredWithItsFallbackKey() {
        Fixture fixture = new Fixture();
        AXDelegateTicket ticket = fixture.service.delegate(AXScope.unknown(), "session", "铁砧怎么修复工具");
        fixture.driveRun();
        fixture.awaitRequest();

        fixture.failResult("LLM_TIMEOUT", "boom");
        fixture.awaitTerminalDelivery(ticket);

        assertEquals(AXRunTermination.FAILED, ticket.termination());
        assertEquals(1, fixture.ledger.size(), "失败也必须登记一条（A4）");
        assertEquals(AXDelegateOutcomeKind.FAILED, fixture.ledger.find(ticket.taskId()).kind());
        assertEquals(1, fixture.appends.size(), "失败也必须写 <event>，否则玩家永远等不到回应");
        assertEquals("delegate_failed", fixture.appends.get(0).text(), "正文为空的终态回落 kind 代码，历史不留空行");

        AXDelegateDelivery last = fixture.lastDelivery();
        assertEquals("ax.runtime.delegate.failed", last.utteranceKey());
        assertTrue(last.text().isEmpty());
    }

    @Test
    void replayInsideTheWindowDoesNotStartASecondRun() {
        Fixture fixture = new Fixture();
        AXDelegateTicket first = fixture.service.delegate(AXScope.unknown(), "session", "铁砧怎么修复工具");
        AXDelegateTicket second = fixture.service.delegate(AXScope.unknown(), "session", " 铁砧怎么修复工具？ ");

        assertEquals(first.taskId(), second.taskId());
        assertEquals(1, fixture.submittedRuns.get(), "5 秒窗口内复用既有 ticket，不新建 Run（A7）");
        assertEquals(1, fixture.pendingRuns.size(), "只有第一次真的把 run 交给了提交器");
        assertEquals(2, fixture.deliveries.size(), "复用也补一条回执，玩家感知一致（§5.2 步骤 ④）");
        assertTrue(fixture.deliveries.get(1).isAck());
    }

    @Test
    void replayOfATerminalTicketRepublichesTheConclusionWithoutASecondEvent() {
        Fixture fixture = new Fixture();
        AXDelegateTicket ticket = fixture.service.delegate(AXScope.unknown(), "session", "铁砧怎么修复工具");
        fixture.driveRun();
        fixture.awaitRequest();
        fixture.answer("{\"conclusion\":\"铁砧可以修复工具。\"}");
        fixture.awaitTerminalDelivery(ticket);

        AXDelegateTicket again = fixture.service.delegate(AXScope.unknown(), "session", "铁砧怎么修复工具");

        assertEquals(ticket.taskId(), again.taskId());
        assertEquals(1, fixture.submittedRuns.get(), "复用不得再起一个 Run");
        assertEquals(1, fixture.ledger.size(), "复用不得写第二条终态（A4）");
        assertEquals(1, fixture.appends.size(), "复用也不重复追加 <event>");
        assertEquals("铁砧可以修复工具。", fixture.lastDelivery().text());
    }

    @Test
    void outsideTheWindowStartsASecondRun() {
        Fixture fixture = new Fixture();
        AXDelegateTicket first = fixture.service.delegate(AXScope.unknown(), "session", "铁砧怎么修复工具");
        fixture.clock.addAndGet(AXDelegateRegistry.DEFAULT_REPLAY_WINDOW_MILLIS + 1L);

        AXDelegateTicket second = fixture.service.delegate(AXScope.unknown(), "session", "铁砧怎么修复工具");

        assertNotEquals(first.taskId(), second.taskId());
        assertEquals(2, fixture.submittedRuns.get(), "窗口外一律新建——没有键级去重（§5.4）");
    }

    @Test
    void rejectedLaneWritesTheFailedTerminalInsteadOfLeavingTheTicketPending() {
        Fixture fixture = new Fixture();
        fixture.rejectRuns = true;

        AXDelegateTicket ticket = fixture.service.delegate(AXScope.unknown(), "session", "铁砧怎么修复工具");

        assertEquals(AXRunTermination.FAILED, ticket.termination());
        assertEquals(1, fixture.ledger.size(), "车道拒绝也要写终态（§5.2 步骤 ④ / §5.3）");
        assertEquals(AXDelegateOutcomeKind.FAILED, fixture.ledger.find(ticket.taskId()).kind());
        assertEquals("ax.runtime.delegate.failed", fixture.lastDelivery().utteranceKey());
    }

    // ---------------------------------------------------------------- fixture

    private static final class Fixture {
        /** 提交器收到的 run（由测试显式驱动，绝不内联跑）。 */
        private final List<Runnable> pendingRuns = new ArrayList<>();
        private final AtomicInteger submittedRuns = new AtomicInteger();
        private final List<LLMPromptRequestPayload> requests = new CopyOnWriteArrayList<>();
        private final List<AXLlmRequestHandler> handlers = new CopyOnWriteArrayList<>();
        private final List<AXEventAppend> appends = new CopyOnWriteArrayList<>();
        private final List<AXDelegateDelivery> deliveries = new CopyOnWriteArrayList<>();
        private final AXDelegateEventLedger ledger = new AXDelegateEventLedger();
        private final AtomicLong clock = new AtomicLong(1_000L);
        private volatile boolean rejectRuns;
        private final AXDelegateService service;

        private Fixture() {
            this.service = new AXDelegateService(
                    (payload, handler) -> {
                        // Handler first: a reader that sees the request must also see its handler.
                        handlers.add(handler);
                        requests.add(payload);
                    },
                    new AXDelegateRegistry(),
                    null,
                    new AXDelegateConclusionParser(String::length),
                    ledger,
                    append -> appends.add(append),
                    (taskId, task) -> {
                        if (rejectRuns) {
                            throw new RejectedExecutionException("lane saturated");
                        }
                        submittedRuns.incrementAndGet();
                        pendingRuns.add(task);
                    },
                    deliveries::add,
                    LogSink.NOOP,
                    clock::get
            );
        }

        /** 把待跑的 run 交给一条守护线程：run 必须与测试线程并行，否则事件通路永远等不到回灌。 */
        private void driveRun() {
            List<Runnable> pending = new ArrayList<>(pendingRuns);
            pendingRuns.clear();
            if (pending.isEmpty()) {
                org.junit.jupiter.api.Assertions.fail("no run was submitted");
            }
            Thread runner = new Thread(() -> pending.forEach(Runnable::run), "ax-delegate-test-run");
            runner.setDaemon(true);
            runner.start();
        }

        private void awaitRequest() {
            await(() -> !requests.isEmpty(), "the Complex request to leave the run");
        }

        /**
         * 等到终态那一句真的交到播报出口。
         *
         * <p>{@code deliver} 的顺序是「写终态 → ① 登记 → ② 追加 → ③ 交句柄」，所以只等 {@code ticket.isTerminal()}
         * 会在 ③ 之前抢跑：那时 {@code lastDelivery()} 还是先前的回执，断言就会误判成
         * "失败没出声"。登记与追加都发生在 publish 之前，因此等句柄到达即可覆盖它们。</p>
         */
        private AXDelegateDelivery awaitTerminalDelivery(AXDelegateTicket ticket) {
            await(() -> terminalDeliveryOf(ticket) != null, "the terminal delivery");
            return terminalDeliveryOf(ticket);
        }

        private AXDelegateDelivery terminalDeliveryOf(AXDelegateTicket ticket) {
            for (AXDelegateDelivery delivery : deliveries) {
                if (!delivery.isAck() && ticket.taskId().equals(delivery.taskId())) {
                    return delivery;
                }
            }
            return null;
        }

        private void answer(String modelText) {
            int index = requests.size() - 1;
            handlers.get(index).onResult(
                    LLMPromptResultPayload.completed(requests.get(index).requestId(), modelText));
        }

        private void failResult(String code, String message) {
            int index = requests.size() - 1;
            handlers.get(index).onResult(
                    LLMPromptResultPayload.failed(requests.get(index).requestId(), code, message));
        }

        private LLMPromptRequestPayload lastRequest() {
            return requests.get(requests.size() - 1);
        }

        private AXDelegateDelivery lastDelivery() {
            return deliveries.get(deliveries.size() - 1);
        }

        private String taskOf(String requestId) {
            return requestId.substring("ax.delegate.".length());
        }
    }

    /** 失败保护，不是等待策略：正常路径由事件唤醒，毫秒级返回。 */
    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
        fail("timed out waiting for " + what);
    }
}
