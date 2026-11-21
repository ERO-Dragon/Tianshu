package com.rheinmetal.tianshu.dubhe.shared.failure;

import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeProvider;

import java.util.List;
import java.util.Objects;

/**
 * 失败账本对外的唯一入口：设置页读它，重试也走它。
 *
 * <p>它自己不办事，只做三件编排：把"当前世界"翻译成账本的作用域、把那条记录交给记忆维护、把结果留给
 * 维护自己的成功路径去撤账。设置页因此不需要认识记忆维护，维护也不需要认识界面。</p>
 *
 * <p>没有执行者、或原因不允许重试时不提供重试：按钮点得动、事情办不成，比没有按钮更坏。参数错误、
 * 余额不足、上下文超限这些要改的是设置，不是再点一次。</p>
 */
public final class DubheRetryService {
    /** 一种后台工作的重试执行者。返回 {@code true} 表示已受理，{@code false} 表示当场没能开始。 */
    @FunctionalInterface
    public interface DubheRetryAction {
        boolean retry(DubheRetryEntry entry);
    }

    private final DubheRetryLedger ledger;
    private final DubheScopeProvider scopeProvider;
    private final DubheRetryAction action;

    public DubheRetryService(DubheRetryLedger ledger, DubheScopeProvider scopeProvider, DubheRetryAction action) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.scopeProvider = scopeProvider;
        this.action = action;
    }

    /** 当前世界还没办成的事，最多一条。没有世界时为空。 */
    public List<DubheRetryEntry> entries() {
        DubheScope scope = currentScope();
        return scope == null ? List.of() : ledger.entries(scope);
    }

    /** 这条现在能不能重试：有执行者，且原因允许再来一次。 */
    public boolean canRetry(DubheRetryEntry entry) {
        return entry != null && entry.retryable() && action != null;
    }

    /** 再办一次。返回是否已受理。 */
    public boolean retry() {
        DubheScope scope = currentScope();
        if (scope == null) {
            return false;
        }
        List<DubheRetryEntry> entries = ledger.entries(scope);
        if (entries.isEmpty() || !canRetry(entries.get(0))) {
            return false;
        }
        return ledger.claim(scope).map(action::retry).orElse(false);
    }

    /** 玩家不再追究这一条。 */
    public boolean dismiss() {
        DubheScope scope = currentScope();
        return scope != null && ledger.dismiss(scope);
    }

    private DubheScope currentScope() {
        DubheScope scope = scopeProvider == null ? null : scopeProvider.currentScope();
        return scope != null && scope.writable() ? scope : null;
    }
}