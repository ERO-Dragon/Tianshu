package com.rheinmetal.tianshu.dubhe.shared.contract;

import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventClass;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * 事件型输入。外部模块把"自己世界里刚发生的一件事"交给 AX：一句它自己已经渲染好的话，加一个它希望的行为。
 *
 * <p><b>生产方给的是行为，不是频率</b>：{@code behavior} 四选一，回答"这次希望被怎么处理"——
 * {@code IMMEDIATE} 到达即说、{@code DEFERRED} 等冷却后说一次、{@code SILENT} 进历史、
 * {@code FOLD} 只留在当前窗口（聚一行、不进长期记忆）。{@link DubheEventClass#DROP} 不接受：什么都不发生等于不投递。
 * 频率（{@code RARE / NORMAL / FREQUENT}）是 AX 的私有词汇，只有内置采集层知道"这个 kind 多久一次"，
 * 外部不需要也不可能声明它。</p>
 *
 * <p>行为只是请求：天枢仍按自己的规则修正——同一 kind 短时间反复发生会被换到更高的频率行，玩家的削弱名单会把它压下去。
 * 因此"要求立刻说"不保证立刻说，只保证不会被更保守地处理。</p>
 *
 * <p>它不请求回答，也不进入对话仲裁：AX 只把这句话走与内置事件完全相同的判定路径
 * （频率修正 → 名单 → 类别），按最终类别决定立即出声、等冷却、只进历史还是丢弃。
 * 想让 AX 回答某句话，请走 IA 的 participant 注册，而不是这里。</p>
 *
 * <p><b>句子由生产方渲染</b>：AX 不查自己的语言表，因此 {@code text} 必须是一句成品；本模块只做清洗
 * （NFC 归一、剥离尖括号、按边界截断到 120 字符）。这也是外部模组可以用自己的 lang 文件、
 * 而不必把文案塞进天枢资源的原因。</p>
 *
 * <p>{@code kind} 必须命名空间化（{@code ^[a-z0-9_.-]+:[a-z0-9_./-]+$}），内置表只占
 * {@code tianshu:}，第三方不得借用该前缀。</p>
 *
 * <p>AX 不做键级去重：同一次发生投递两次就是两条历史。生产方负责"每次发生只投一次"。</p>
 */
public record DubheWorldEventPayload(
        String kind,
        DubheEventClass behavior,
        String text
) implements ITianshuPayload {
    public DubheWorldEventPayload {
        kind = kind == null ? "" : kind.trim();
        text = text == null ? "" : text.trim();
    }
}
