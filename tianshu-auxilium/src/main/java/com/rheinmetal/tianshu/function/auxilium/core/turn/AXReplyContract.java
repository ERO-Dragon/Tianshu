package com.rheinmetal.tianshu.function.auxilium.core.turn;

/**
 * 一轮主对话输出的契约（设计 §4.1）：<b>平时就是人话，需要查时才挂一个标签</b>。
 *
 * <pre>
 * 平时：正常说人话，零格式。
 * 需要查时：这个问题让我去查一下。&lt;delegate&gt;检索如何进入暮色森林&lt;/delegate&gt;
 * </pre>
 *
 * <p>主对话<b>不带 {@code tools[]}</b>、历史里也不出现 {@code tool_calls}（A3），所以"要委派"只能靠标签表达。
 * 与 JSON 契约相比，这里没有任何"违约"概念：模型说了句人话就是答句，标签只是可选的控制后缀。判别结果只有两态：</p>
 *
 * <ul>
 *   <li>{@link Delegated}：第一个完整标签的内容非空——它是一次独立异步检索的<b>控制信息</b>，不是答句；
 *       答句是标签<b>之外</b>的文本（即模型自己说的"我查一下"）；</li>
 *   <li>{@link Plain}：其它一切情形——无标签、标签内容为空/全空白、只有开标签没有闭标签、多个标签里第一个为空。
 *       剥掉标签后剩下的文本就是答句。</li>
 * </ul>
 *
 * <p><b>标签本身绝不进 TTS、不进历史</b>：所有出口只认 {@link #spoken()}。标签剥离遵循以下规则
 * （每条都有用例守着，见 {@code AXReplyContractTest}）：</p>
 *
 * <ol>
 *   <li>无标签 → 全文即答句；</li>
 *   <li>有完整标签、内容非空 → 标签外的文本为答句，标签内 strip 后为检索问题；</li>
 *   <li>标签内容为空/全空白 → 不委派，空标签连同标记一起剥掉，按普通文本念；</li>
 *   <li>只有开标签没有闭标签 → 剥掉开标签，不委派，剩余文本按普通文本念；</li>
 *   <li>多个标签 → 只认<b>第一个</b>，其余标签连同其内容一并从文本中剥除；</li>
 *   <li>标签在文本中间 → 标签前段与标签后段<b>直接拼接</b>为答句（只删掉标签字符，不额外插入空白，
 *       因此阅读顺序不变）。</li>
 * </ol>
 *
 * <p>标签标记按字面量匹配（区分大小写、允许两侧空白）。模型写错大小写时它只是一段普通文本，不会被当成委派。</p>
 */
public sealed interface AXReplyContract {

    /** 委派标签的开标记：契约的一部分，写死在这里（提示词与解析器共用同一个字面量）。 */
    String TAG_OPEN = "<delegate>";

    /** 委派标签的闭标记。 */
    String TAG_CLOSE = "</delegate>";

    /** 这一轮要念给玩家的正文：标签已剥除，普通形态下它就是全文。 */
    String spoken();

    /** 有人话要说、没有委派意图：{@code spoken} 已剥标签并去首尾空白，可能为空（模型什么都没说）。 */
    record Plain(String spoken) implements AXReplyContract {
        public Plain {
            spoken = spoken == null ? "" : spoken;
        }
    }

    /** 需要一次独立的异步检索：{@code spoken} 是标签外的那些话，{@code query} 已去首尾空白且非空。 */
    record Delegated(String spoken, String query) implements AXReplyContract {
        public Delegated {
            spoken = spoken == null ? "" : spoken;
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("delegate query must not be blank");
            }
            query = query.strip();
        }
    }

    /**
     * 判别一段主对话输出。
     *
     * @param assistantText 模型回传的正文；{@code null} 与空串都退化成 {@link Plain} 的空答句
     */
    static AXReplyContract parse(String assistantText) {
        String text = assistantText == null ? "" : assistantText;
        String query = firstQuery(text);
        String spoken = withoutTags(text);
        return query.isEmpty() ? new Plain(spoken) : new Delegated(spoken, query);
    }

    /**
     * 只剥掉标签标记、保留标签之间的文字：玩家输入进 prompt 之前用它，防止玩家把标签写进指令。
     *
     * <p>与 {@link #withoutTags} 的区别是要不要连标签内容一起删：答句里的标签内容属于检索意图（不该念），
     * 玩家嘴里的标签内容则是玩家自己的话（照旧保留）。</p>
     */
    static String withoutTagMarkers(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace(TAG_OPEN, "").replace(TAG_CLOSE, "");
    }

    /**
     * 剥掉所有标签标记及其内容，返回要念的文本（已去首尾空白）。
     *
     * <p>规则 5/6 落在这里：第一个完整标签是"有效标签"，其余标签块（含内容）和孤立标记一并删除，
     * 剩下的前后两段自然相接。</p>
     */
    static String withoutTags(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder spoken = new StringBuilder(text.length());
        int cursor = 0;
        while (cursor < text.length()) {
            if (text.startsWith(TAG_OPEN, cursor)) {
                int close = text.indexOf(TAG_CLOSE, cursor + TAG_OPEN.length());
                // 未闭合：只剥开标记（规则 4）；已闭合：连内容一起剥掉（规则 2/5）
                cursor = close < 0 ? cursor + TAG_OPEN.length() : close + TAG_CLOSE.length();
                continue;
            }
            if (text.startsWith(TAG_CLOSE, cursor)) {
                // 孤立闭标记同样不念
                cursor += TAG_CLOSE.length();
                continue;
            }
            spoken.append(text.charAt(cursor));
            cursor++;
        }
        return spoken.toString().strip();
    }

    /**
     * 第一个完整标签的内容（已去首尾空白、已剥标记）；没有完整标签时返回空串。
     *
     * <p>标签内容会原样进入一次独立检索，因此这里再把夹带的标记剥掉一次——嵌套写法不该把
     * {@code <delegate>} 带进检索问题里。</p>
     */
    private static String firstQuery(String text) {
        int open = text.indexOf(TAG_OPEN);
        if (open < 0) {
            return "";
        }
        int close = text.indexOf(TAG_CLOSE, open + TAG_OPEN.length());
        return close < 0 ? "" : withoutTagMarkers(text.substring(open + TAG_OPEN.length(), close)).strip();
    }
}
