package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 结论的可执行判定（设计 §5.6）：剥出正文与来源，然后<b>本地</b>把正文裁到"≤3 句、渲染后 ≤300 token"。
 *
 * <p>两条约束都是硬上限，超了就裁、并让调用方记 {@code complex_result_overflow}（§5.2 步骤 ⑦ / §10.1）。
 * 裁剪只压缩"投递正文"，不丢"投递记录"——与 A4 不冲突（§5.6 末注）。</p>
 *
 * <p>不在这里做的：句内指代校验（A10）的告警归诊断片；结论写记忆库（§5.2 第 ⑨ 步）归记忆片。</p>
 */
public final class AXDelegateConclusionParser {

    /** 结论句数上限（§5.6 约束 4 / A.2：按句末标点切，超出整句裁剪）。 */
    public static final int MAX_CONCLUSION_SENTENCES = 3;

    /** 结论渲染上限（§5.6 约束 3 / A.2：沿用 03 §8.2 的 300 预留位）。 */
    public static final int MAX_CONCLUSION_TOKENS = 300;

    /** 句末标点（§5.6 约束 4 原文）。 */
    public static final String SENTENCE_TERMINATORS = "。！？；.!?;";

    /** JSON 契约字段名（Complex 输出的伪 schema，字段名在此定死）。 */
    public static final String FIELD_CONCLUSION = "conclusion";
    public static final String FIELD_SOURCES = "sources";

    /** 回传结果为空/不可解析时的稳定原因码。 */
    public static final String REASON_MISSING = "delegate.conclusion_missing";

    /**
     * 字符级 token 估算的换算系数：非 CJK 按 4 字符 ≈ 1 token，CJK 按 1 字符 ≈ 1 token。
     *
     * <p>这是<b>本地保守估算</b>，只在拿不到 provider 计数时用于判断 300 上限；两个数字是具名常量，测试
     * 里注入精确计数器即可让判定完全确定。</p>
     */
    public static final int NON_CJK_CHARS_PER_TOKEN = 4;
    public static final int CJK_TOKENS_PER_CHAR = 1;

    private final ToIntFunction<String> tokenCounter;

    public AXDelegateConclusionParser() {
        this(null);
    }

    /**
     * @param tokenCounter 精确 token 计数器（可为 {@code null}，用 {@link #estimateTokens(String)}）
     */
    public AXDelegateConclusionParser(ToIntFunction<String> tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    /**
     * 解析并裁剪一次 Complex 结果。
     *
     * @throws IllegalArgumentException 正文为空/不可解析（调用方转成 {@code AXStepFailure}）
     */
    public AXDelegateConclusion parse(String rawModelText) {
        String text = rawModelText == null ? "" : rawModelText.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(REASON_MISSING);
        }
        String conclusion = extractConclusion(text);
        if (conclusion.isBlank()) {
            throw new IllegalArgumentException(REASON_MISSING);
        }
        return new AXDelegateConclusion(clamp(clampSentences(conclusion)), extractSources(text));
    }

    /** 按句末标点整句裁剪到 {@link #MAX_CONCLUSION_SENTENCES} 句。 */
    public String clampSentences(String text) {
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= MAX_CONCLUSION_SENTENCES) {
            return String.join("", sentences).trim();
        }
        return String.join("", sentences.subList(0, MAX_CONCLUSION_SENTENCES)).trim();
    }

    /** 渲染后 token 数不超过 {@link #MAX_CONCLUSION_TOKENS}；必要时从尾部整句丢弃，最后才按字符截断。 */
    public String clamp(String text) {
        String current = text == null ? "" : text.trim();
        if (current.isEmpty() || countTokens(current) <= MAX_CONCLUSION_TOKENS) {
            return current;
        }
        List<String> sentences = splitSentences(current);
        while (sentences.size() > 1) {
            sentences.remove(sentences.size() - 1);
            String candidate = String.join("", sentences).trim();
            if (countTokens(candidate) <= MAX_CONCLUSION_TOKENS) {
                return candidate;
            }
        }
        return truncateToTokenBudget(current);
    }

    /** 按句末标点切分，标点留在句尾；空段丢弃。 */
    public static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null) {
            return sentences;
        }
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            current.append(value);
            if (SENTENCE_TERMINATORS.indexOf(value) >= 0) {
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        return sentences;
    }

    /** 本地保守 token 估算（见 {@link #NON_CJK_CHARS_PER_TOKEN} / {@link #CJK_TOKENS_PER_CHAR}）。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int index = 0; index < text.length(); index++) {
            if (isCjk(text.charAt(index))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk * CJK_TOKENS_PER_CHAR + (other + NON_CJK_CHARS_PER_TOKEN - 1) / NON_CJK_CHARS_PER_TOKEN;
    }

    private int countTokens(String text) {
        return tokenCounter == null ? estimateTokens(text) : tokenCounter.applyAsInt(text);
    }

    /** 二分找"最大的仍在预算内的前缀"，避免引入字符/token 换算的魔数。 */
    private String truncateToTokenBudget(String text) {
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (countTokens(text.substring(0, middle)) <= MAX_CONCLUSION_TOKENS) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, low).trim();
    }

    private static String extractConclusion(String text) {
        JsonObject object = asJsonObject(text);
        if (object == null) {
            return text;
        }
        if (!object.has(FIELD_CONCLUSION) || !object.get(FIELD_CONCLUSION).isJsonPrimitive()) {
            return "";
        }
        String value = object.get(FIELD_CONCLUSION).getAsString();
        return value == null ? "" : value.trim();
    }

    private static List<String> extractSources(String text) {
        JsonObject object = asJsonObject(text);
        if (object == null || !object.has(FIELD_SOURCES) || !object.get(FIELD_SOURCES).isJsonArray()) {
            return List.of();
        }
        JsonArray array = object.getAsJsonArray(FIELD_SOURCES);
        List<String> sources = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonPrimitive()) {
                continue;
            }
            String source = element.getAsString();
            if (source != null && !source.isBlank()) {
                sources.add(source.trim());
            }
        }
        return sources;
    }

    private static JsonObject asJsonObject(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(trimmed);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static boolean isCjk(char value) {
        return value >= '\u2E80' && value <= '\u9FFF' || value >= '\uF900' && value <= '\uFAFF';
    }
}
