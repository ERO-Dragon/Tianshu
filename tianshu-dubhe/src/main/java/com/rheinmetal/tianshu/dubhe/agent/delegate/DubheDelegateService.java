package com.rheinmetal.tianshu.dubhe.agent.delegate;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentAnswer;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentCompactor;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentLoop;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentModel;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentReport;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentTranscript;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheAgentTurn;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunCancellation;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunExpiry;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunOutcome;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunPolicy;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheRunTermination;
import com.rheinmetal.tianshu.dubhe.agent.loop.DubheStepFailure;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillRegistry;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventAppend;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventHistorySink;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;

import java.util.List;
import java.util.function.Supplier;

/**
 * 异步委派骨架（设计 §5 / §6）：把主对话识别出的 {@code <delegate>} 标签变成一次 Complex run，并把结论送回。
 *
 * <p>本片<b>不含真实工具执行</b>：一步 = 提交一次模型请求 + 有界等待它的结论。循环仍是同一个
 * {@link DubheAgentLoop}，只是策略换成 {@link DubheRunPolicy#complex()}（25 步 / 120s / 无进展检测），
 * 因此"步数安全阀、挂钟、无进展"三条终止条件与主对话共用同一份实现（§3.1 / §3.2）。</p>
 *
 * <p>三个通道分开（§6.1）：① 事件线登记（{@link DubheDelegateEventLedger}，每个 {@code taskId} 恰好一条）
 * 与 ② {@code <event>} 追加（{@link DubheEventHistorySink}）<b>永远发生</b>；③ 出声只产出句柄
 * （{@link DubheDelegateDeliverySink}），按到达顺序 FIFO 交出——正在播报时是否排队归 TTS 层。</p>
 *
 * <p>实测顺序：终态 → ① 登记 → ② 追加为 {@code <event>}（失败也追加）→ ③ 产出播报句柄。
 * 后两步失败也不影响前一步（A4 不因播报失败而破）。</p>
 */
public final class DubheDelegateService {

    /** 暂停后最多允许续跑的次数；用尽即 EXPIRED，不再无限续命（G48）。 */
    public static final int MAX_RESUMES = 2;


    private final DubheDelegateRequestSender requestSender;
    private final DubheDelegateRegistry registry;
    private final DubheDelegatePromptRepository promptRepository;
    private final DubheAgentContextBudget agentBudget;
    private final DubheSkillRegistry skills;
    private final DubheAgentCompactor compactor;
    private final DubheDelegateEventLedger eventLedger;
    private final DubheEventHistorySink historySink;
    private final DubheDelegateRunSubmitter runSubmitter;
    private final DubheDelegateDeliverySink deliverySink;
    private final LogSink logSink;
    private final Supplier<Long> nowMillis;

    public DubheDelegateService(
            DubheDelegateRequestSender requestSender,
            DubheDelegateRegistry registry,
            DubheDelegatePromptRepository promptRepository,
            DubheAgentContextBudget agentBudget,
            DubheSkillRegistry skills,
            DubheAgentCompactor compactor,
            DubheDelegateEventLedger eventLedger,
            DubheEventHistorySink historySink,
            DubheDelegateRunSubmitter runSubmitter,
            DubheDelegateDeliverySink deliverySink,
            LogSink logSink,
            Supplier<Long> nowMillis
    ) {
        this.requestSender = requestSender;
        this.registry = registry == null ? new DubheDelegateRegistry() : registry;
        this.promptRepository = promptRepository;
        this.agentBudget = agentBudget == null ? DubheAgentContextBudget.forWindow(32_768) : agentBudget;
        this.skills = skills == null ? new DubheSkillRegistry() : skills;
        this.compactor = compactor;
        this.eventLedger = eventLedger == null ? new DubheDelegateEventLedger() : eventLedger;
        this.historySink = historySink;
        this.runSubmitter = runSubmitter;
        this.deliverySink = deliverySink == null ? DubheDelegateDeliverySink.NOOP : deliverySink;
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
    }

    public DubheDelegateEventLedger eventLedger() {
        return eventLedger;
    }

    public DubheDelegateRegistry registry() {
        return registry;
    }

