package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 主对话回答里"要委派"这一种形态的识别结果（设计 §4.1 / §5.1 步骤 ③）。
 *
 * <p>主对话<b>不带 {@code tools[]}</b>、历史里也不出现 {@code tool_calls}（A3），所以"要委派"只能用
 * 结构化输出表达：{@code {"action":"delegate","query":"…"}}。本类只做<b>识别</b>，不做契约修复：
 * {@link Absent} 表示这不是委派指令（普通答句原样走既有输出路径），因此答句契约的强化（形态外输出的
 * 重问与资源键降级，§4.1）不属于本片，本片对既有答句路径零改动。</p>
 *
 * <p>解析刻意保守：只有"是一个 JSON 对象且 {@code action} 恰为 {@code delegate}"才认；其余一律
 * {@link Absent}。这样"模型输出了一段普通文字"永远不会被误判成委派，也不会因为解析器而改变既有行为。</p>
 */
public sealed interface AXDelegateSignal {

    /** 不是委派指令：按既有答句路径处理。 */
    record Absent() implements AXDelegateSignal {
    }

    /** 明确的委派指令，{@code query} 已去首尾空白且非空。 */
    record Delegated(String query) implements AXDelegateSignal {
        public Delegated {
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("delegate query must not be blank");
            }
        }
    }

    /** 明知要委派但指令不完整（{@code query} 为空/不可读）：不建 ticket（§5.2 步骤 ③）。 */
    record Invalid(String reasonCode) implements AXDelegateSignal {
        public Invalid {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode must not be blank");
            }
        }
    }

    /** 委派指令形态不完整时的稳定原因码（资源键风格，不是玩家文案）。 */
    String INVALID_QUERY = "delegate_invalid_query";

    /** JSON 字段名与动作取值：契约的一部分，写死在这里（§4.1 只有 answer / delegate 两种）。 */
    String FIELD_ACTION = "action";
    String ACTION_DELEGATE = "delegate";
    String FIELD_QUERY = "query";

    /**
     * 识别一段主对话回答。
     *
     * @param assistantText 模型回传的正文；{@code null}/空白视为 {@link Absent}
     */
    static AXDelegateSignal parse(String assistantText) {
        String text = assistantText == null ? "" : assistantText.trim();
        if (text.isEmpty() || text.charAt(0) != '{') {
            return new Absent();
        }
        JsonObject object;
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element == null || !element.isJsonObject()) {
                return new Absent();
            }
            object = element.getAsJsonObject();
        } catch (RuntimeException malformed) {
            return new Absent();
        }
        if (!object.has(FIELD_ACTION) || !object.get(FIELD_ACTION).isJsonPrimitive()) {
            return new Absent();
        }
        String action = object.get(FIELD_ACTION).getAsString();
        if (!ACTION_DELEGATE.equalsIgnoreCase(action)) {
            return new Absent();
        }
        String query = object.has(FIELD_QUERY) && object.get(FIELD_QUERY).isJsonPrimitive()
                ? object.get(FIELD_QUERY).getAsString().trim()
                : "";
        return query.isEmpty() ? new Invalid(INVALID_QUERY) : new Delegated(query);
    }
}
