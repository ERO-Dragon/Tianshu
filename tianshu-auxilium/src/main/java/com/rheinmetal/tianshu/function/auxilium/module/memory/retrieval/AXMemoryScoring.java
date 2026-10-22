package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval;

import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;

/**
 * 记忆检索的三因子打分（记忆设计 §6.1/§6.2/§6.3.4 的实现口径）。
 *
 * <pre>
 *   score = relevance × recency × importanceFactor        // ∈ [0, 1]
 *   recency = RECENCY_FLOOR + RECENCY_HEADROOM × 2^(−ageHours / (HALF_LIFE_HOURS × S))
 *   S       = clamp(SUPPORT_BASE + min(SUPPORT_BONUS_CAP, SUPPORT_PER_TURN_REF × (turnRefs−1)), 1, SUPPORT_MAX)
 *   importanceFactor = clamp(importance/10, IMPORTANCE_FLOOR, 1)
 * </pre>
 *
 * <p><b>为什么乘法</b>：三个条件必须同时成立。加法会让"任一项极高"就进候选（一条 relevance 0.9、
 * importance 0.1、recency 0.05 的噪声事件也能得到 0.35 分），乘法不会。</p>
 *
 * <p><b>为什么每个因子都有地板</b>：乘法下任一项接近 0 就把整条压到 0，会出现"全部候选挤在 1e-4
 * 量级、分档阈值全部失效"。地板保证极旧/极弱但真实的事件仍能落进 Cold。</p>
 *
 * <p><b>S 用 {@code turnRefs.size()} 而不是召回次数</b>（§6.2）：召回计数是读路径回写，会让同一份
 * 历史在不同回放里给出不同分数，并与"importance 落库后只读"的边界冲突；而 {@code turnRefs} 越多
 * 本就意味着同一事实在被压缩的那段里被反复提到（间隔效应的静态代理），且它已经随事件落库，
 * 零额外存储、重启即可恢复。</p>
 *
 * <p>全部数值都是具名常量，来源写在每个常量上；本类不读配置、不持有状态，纯函数。</p>
 */
public final class AXMemoryScoring {
    /** 衰减地板（§6.1）：与 03 的遗忘淘汰线 {@code R < 0.05} 同源同值，极旧事件仍保留 5%。 */
    public static final double RECENCY_FLOOR = 0.05D;

    /** 高于地板的那部分衰减幅度：{@code recency = FLOOR + HEADROOM × 2^(−age/H)}。 */
    public static final double RECENCY_HEADROOM = 1.0D - RECENCY_FLOOR;

    /** S = 1 时的半衰期：7 天（§6.2）。 */
    public static final double HALF_LIFE_HOURS_BASE = 168.0D;

    /** 记忆强度基数：只被提到一次的轮次，S = 1（7 天半衰期）。 */
    public static final double SUPPORT_BASE = 1.0D;

    /** 每多一条轮级溯源就加的半条命：同一事实被反复提到 → 更耐久（§6.2 间隔效应的静态代理）。 */
    public static final double SUPPORT_PER_TURN_REF = 0.5D;

    /** 强度增量上限：{@code turnRefs} 超过 15 条不再继续加寿命（避免长单元文本刷分）。 */
    public static final double SUPPORT_BONUS_CAP = 7.0D;

    /** 强度上限 8：S=8 时半衰期 56 天，与 03 的记忆强度上界一致。 */
    public static final double SUPPORT_MAX = SUPPORT_BASE + SUPPORT_BONUS_CAP;

    /** importance 下界（等价于 raw = 1）：保证乘法不会因 importance 归零。 */
    public static final double IMPORTANCE_FLOOR = AXMemoryEvent.MIN_IMPORTANCE
            / (double) AXMemoryEvent.MAX_IMPORTANCE;

    /**
     * 重要性钉子线（记忆设计 §6.3.4 的"强制 Hot 线"的弱化版，取 {@code raw ≥ 7}）。
     *
     * <p>importance ≥ 这条线的事件属于位置 / 明示偏好 / 未完成承诺一类<b>不可重建</b>的信息，
     * 不能因为"这次没人问它"就被时间衰减淘汰掉。因此它们的 recency 地板被单独抬高到
     * {@link #PINNED_RECENCY_FLOOR}：仍然随时间衰减，但永不低于该线。</p>
     */
    public static final int PINNED_IMPORTANCE = 7;