    public DubheDelegateTicket delegate(DubheScope scope, String sessionId, String query) {
        long now = nowMillis.get();
        DubhePausedTask paused = registry.paused(sessionId, now);
        if (paused != null && DubheResumeIntent.accepts(query, resumeAcceptWords())) {
            return resume(paused, now);
        }
        if (paused != null) {
            // 玩家换了话题：旧暂停任务作废，避免下次一句"好"续到不相干的事上。
            registry.clearPaused(sessionId);
        }
        DubheDelegateRegistry.Reservation reservation = registry.open(scope, sessionId, query, now);
        DubheDelegateTicket ticket = reservation.ticket();
        if (!reservation.created()) {
            logSink.info("dubhe.delegate.replay_hit session=" + ticket.sessionId() + " task=" + ticket.taskId()
                    + " windowMs=" + registry.replayWindowMillis());
            republish(ticket);
            return ticket;
        }
        if (!ticket.markRunning()) {
            return ticket;
        }
        publishAck(ticket);
        try {
            runSubmitter.submit(ticket.taskId(), () -> runComplex(ticket, null));
        } catch (RuntimeException rejected) {
            logSink.warn("dubhe.delegate.rejected task=" + ticket.taskId() + " reason=" + messageOf(rejected));
            deliver(ticket, new DubheRunOutcome<>(
                    DubheRunTermination.FAILED, 0, 0L, null, null, rejected, DubheRunExpiry.NONE));
        }
        return ticket;
    }

    private List<String> resumeAcceptWords() {
        return promptRepository == null ? List.of() : promptRepository.resumeAcceptWords();
    }

    /** 续跑：新建一张票据并指向被暂停的那张；旧票据保持 PAUSED（G48）。 */
    private DubheDelegateTicket resume(DubhePausedTask paused, long now) {
        registry.clearPaused(paused.sessionId());
        DubheDelegateRegistry.Reservation reservation =
                registry.open(paused.scope(), paused.sessionId(), paused.question(), now, paused.taskId());
        DubheDelegateTicket ticket = reservation.ticket();
        if (!ticket.markRunning()) {
            return ticket;
        }
        publishAck(ticket);
        try {
            runSubmitter.submit(ticket.taskId(), () -> runComplex(ticket, paused));
        } catch (RuntimeException rejected) {
            logSink.warn("dubhe.delegate.resume_rejected task=" + ticket.taskId() + " reason=" + messageOf(rejected));
            deliver(ticket, new DubheRunOutcome<>(
                    DubheRunTermination.FAILED, 0, 0L, null, null, rejected, DubheRunExpiry.NONE));
        }
        return ticket;
    }

    /** Complex run 本体：同一个循环，策略 {@code complex()}；每一步都可能调用技能。 */
    private void runComplex(DubheDelegateTicket ticket, DubhePausedTask paused) {
        String notice = promptRepository == null ? "" : promptRepository.softBudgetNotice();
        DubheAgentModel model = new DubheDelegateAgentModel(requestSender, promptRepository);
        DubheAgentTurn turn = paused == null
                ? new DubheAgentTurn(
                        ticket.taskId(), ticket.query(), ticket.scope(), skills, agentBudget, compactor, model, notice)
                : DubheAgentTurn.resumed(
                        ticket.taskId(), ticket.query(), ticket.scope(), skills, agentBudget, compactor, model,
                        notice, paused.transcript(), paused.digest());
        DubheRunOutcome<DubheAgentAnswer> outcome = new DubheAgentLoop(DubheRunPolicy.complex())
                .run(turn, DubheRunCancellation.none());
        int usedResumes = paused == null ? 0 : paused.resumes();
        if (shouldPause(outcome.termination(), turn.transcript(), usedResumes)) {
            pause(ticket, turn, usedResumes);
            return;
        }
        deliver(ticket, outcome);
    }

    /** 预算用尽但还有进展：停在这里等玩家说继续；续跑次数用尽则不再暂停（G48）。 */
    static boolean shouldPause(
            DubheRunTermination termination, DubheAgentTranscript transcript, int usedResumes) {
        if (termination != DubheRunTermination.ABORT_MAX_STEPS
                && termination != DubheRunTermination.ABORT_TIMEOUT) {
            return false;
        }
        if (usedResumes >= MAX_RESUMES) {
            return false;
        }
        return transcript.compactions() > 0 || transcript.entries().size() > 2;
    }

    private void pause(DubheDelegateTicket ticket, DubheAgentTurn turn, int usedResumes) {
        registry.pause(new DubhePausedTask(
                ticket.taskId(),
                ticket.sessionId(),
                ticket.scope(),
                ticket.query(),
                turn.transcript().entries(),
                turn.transcript().digest(),
                usedResumes + 1,
                nowMillis.get()));
        publishTerminal(ticket, DubheDelegateOutcomeKind.PAUSED, DubheRunTermination.PAUSED, "");
    }

