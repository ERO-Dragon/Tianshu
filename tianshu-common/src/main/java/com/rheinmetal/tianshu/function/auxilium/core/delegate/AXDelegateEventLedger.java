package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.rheinmetal.tianshu.function.auxilium.core.agent.AXRunTermination;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件线（设计 §6.3 ① "登记"）：append-only，<b>同一 {@code taskId} 只允许一条终态</b>（A4）。
 *
 * <p>这条幂等主键是<b>投递</b>的幂等，不是"同一个问题问过几次"的去重——后者已由 §5.4 裁决删除（A9）。
 * 两者不得互相替代：重复提问会产生新 {@code taskId}，而同一个 {@code taskId} 的晚到终态一律被丢弃。</p>
 *
 * <p>纯内存态：写入持久化属存储片，本片只保证"恰好一条"这条判据在内存里成立。</p>
 */
public final class AXDelegateEventLedger {

    /** 一条已投递的委派终态（§6.3 的字段，去掉本片不产生的 {@code replayKeyHit}）。 */
    public record Entry(
            String taskId,
            AXDelegateOutcomeKind kind,
            AXRunTermination status,
            String text,
            List<String> sources,
            long createdAtMillis,
            boolean appendedToHistory,
            boolean spoken
    ) {
        public Entry {
            sources = sources == null ? List.of() : List.copyOf(sources);
            text = text == null ? "" : text;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * 登记一条终态。
     *
     * @return {@code true} 表示本次登记生效；{@code false} 表示该 {@code taskId} 已有终态（A4 幂等）
     */
    public synchronized boolean register(Entry entry) {
        if (entry == null || entry.taskId() == null || entry.taskId().isBlank()) {
            return false;
        }
        if (entries.containsKey(entry.taskId())) {
            return false;
        }
        entries.put(entry.taskId(), entry);
        return true;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    public synchronized Entry find(String taskId) {
        return taskId == null ? null : entries.get(taskId);
    }

    public synchronized int size() {
        return entries.size();
    }
}
