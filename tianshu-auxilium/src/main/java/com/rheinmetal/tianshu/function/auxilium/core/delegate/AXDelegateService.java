package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXAgentLoop;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunCancellation;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunExpiry;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunOutcome;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunPolicy;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXStepContext;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXStepFailure;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXStepOutcome;
import com.rheinmetal.tianshu.function.auxilium.core.agent.AXStepSignal;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmRequestHandler;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventHistorySink;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptStreamChunkPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 异步委派骨架（设计 §5 / §6）：把主对话识别出的 {@code <delegate>} 标签变成一次 Complex run，并把结论送回。
 *
 * <p>本片<b>不含真实工具执行</b>：一步 = 提交一次模型请求 + 有界等待它的结论。循环仍是同一个
 * {@link AXAgentLoop}，只是策略换成 {@link AXRunPolicy#complex()}（25 步 / 120s / 无进展检测），
 * 因此"步数安全阀、挂钟、无进展"三条终止条件与主对话共用同一份实现（§3.1 / §3.2）。</p>
 *
 * <p>三个通道分开（§6.1）：① 事件线登记（{@link AXDelegateEventLedger}，每个 {@code taskId} 恰好一条）
 * 与 ② {@code <event>} 追加（{@link AXEventHistorySink}）<b>永远发生</b>；③ 出声只产出句柄
 * （{@link AXDelegateDeliverySink}），按到达顺序 FIFO 交出——正在播报时是否排队归 TTS 层。</p>
 *
 * <p>实测顺序：终态 → ① 登记 → ② 追加为 {@code <event>}（失败也追加）→ ③ 产出播报句柄。
 * 后两步失败也不影响前一步（A4 不因播报失败而破）。</p>
 */
public final class AXDelegateService {

    /** 结论 JSON 的生成 token 上限与结论渲染上限同值（§5.6 约束 3）。 */
    public static final int DELEGATE_MAX_TOKENS = AXDelegateConclusionParser.MAX_CONCLUSION_TOKENS;

    /** 结论要的是"抄结论"不是"创作"，因此低温度（与压缩任务同为确定性输出）。 */
    public static final float DELEGATE_TEMPERATURE = 0.2F;

    /** {@code TASK} 车道优先级；取值归 TASK 车道优先级表（§3.8 本文不定数值），与后台压缩任务同档。 */
    public static final int DELEGATE_TASK_PRIORITY = 500;

    /** Complex run 的信封车道：§3.2 规定 Complex 跑在 {@code TASK}。 */
    public static final String DELEGATE_LANE = "TASK";

    private static final String REQUEST_ID_PREFIX = "ax.delegate.";
    private static final String REASON_RESULT_FAILED = "delegate.result_failed";
    private static final String REASON_NO_REPAIR = "delegate.no_repair_defined";

    private final AXDelegateRequestSender requestSender;
    private final AXDelegateRegistry registry;
    private final AXDelegatePromptRepository promptRepository;
    private final AXDelegateConclusionParser conclusionParser;
    private final AXDelegateEventLedger eventLedger;
    private final AXEventHistorySink historySink;
    private final AXDelegateRunSubmitter runSubmitter;
    private final AXDelegateDeliverySink deliverySink;
    private final LogSink logSink;
    private final Supplier<Long> nowMillis;

    public AXDelegateService(
            AXDelegateRequestSender requestSender,
            AXDelegateRegistry registry,
            AXDelegatePromptRepository promptRepository,
            AXDelegateConclusionParser conclusionParser,
            AXDelegateEventLedger eventLedger,
            AXEventHistorySink historySink,
            AXDelegateRunSubmitter runSubmitter,
            AXDelegateDeliverySink deliverySink,
            LogSink logSink,
            Supplier<Long> nowMillis
    ) {
        this.requestSender = requestSender;
        this.registry = registry == null ? new AXDelegateRegistry() : registry;
        this.promptRepository = promptRepository;
        this.conclusionParser = conclusionParser == null ? new AXDelegateConclusionParser() : conclusionParser;
        this.eventLedger = eventLedger == null ? new AXDelegateEventLedger() : eventLedger;
        this.historySink = historySink;
        this.runSubmitter = runSubmitter;
        this.deliverySink = deliverySink == null ? AXDelegateDeliverySink.NOOP : deliverySink;
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
    }

    public AXDelegateEventLedger eventLedger() {
        return eventLedger;
    }

    public AXDelegateRegistry registry() {
        return registry;
    }

    /**
     * 委派一次检索：立即返回 ticket，不等任何检索 I/O（§5.1 步骤 ③⑤）。
     *
     * <p>5s 窗口内命中同 {@code replayKey} 时不新建 Run（A7）：还活着的 ticket 只补一条回执，已终态的
     * ticket 直接把既有结论再投递一次——<b>都不再登记第二条事件</b>（A4）。</p>
     */
    public AXDelegateTicket delegate(AXScope scope, String sessionId, String query) {
        AXDelegateRegistry.Reservation reservation = registry.open(scope, sessionId, query, nowMillis.get());
        AXDelegateTicket ticket = reservation.ticket();
        if (!reservation.created()) {
            logSink.info("ax.delegate.replay_hit session=" + ticket.sessionId() + " task=" + ticket.taskId()
                    + " windowMs=" + registry.replayWindowMillis());
            republish(ticket);
            return ticket;
        }
        if (!ticket.markRunning()) {
            return ticket;
        }
        publishAck(ticket);
        try {
            runSubmitter.submit(ticket.taskId(), () -> runComplex(ticket));
        } catch (RuntimeException rejected) {
            // 车道拒绝/队列满：立即终态 FAILED(rejected)，仍按投递路径投一句（§5.2 步骤 ④）。
            logSink.warn("ax.delegate.rejected task=" + ticket.taskId() + " reason=" + messageOf(rejected));
            deliver(ticket, new AXRunOutcome<>(
                    AXRunTermination.FAILED, 0, 0L, null, null, rejected, AXRunExpiry.NONE));
        }
        return ticket;
    }

    /** Complex run 本体：同一个循环，策略 {@code complex()}。 */
    private void runComplex(AXDelegateTicket ticket) {
        AXStepSignal<AXDelegateConclusion> step = new AXStepSignal<>();
        AXAgentLoop loop = new AXAgentLoop(AXRunPolicy.complex());
        AXRunOutcome<AXDelegateConclusion> outcome = loop.run(
                context -> nextStep(ticket, step, new DelegateResultHandler(step), context),
                // 本片不接取消（§5.3 的本地取消属元能力片）：「只读」探针即可，循环仍按挂钟兜底。
                AXRunCancellation.none()
        );
        deliver(ticket, outcome);
    }

    private AXStepOutcome<AXDelegateConclusion> nextStep(
            AXDelegateTicket ticket,
            AXStepSignal<AXDelegateConclusion> step,
            AXLlmRequestHandler handler,
            AXStepContext context
    ) throws Exception {
        if (context.stepIndex() > 0) {
            // 单步契约：没有工具，也没有"输出不合契约就重问"的修复动作（§6 的裁剪已在本地完成），
            // 因此第二步没有可做的事。真走到这里说明契约变了，按内部错误收尾而不是空转。
            throw new AXStepFailure(REASON_NO_REPAIR);
        }
        requestSender.send(delegateRequest(ticket), handler);
        return step.await(context.remainingMillis(), context.cancellation());
    }

    /**
     * Complex 的请求：自包含的 system 规则 + 一句 query（§5.5 明确不复用主链前缀，因此这里没有五段装配）。
     */
    private LLMPromptRequestPayload delegateRequest(AXDelegateTicket ticket) {
        List<LLMPromptRequestPayload.MessageItemPayload> messages = new ArrayList<>();
        String system = promptRepository == null ? "" : promptRepository.delegateSystemPrompt();
        if (!system.isBlank()) {
            messages.add(LLMPromptRequestPayload.MessageItemPayload.system(system));
        }
        String instruction = promptRepository == null ? "" : promptRepository.delegateInstruction(ticket.query());
        messages.add(LLMPromptRequestPayload.MessageItemPayload.user(instruction.isBlank()
                ? ticket.query()
                : instruction));
        return new LLMPromptRequestPayload(
                REQUEST_ID_PREFIX + ticket.taskId(),
                DELEGATE_MAX_TOKENS,
                DELEGATE_TEMPERATURE,
                false,
                true,
                false,
                DELEGATE_LANE,
                DELEGATE_TASK_PRIORITY,
                true,
                List.of(LLMPromptRequestPayload.ChunkPayload.message(messages))
        );
    }

    /**
     * 投递终态：① 登记（恰好一条）→ ② {@code <event>} 追加 → ③ 产出播报句柄。
     *
     * <p>{@code terminate} 与 {@code register} 的双重 CAS 保证"同一 ticket 只登记一条"：晚到的第二个终态
     * （复用、车道拒绝与回调竞速）直接被丢弃，而不是写第二条（A4 / §6.3）。</p>
     */
    private void deliver(AXDelegateTicket ticket, AXRunOutcome<AXDelegateConclusion> outcome) {
        AXDelegateOutcomeKind kind = AXDelegateOutcomeKind.of(outcome.termination());
        AXDelegateConclusion conclusion = outcome.result();
        String text = kind == AXDelegateOutcomeKind.OK && conclusion != null ? conclusion.text() : "";
        List<String> sources = kind == AXDelegateOutcomeKind.OK && conclusion != null
                ? conclusion.sources()
                : List.of();
        if (!ticket.terminate(outcome.termination(), text, sources)) {
            return;
        }
        boolean appended = appendHistory(kind, text);
        boolean registered = eventLedger.register(new AXDelegateEventLedger.Entry(
                ticket.taskId(),
                kind,
                outcome.termination(),
                text,
                sources,
                nowMillis.get(),
                appended
        ));
        if (!registered) {
            return;
        }
        logSink.info("ax.delegate.terminal task=" + ticket.taskId()
                + " kind=" + kind.code()
                + " status=" + outcome.termination().name()
                + " appended=" + appended);
        deliverySink.publish(new AXDelegateDelivery(
                ticket.taskId(), kind, outcome.termination(), text, sources, kind.speechKey()));
    }

    /** ② 进 prompt：结论（或失败标记）作为一条 {@code <event>} 历史条目追加（A4 必达）。 */
    private boolean appendHistory(AXDelegateOutcomeKind kind, String text) {
        if (historySink == null) {
            return false;
        }
        // 失败/超时等终态的正文为空（语义由 kind 承载）。历史条目不能因此变成空行，否则模型看不到任何东西；
        // 回落用 kind 代码——它是稳定标识符，不是自然语言，拼句仍然只在生产者侧发生。
        String historyText = text == null || text.isBlank() ? kind.code() : text;
        historySink.append(new AXEventAppend.Single(kind.code(), historyText));
        return true;
    }

    /** ③ 回执句柄（§6.2 "我查一下"），立即返回路径上唯一该出声的东西。 */
    private void publishAck(AXDelegateTicket ticket) {
        deliverySink.publish(AXDelegateDelivery.ack(ticket.taskId()));
        logSink.info("ax.delegate.ack task=" + ticket.taskId()
                + " key=" + AXDelegateOutcomeKind.ACK_SPEECH_KEY);
    }

    /** 5s 窗口内复用：不再登记事件，只把"玩家会感知到的东西"再给一次（§5.2 步骤 ④）。 */
    private void republish(AXDelegateTicket ticket) {
        AXDelegateOutcomeKind kind = AXDelegateOutcomeKind.of(ticket.termination());
        if (!ticket.isTerminal()) {
            publishAck(ticket);
            return;
        }
        deliverySink.publish(new AXDelegateDelivery(
                ticket.taskId(), kind, ticket.termination(), ticket.conclusionText(),
                ticket.conclusionSources(), kind.speechKey()));
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /** 一次 Complex 步的回调：把模型结果转成步信号。 */
    private final class DelegateResultHandler implements AXLlmRequestHandler {
        private final AXStepSignal<AXDelegateConclusion> step;

        private DelegateResultHandler(AXStepSignal<AXDelegateConclusion> step) {
            this.step = step;
        }

        @Override
        public void onStreamChunk(LLMPromptStreamChunkPayload payload) {
            // 结论只取终态结果：流式片段不进投递（§5.6 只回传 conclusion）。
        }

        @Override
        public void onResult(LLMPromptResultPayload payload) {
            if (payload == null || !payload.isCompleted()) {
                step.complete(new AXStepOutcome.Rejected<>(new AXStepFailure(REASON_RESULT_FAILED)));
                return;
            }
            AXDelegateConclusion conclusion;
            try {
                conclusion = conclusionParser.parse(payload.text());
            } catch (RuntimeException unusable) {
                step.complete(new AXStepOutcome.Rejected<>(new AXStepFailure(
                        AXDelegateConclusionParser.REASON_MISSING, unusable)));
                return;
            }
            step.complete(new AXStepOutcome.Finished<>(conclusion));
        }

        @Override
        public void onCancelled(AXTurnCancellation cancellation) {
            step.complete(new AXStepOutcome.Aborted<>(
                    cancellation == null ? AXTurnCancellation.playerInterrupted("delegate cancelled") : cancellation,
                    AXRunExpiry.NONE));
        }

        @Override
        public void onCancellationResult(LLMPromptResultPayload payload) {
            step.complete(new AXStepOutcome.Rejected<>(new AXStepFailure(REASON_RESULT_FAILED)));
        }
    }
}
