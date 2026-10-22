package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;
import java.util.Objects;

/**
 * 一次委派的 ticket（设计 §5.3）。
 *
 * <p>状态机：{@code PENDING → RUNNING → 终态}。终态<b>只允许写一次</b>（§5.3 "终态不可变"、A4"恰好一条"），
 * 由 {@link #terminate(AXRunTermination, String, List)} 的 CAS 保证；重复写返回 {@code false} 而不是抛异常，
 * 因为投递路径上"晚到的第二个终态"是常态（复用命中、车道拒绝与回调竞速）。</p>
 *
 * <p>ticket 同时是 §6.3 事件线的 {@code taskId} 持有者——它是<b>投递</b>的幂等主键，与"同一个问题问过几次"
 * 无关（§5.4 A9）。</p>
 */
public final class AXDelegateTicket {

    /** ticket 生命周期状态（§5.3）。 */
    public enum State {
        PENDING,
        RUNNING,
        TERMINAL
    }

    private final String taskId;
    private final String replayKey;
    private final String sessionId;
    private final String query;
    private final AXScope scope;
    private final long createdAtMillis;

    private volatile State state = State.PENDING;
    private volatile AXRunTermination termination;
    private volatile String conclusionText = "";
    private volatile List<String> conclusionSources = List.of();

    AXDelegateTicket(String taskId, String replayKey, String sessionId, String query, AXScope scope, long createdAtMillis) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.replayKey = Objects.requireNonNull(replayKey, "replayKey");
        this.sessionId = sessionId == null ? "" : sessionId;
        this.query = Objects.requireNonNull(query, "query");
        this.scope = scope == null ? AXScope.unknown() : scope;
        this.createdAtMillis = createdAtMillis;
    }

    public String taskId() {
        return taskId;
    }

    public String replayKey() {
        return replayKey;
    }

    public String sessionId() {
        return sessionId;
    }

    public String query() {
        return query;
    }

    public AXScope scope() {
        return scope;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public State state() {
        return state;
    }

    public boolean isTerminal() {
        return state == State.TERMINAL;
    }

    /** 终态；未终态时为 {@code null}。 */
    public AXRunTermination termination() {
        return termination;
    }

    /** 已投递的结论正文；未终态或失败终态时为空串。 */
    public String conclusionText() {
        return conclusionText;
    }

    /** 已投递的来源短标识；未终态或失败终态时为空列表。 */
    public List<String> conclusionSources() {
        return conclusionSources;
    }

    /** {@code PENDING → RUNNING}；非 PENDING 时返回 {@code false}（说明已被别的路径推走）。 */
    synchronized boolean markRunning() {
        if (state != State.PENDING) {
            return false;
        }
        state = State.RUNNING;
        return true;
    }

    /**
     * 写终态：恰好一次。
     *
     * @return {@code true} 表示本次写入生效；{@code false} 表示已有终态（A4：同一 taskId 只登记一条）
     */
    synchronized boolean terminate(AXRunTermination termination, String conclusionText, List<String> conclusionSources) {
        if (termination == null || state == State.TERMINAL) {
            return false;
        }
        this.termination = termination;
        this.conclusionText = conclusionText == null ? "" : conclusionText;
        this.conclusionSources = conclusionSources == null ? List.of() : List.copyOf(conclusionSources);
        this.state = State.TERMINAL;
        return true;
    }
}
