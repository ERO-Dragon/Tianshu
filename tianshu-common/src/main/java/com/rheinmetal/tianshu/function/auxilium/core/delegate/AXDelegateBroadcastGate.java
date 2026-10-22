package com.rheinmetal.tianshu.function.auxilium.core.delegate;

/**
 * 播报门槛判定（设计 §6.2）：<b>只判定，不出声</b>。
 *
 * <p>三个通道必须分开（§6.1）：① 事件线登记与 ② {@code <event>} 追加永远发生，③ 出声才看门槛。因此本类
 * 的返回值只影响"现在能不能开口"，绝不影响投递是否发生。</p>
 *
 * <p>判定与"一次能出声几条"（D1 的 FIFO 分条）无关：后者属播报器，本片不实现。</p>
 */
public final class AXDelegateBroadcastGate {

    /** 播报门槛的三种判定（§6.2 表格前两行，加上"战斗中不打断"）。 */
    public enum Mode {
        /** 玩家空闲：立即出声。 */
        IMMEDIATE,
        /** 正在对话 / 正在播报：排队到本轮结束，FIFO 逐条（§6.2）。 */
        QUEUED,
        /** 战斗中：只追加不播报。失败类不落此态（必须出声一句，§6.2）。 */
        SUPPRESSED
    }

    /**
     * 判定所需的运行时上下文。
     *
     * @param chatTurnActive 是否有一轮 {@code CHAT} 正在进行
     * @param broadcasting   是否正在播报别的条目
     * @param inCombat       玩家是否处于战斗（该来源属游戏状态片，未接线时传 {@code false}）
     */
    public record Context(boolean chatTurnActive, boolean broadcasting, boolean inCombat) {
        public static Context idle() {
            return new Context(false, false, false);
        }
    }

    /**
     * @param context   运行时上下文，{@code null} 等同空闲
     * @param mustSpeak 该条目是否"必须出声"（失败 / 超时 / 取消，§6.2）
     */
    public Mode decide(Context context, boolean mustSpeak) {
        Context effective = context == null ? Context.idle() : context;
        if (effective.inCombat()) {
            // 失败不静默丢：战斗中也不打断，但降级为排队而不是抑制（§6.2 "不允许静默失败"）。
            return mustSpeak ? Mode.QUEUED : Mode.SUPPRESSED;
        }
        if (effective.chatTurnActive() || effective.broadcasting()) {
            return Mode.QUEUED;
        }
        return Mode.IMMEDIATE;
    }
}
