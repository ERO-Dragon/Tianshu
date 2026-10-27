package com.rheinmetal.tianshu.function.auxilium.activity;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 辅助界面上那半栏"日志"：最近发生的几件事，给玩家看，不给工程师看。
 *
 * <p>它不是诊断日志的替代品。诊断有分类、有隐私级别、落到宿主；这里只有一句语言键加一条明细，
 * 目的是让玩家在设置里就能回答"刚才天枢干了什么"，而不是去翻文件。</p>
 *
 * <p>只在内存里，上限 {@value #MAX_ENTRIES} 条：日志是"这一局发生了什么"，重启之后重来一份才符合直觉；
 * 需要跨进程留下来的东西属于失败账本，不属于日志。</p>
 */
public final class AXActivityLog {
    public static final int MAX_ENTRIES = 64;
    /** 明细是机器码或计数，不是玩家文本：这里不做本地化，也不装长文本。 */
    public static final int MAX_DETAIL_CHARS = 64;

    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();

    /** 一条日志：{@code code} 是语言键后缀，{@code detail} 是可选的机器明细。 */
    public record Entry(String code, String detail, long atMillis) {
        public Entry {
            code = code == null || code.isBlank() ? "unknown" : code.trim();
            detail = detail == null ? "" : detail.trim();
            atMillis = atMillis <= 0L ? System.currentTimeMillis() : atMillis;
        }
    }

    public void add(String code, String detail) {
        Entry entry = new Entry(code, truncate(detail), System.currentTimeMillis());
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.pollLast();
        }
    }

    /** 最近的在前。 */
    public List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public void clear() {
        entries.clear();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= MAX_DETAIL_CHARS ? normalized : normalized.substring(0, MAX_DETAIL_CHARS);
    }
}