    /**
     * 钉子事件的 recency 地板 0.50。
     *
     * <p>取值理由：强制 Hot 线要求 {@code relevance ≥ 0.60}，于是钉子事件的最坏得分
     * {@code 0.60 × 0.50 × 0.7 = 0.21}，刚好贴着 Hot 绝对地板 0.22、远高于 Warm 地板 0.08——
     * 也就是说"重要的旧事"不会被时间打进 Cold，但也不会盖过本轮真正高相关的新事件。</p>
     */
    public static final double PINNED_RECENCY_FLOOR = 0.50D;

    /** 强制 Hot 线的相关性下限（§6.3.4）：高分事件只有在相关性也高时才走特权通道。 */
    public static final double FORCED_HOT_RELEVANCE = 0.60D;

    /** 强制 Hot 线的重要性下限（§6.3.4）：{@code raw ≥ 8}。 */
    public static final int FORCED_HOT_IMPORTANCE = 8;

    /**
     * Cold 可容线（§6.3.4）：只有 {@code raw ≥ 4} 的事件允许进 Cold。
     *
     * <p>防止"玩家问过一句话"这类弱事实在 90 天后仍占着 Cold 的条数配额。</p>
     */
    public static final int COLD_MIN_IMPORTANCE = 4;

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private AXMemoryScoring() {
    }

    /**
     * 事件印象分：{@code relevance × recency × importanceFactor}。
     *
     * @param relevance 向量相似度（0–1，来自 RAG 命中分，本类不改它的算法）
     */
    public static double score(double relevance, AXMemoryEvent event, long nowMillis) {
        double boundedRelevance = clamp01(relevance);
        if (boundedRelevance <= 0.0D) {
            return 0.0D;
        }
        return boundedRelevance * recency(event, nowMillis) * importanceFactor(event);
    }

    /** 时间衰减因子：指数、半衰期 7–56 天、地板 0.05（钉子事件地板更高）。 */
    public static double recency(AXMemoryEvent event, long nowMillis) {
        double floor = isPinned(event) ? PINNED_RECENCY_FLOOR : RECENCY_FLOOR;
        if (event == null || nowMillis <= 0L || event.happenedAtMillis() <= 0L) {
            return floor;
        }
        double ageHours = Math.max(0.0D, (nowMillis - event.happenedAtMillis()) / (double) MILLIS_PER_HOUR);
        double halfLifeHours = HALF_LIFE_HOURS_BASE * supportStrength(event);
        double decay = Math.pow(2.0D, -ageHours / halfLifeHours);
        double floorHeadroom = 1.0D - floor;
        return floor + floorHeadroom * decay;
    }

    /** 重要性乘数：{@code importance/10}，落在 [0.1, 1.0]，永远不会把乘积归零。 */
    public static double importanceFactor(AXMemoryEvent event) {
        if (event == null) {
            return AXMemoryEvent.DEFAULT_IMPORTANCE / (double) AXMemoryEvent.MAX_IMPORTANCE;
        }
        return Math.max(IMPORTANCE_FLOOR, event.importance() / (double) AXMemoryEvent.MAX_IMPORTANCE);
    }

    /** 记忆强度 S ∈ [1, 8]：轮级溯源越多，半衰期越长。 */
    public static double supportStrength(AXMemoryEvent event) {
        if (event == null || event.turnRefs().isEmpty()) {
            return SUPPORT_BASE;
        }
        double bonus = Math.min(SUPPORT_BONUS_CAP, SUPPORT_PER_TURN_REF * (event.turnRefs().size() - 1));
        return Math.min(SUPPORT_MAX, SUPPORT_BASE + bonus);
    }

    /** 重要性钉子：≥ {@link #PINNED_IMPORTANCE} 的记忆不参与"被时间淘汰"。 */
    public static boolean isPinned(AXMemoryEvent event) {
        return event != null && event.importance() >= PINNED_IMPORTANCE;
    }

    /** 强制 Hot：高重要性 + 高相关，直接进 Hot，不受 {@code 0.72 × top} 相对线约束（§6.3.4）。 */
    public static boolean isForcedHot(AXMemoryEvent event, double relevance) {
        return event != null
                && event.importance() >= FORCED_HOT_IMPORTANCE
                && clamp01(relevance) >= FORCED_HOT_RELEVANCE;
    }

    /** Cold 可容线：重要性低于 {@link #COLD_MIN_IMPORTANCE} 的事件不进 Cold（§6.3.4）。 */
    public static boolean isColdEligible(AXMemoryEvent event) {
        return event != null && event.importance() >= COLD_MIN_IMPORTANCE;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