    /**
     * 投递终态：① 登记（恰好一条）→ ② {@code <event>} 追加 → ③ 产出播报句柄。
     *
     * <p>{@code terminate} 与 {@code register} 的双重 CAS 保证"同一 ticket 只登记一条"：晚到的第二个终态
     * （复用、车道拒绝与回调竞速）直接被丢弃，而不是写第二条（A4 / §6.3）。</p>
     *
     * <p>只用 block 的报告也按 OK 投递：那是"没查到"的人话，不是系统失败；系统失败由终态（超时/取消/异常）决定。</p>
     */
    private void deliver(DubheDelegateTicket ticket, DubheRunOutcome<DubheAgentAnswer> outcome) {
        DubheAgentReport report = outcome.result() == null ? null : outcome.result().report();
        if (report != null && report.truncated()) {
            logSink.warn("dubhe.delegate.report_truncated task=" + ticket.taskId());
        }
        DubheDelegateOutcomeKind kind;
        if (outcome.termination() != DubheRunTermination.COMPLETED) {
            boolean budget = outcome.termination() == DubheRunTermination.ABORT_MAX_STEPS
                    || outcome.termination() == DubheRunTermination.ABORT_TIMEOUT;
            kind = budget && !ticket.resumes().isEmpty()
                    ? DubheDelegateOutcomeKind.EXPIRED
                    : DubheDelegateOutcomeKind.of(outcome.termination());
        } else if (report != null && !report.speech().isBlank()) {
            kind = DubheDelegateOutcomeKind.OK;
        } else {
            kind = DubheDelegateOutcomeKind.FAILED;
        }
        String text = kind == DubheDelegateOutcomeKind.OK && report != null ? report.speech() : "";
        registry.clearPaused(ticket.sessionId());
        publishTerminal(ticket, kind, outcome.termination(), text);
    }

    private void publishTerminal(
            DubheDelegateTicket ticket,
            DubheDelegateOutcomeKind kind,
            DubheRunTermination termination,
            String text
    ) {
        if (!ticket.terminate(termination, text)) {
            return;
        }
        boolean appended = appendHistory(kind, text);
        boolean registered = eventLedger.register(new DubheDelegateEventLedger.Entry(
                ticket.taskId(),
                kind,
                termination,
                text,
                nowMillis.get(),
                appended
        ));
        if (!registered) {
            return;
        }
        logSink.info("dubhe.delegate.terminal task=" + ticket.taskId()
                + " kind=" + kind.code()
                + " status=" + termination.name()
                + " appended=" + appended);
        deliverySink.publish(new DubheDelegateDelivery(
                ticket.taskId(), kind, termination, text, kind.speechKey()));
    }



    /** ② 进 prompt：结论（或失败标记）作为一条 {@code <event>} 历史条目追加（A4 必达）。 */
    private boolean appendHistory(DubheDelegateOutcomeKind kind, String text) {
        if (historySink == null) {
            return false;
        }
        // 失败/超时等终态的正文为空（语义由 kind 承载）。历史条目不能因此变成空行，否则模型看不到任何东西；
        // 回落用 kind 代码——它是稳定标识符，不是自然语言，拼句仍然只在生产者侧发生。
        String historyText = text == null || text.isBlank() ? kind.code() : text;
        historySink.append(new DubheEventAppend.Single(kind.code(), historyText));
        return true;
    }

    /** ③ 回执句柄（§6.2 "我查一下"），立即返回路径上唯一该出声的东西。 */
    private void publishAck(DubheDelegateTicket ticket) {
        deliverySink.publish(DubheDelegateDelivery.ack(ticket.taskId()));
        logSink.info("dubhe.delegate.ack task=" + ticket.taskId()
                + " key=" + DubheDelegateOutcomeKind.ACK_SPEECH_KEY);
    }

    /** 5s 窗口内复用：不再登记事件，只把"玩家会感知到的东西"再给一次（§5.2 步骤 ④）。 */
    private void republish(DubheDelegateTicket ticket) {
        DubheDelegateOutcomeKind kind = DubheDelegateOutcomeKind.of(ticket.termination());
        if (!ticket.isTerminal()) {
            publishAck(ticket);
            return;
        }
        deliverySink.publish(new DubheDelegateDelivery(
                ticket.taskId(), kind, ticket.termination(), ticket.reportText(), kind.speechKey()));
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

}
