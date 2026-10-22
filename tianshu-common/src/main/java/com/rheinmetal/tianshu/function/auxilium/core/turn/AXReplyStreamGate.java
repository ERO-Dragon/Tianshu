package com.rheinmetal.tianshu.function.auxilium.core.turn;

/**
 * 流式闸：模型分片到达时，决定"现在能不能出声"（设计 §4.1）。
 *
 * <p>为什么要闸：契约标签是普通文本的一部分，模型可能先吐 {@code <del}、再吐 {@code egate>}。若把分片直接
 * 交给 TTS/UI，等 {@link AXReplyContract#parse} 判出这是标签时，玩家已经听到半截 {@code <delegate>} 了。
 * 因此这里做三件事：</p>
 *
 * <ul>
 *   <li>缓冲里出现 {@code <} 开头、且可能是标签前缀的片段时<b>压住不出声</b>，等下一片把它判完；</li>
 *   <li>判成标签 → 继续吞到 {@code </delegate>} 为止，全程不出声，标签内容只留作"未闭合时交回"的备份；</li>
 *   <li>判成普通文本（如 {@code <del} 后面接着 {@code ete}）→ 把压住的字符原样放出去。</li>
 * </ul>
 *
 * <p>与 {@link AXReplyContract} 的关系是"同一规则的流式版"：一次投喂全文再 {@link #flush()}，产出与
 * {@code AXReplyContract.parse(text).spoken()} 相同（{@code AXReplyStreamGateTest} 用一组代表性输入守住这条
 * 一致性）。本类只做文本流过滤，不认识 LLM/TTS，因此可以独立测。</p>
 *
 * <p>整条流走完而没有闭标签时（规则 4：只有开标记），被吞下的内容<b>按普通文本交回</b>——{@link #flush()}
 * 就是那个交回点；否则"<delegate>xxx"这种退化输出会让玩家什么也听不到。</p>
 */
public final class AXReplyStreamGate {

    private final StringBuilder emitted = new StringBuilder();
    /** 可能是标签前缀、也可能是标签内部尾部的一小段不完整文本；每次 {@link #accept} 都重新判定。 */
    private String buffered = "";
    /** 已吞下的标签内容：闭标签到达时作废，流结束时由 {@link #flush()} 交回。 */
    private String tagBody = "";
    private boolean insideTag;

    /**
     * 投喂一个流式分片。
     *
     * @param chunk 模型回传的分片；{@code null}/空串按"没有新内容"处理
     * @return 本片刻可以立刻发声的文本（可能为空）
     */
    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        buffered = buffered + chunk;
        StringBuilder speakable = new StringBuilder();
        while (true) {
            if (insideTag) {
                int close = buffered.indexOf(AXReplyContract.TAG_CLOSE);
                if (close >= 0) {
                    // 标签闭合：内容作废，绝不念出去。
                    insideTag = false;
                    tagBody = "";
                    buffered = buffered.substring(close + AXReplyContract.TAG_CLOSE.length());
                    continue;
                }
                // 没等到闭标签：只留可能是闭标签前缀的尾巴，其余并入 tagBody（不发声）。
                int keep = closePrefixLength(buffered);
                tagBody = tagBody + buffered.substring(0, buffered.length() - keep);
                buffered = buffered.substring(buffered.length() - keep);
                break;
            }
            int open = buffered.indexOf('<');
            if (open < 0) {
                speakable.append(buffered);
                buffered = "";
                break;
            }
            speakable.append(buffered, 0, open);
            buffered = buffered.substring(open);
            if (buffered.startsWith(AXReplyContract.TAG_OPEN)) {
                buffered = buffered.substring(AXReplyContract.TAG_OPEN.length());
                insideTag = true;
                tagBody = "";
                continue;
            }
            if (buffered.startsWith(AXReplyContract.TAG_CLOSE)) {
                // 孤立闭标记：与解析器一致，不念。
                buffered = buffered.substring(AXReplyContract.TAG_CLOSE.length());
                continue;
            }
            if (AXReplyContract.TAG_OPEN.startsWith(buffered) || AXReplyContract.TAG_CLOSE.startsWith(buffered)) {
                // 可能是标签前缀：压住，等下一片判完。
                break;
            }
            // 不是标签：'<' 就是普通字符，放出去，后面的字符下一轮再判。
            speakable.append(buffered.charAt(0));
            buffered = buffered.substring(1);
        }
        emitted.append(speakable);
        return speakable.toString();
    }

    /**
     * 流结束：把还压在闸里的普通文本交回。
     *
     * <p>两种压着的情形含义相反：只是标签前缀（如结尾的 {@code <del}）→ 它就是普通文本；已进入标签但没有闭标签
     * → 按规则 4，标签内容要当普通文本念出来。</p>
     *
     * @return 收尾时要补念的文本（可能为空）
     */
    public String flush() {
        String tail = insideTag ? tagBody + buffered : buffered;
        insideTag = false;
        tagBody = "";
        buffered = "";
        emitted.append(tail);
        return tail;
    }

    /** 闸到目前为主已经放出去的文本（含 {@link #flush()} 交回的部分），供调用方补齐差额。 */
    public String emitted() {
        return emitted.toString();
    }

    /** 最长的一段"是闭标记前缀"的尾巴长度：它必须继续压住，否则跨片断开的 {@code </delegate>} 会被念出去。 */
    private static int closePrefixLength(String text) {
        int max = Math.min(AXReplyContract.TAG_CLOSE.length() - 1, text.length());
        for (int length = max; length >= 1; length--) {
            if (AXReplyContract.TAG_CLOSE.startsWith(text.substring(text.length() - length))) {
                return length;
            }
        }
        return 0;
    }
}
