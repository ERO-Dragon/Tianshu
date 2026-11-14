package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.function.auxilium.activity.AXActivityLog;
import com.rheinmetal.tianshu.function.auxilium.failure.AXFailureCause;

import java.util.Objects;

/**
 * 把一轮的结局写进界面上那半栏日志。
 *
 * <p>住在这个包里，是因为轮次的身份（属于谁、哪一句、哪一轮）只有包内的 {@link AXTurnExecution} 有；
 * 日志自己只留一条码和一条明细，不留玩家原话。</p>
 *
 * <p>它不决定任何事：回滚由轮次在"有没有答出内容"的那一刻自己判，账本只管记忆维护。
 * 观察者只记录，这让三条规则互不牵连。</p>
 */
public final class AXTurnActivityRecorder implements AXTurnStatusPublisher.FailureObserver {
    private final AXActivityLog log;

    public AXTurnActivityRecorder(AXActivityLog log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public void onTurnFailed(AXTurnExecution execution, AXFailureCause cause, String reasonCode) {
        log.add("turn_failed", cause == null ? "" : cause.code());
    }

    @Override
    public void onTurnInterrupted(AXTurnExecution execution) {
        log.add("turn_interrupted", "");
    }

    @Override
    public void onTurnResolved(AXTurnExecution execution) {
        log.add("turn_completed", "");
    }
}