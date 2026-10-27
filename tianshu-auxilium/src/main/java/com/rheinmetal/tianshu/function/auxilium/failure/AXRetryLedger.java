package com.rheinmetal.tianshu.function.auxilium.failure;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 失败账本：这个世界还没办成、玩家可以再点一次的后台工作。
 *
 * <p>一个世界同时最多一条：记忆维护是世界内单线程的后台任务，接不了两件；重复失败替换记录，
 * 成功就撤账。所以这里没有列表、没有分页、没有排序——那些都是"有很多条"才需要的东西。</p>
 *
 * <p>落盘可跨进程存活：关掉游戏，重进这个世界，那条没办成的维护还在，玩家还能再点一次。读盘发生在
 * 这个世界第一次被触碰时；不先读就写，第一条新记录会把上一局留下的账本整体覆盖掉——那正好是崩溃之后
 * 最需要的那一份。</p>
 */
public final class AXRetryLedger {
    private final AXRetryLedgerStore store;
    private final ConcurrentMap<String, AXRetryEntry> entriesByWorld = new ConcurrentHashMap<>();
    private final Set<String> restoredWorlds = ConcurrentHashMap.newKeySet();

    public AXRetryLedger(AXRetryLedgerStore store) {
        this.store = store;
    }

    /** 记下一件没办成的事。同一世界已有记录时替换它，重试次数取较大值。 */
    public synchronized void record(AXScope scope, AXRetryEntry entry) {
        if (!writable(scope) || entry == null) {
            return;
        }
        restoreIfNeeded(scope);
        AXRetryEntry existing = entriesByWorld.get(scope.worldId());
        AXRetryEntry stored = existing != null && existing.attempts() > entry.attempts()
                ? new AXRetryEntry(entry.id(), entry.cause(), entry.reasonCode(), entry.worldId(),
                        entry.createdAtMillis(), existing.attempts())
                : entry;
        persist(scope, stored);
    }

    /** 这个世界还没办成的事，最多一条。 */
    public List<AXRetryEntry> entries(AXScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        restoreIfNeeded(scope);
        AXRetryEntry entry = entriesByWorld.get(scope.worldId());
        return entry == null ? List.of() : List.of(entry);
    }

    /**
     * 取出来准备重试：次数加一后仍留在账本里。
     *
     * <p>重试成不成不是由这里宣布的：那件事自己的成功路径会来 {@link #resolve} 撤账。账本不猜结果。</p>
     */
    public synchronized Optional<AXRetryEntry> claim(AXScope scope) {
        if (!writable(scope)) {
            return Optional.empty();
        }
        restoreIfNeeded(scope);
        AXRetryEntry entry = entriesByWorld.get(scope.worldId());
        if (entry == null) {
            return Optional.empty();
        }
        AXRetryEntry claimed = entry.withAttempt();
        persist(scope, claimed);
        return Optional.of(claimed);
    }

    /** 办成了：撤账。 */
    public synchronized boolean resolve(AXScope scope) {
        if (!writable(scope)) {
            return false;
        }
        restoreIfNeeded(scope);
        boolean removed = entriesByWorld.remove(scope.worldId()) != null;
        if (removed) {
            write(scope, null);
        }
        return removed;
    }

    /** 玩家不再追究。 */
    public synchronized boolean dismiss(AXScope scope) {
        return resolve(scope);
    }

    private void restoreIfNeeded(AXScope scope) {
        if (!writable(scope)) {
            return;
        }
        if (!restoredWorlds.add(scope.worldId())) {
            return;
        }
        List<AXRetryEntry> restored = store == null ? List.of() : store.load(scope);
        for (AXRetryEntry entry : restored) {
            if (entry != null) {
                entriesByWorld.put(scope.worldId(), entry);
                break;
            }
        }
    }

    private void persist(AXScope scope, AXRetryEntry entry) {
        entriesByWorld.put(scope.worldId(), entry);
        write(scope, entry);
    }

    private void write(AXScope scope, AXRetryEntry entry) {
        if (store != null) {
            store.write(scope, entry == null ? List.of() : List.of(entry));
        }
    }

    private boolean writable(AXScope scope) {
        return scope != null && scope.writable();
    }
}