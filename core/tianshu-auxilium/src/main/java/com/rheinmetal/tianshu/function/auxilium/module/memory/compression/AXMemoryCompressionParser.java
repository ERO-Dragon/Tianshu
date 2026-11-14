package com.rheinmetal.tianshu.function.auxilium.module.memory.compression;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnBatch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一段式压缩输出解析器（{@code schemaVersion = 2}）。
 *
 * <p>一次模型调用同时产出三要素：{@code summary} / {@code eventChain} / {@code preferences}。
 * 解析遵循三条硬约束：</p>
 *
 * <ol>
 *   <li><b>字段级降级</b>：三个字段各自解析。摘要坏了仍然落事件，事件坏了仍然落摘要，不整体丢弃。</li>
 *   <li><b>事件不去重、不合并</b>：解析器不做任何文本级去重。同一句事实出现两次就是两条，只要
 *   各自的轮级溯源不同；"同一件事发生两次"必须能被区分开。</li>
 *   <li><b>轮级溯源</b>：每条事件必须有属于本批的 {@code turnRefs}，否则该条被拒绝（其余照常）；这是
 *   "不去重"能成立的前提——没有溯源，两条同样文本的事件就无法证明是两次真实发生。</li>
 * </ol>
 *
 * <p>{@code turnRefs} 接受两种写法：本批轮次的 <b>1-based 序号</b>（提示词里每行都带序号，这也是
 * 小模型最容易复述的形态）或 {@code AXRawTurn.id()} 字符串。解析结果统一归一成轮 id，越界/编造的
 * 引用一律丢弃。</p>
 *
 * <p>不属于本批的 {@code schemaVersion} 会整批拒绝（§10 G3）：静默降级等于用旧解析器读新 schema，
 * 字段会静默丢失。</p>
 */
public final class AXMemoryCompressionParser {
    /** 本解析器认识的压缩 JSON 版本。 */
    public static final int SUPPORTED_SCHEMA_VERSION = 2;

    /** 单条事实/偏好的字符上限：只挡异常输出，不限制摘要长度。 */
    public static final int MAX_FACT_CHARS = 512;

    /**
     * 偏好落库的明示门槛。
     *
     * <p>对应设计 §4.5 的 {@code confidence=explicit}：模型给出低于该值的置信度时，视为"非明示"，
     * 整条丢弃（不阻塞其余字段）。</p>
     */
    public static final double MIN_PREFERENCE_CONFIDENCE = 0.5D;

    public static final String KEY_SCHEMA_VERSION = "schemaVersion";
    public static final String KEY_SUMMARY = "summary";
    public static final String KEY_EVENT_CHAIN = "eventChain";
    public static final String KEY_PREFERENCES = "preferences";
    public static final String KEY_EVENT = "event";
    /** 设计文档里的旧键名，仅作为同义键读取，不作为写出形态。 */
    public static final String KEY_EVENT_ALIAS = "fact";
    public static final String KEY_IMPORTANCE = "importance";
    public static final String KEY_TURN_REFS = "turnRefs";
    public static final String KEY_CONFIDENCE = "confidence";

    public static final String DIAGNOSTIC_EMPTY_OUTPUT = "compact_empty_output";
    public static final String DIAGNOSTIC_UNPARSEABLE_OUTPUT = "compact_unparseable_output";
    public static final String DIAGNOSTIC_SCHEMA_VERSION_MISSING = "schema_version_missing";
    public static final String DIAGNOSTIC_SCHEMA_VERSION_UNSUPPORTED = "schema_version_unsupported";
    public static final String DIAGNOSTIC_SUMMARY_MISSING = "summary_missing";
    public static final String DIAGNOSTIC_SUMMARY_SALVAGED = "summary_salvaged";
    public static final String DIAGNOSTIC_EVENT_CHAIN_INVALID = "event_chain_invalid";
    public static final String DIAGNOSTIC_EVENT_INVALID_ITEM = "event_invalid_item";
    public static final String DIAGNOSTIC_EVENT_EMPTY_FACT = "event_empty_fact";
    public static final String DIAGNOSTIC_EVENT_TOO_LONG = "event_too_long";
    public static final String DIAGNOSTIC_EVENT_INVALID_TEXT = "event_invalid_text";
    public static final String DIAGNOSTIC_EVENT_WITHOUT_TURN_REFS = "event_without_turn_refs";
    public static final String DIAGNOSTIC_EVENT_UNKNOWN_TURN_REFS = "event_unknown_turn_refs";
    public static final String DIAGNOSTIC_IMPORTANCE_FALLBACK = "importance_fallback";
    public static final String DIAGNOSTIC_IMPORTANCE_CLAMPED = "importance_clamped";
    public static final String DIAGNOSTIC_PREFERENCES_INVALID = "preferences_invalid";
    public static final String DIAGNOSTIC_PREFERENCE_EMPTY_FACT = "preference_empty_fact";
    public static final String DIAGNOSTIC_PREFERENCE_WITHOUT_CONFIDENCE = "preference_without_confidence";
    public static final String DIAGNOSTIC_PREFERENCE_LOW_CONFIDENCE = "preference_low_confidence";
    public static final String DIAGNOSTIC_PREFERENCE_WITHOUT_TURN_REFS = "preference_without_turn_refs";

    private static final char REPLACEMENT_CHAR = '\uFFFD';
    private static final String CODE_FENCE = "```";

    /**
     * 解析一次压缩调用的输出。
     *
     * @param rawOutput 模型原始输出（可能带 markdown 代码围栏）
     * @param batch     本次压缩单元；{@code turnRefs} 的校验与归一都以它为准
     */
    public AXMemoryCompressionResult parse(String rawOutput, AXRawTurnBatch batch) {
        List<String> diagnostics = new ArrayList<>();
        String text = stripCodeFence(rawOutput == null ? "" : rawOutput.trim());
        if (text.isBlank()) {
            diagnostics.add(DIAGNOSTIC_EMPTY_OUTPUT);
            return new AXMemoryCompressionResult(0, "", List.of(), List.of(), diagnostics);
        }
        JsonObject root = parseObject(text);
        if (root == null) {
            if (looksLikeJson(text)) {
                // 截断/损坏的 JSON 不能当摘要用，也不能猜字段：交给调用方按"无产物"处理。
                diagnostics.add(DIAGNOSTIC_UNPARSEABLE_OUTPUT);
                return new AXMemoryCompressionResult(0, "", List.of(), List.of(), diagnostics);
            }
            // 非 JSON 自由文本：只做摘要兜底（§5.7），事件与偏好置空。
            diagnostics.add(DIAGNOSTIC_SUMMARY_SALVAGED);
            return new AXMemoryCompressionResult(0, text, List.of(), List.of(), diagnostics);
        }
        int schemaVersion = readSchemaVersion(root, diagnostics);
        if (schemaVersion < 0) {
            return new AXMemoryCompressionResult(0, "", List.of(), List.of(), diagnostics);
        }
        String summary = readString(root, KEY_SUMMARY);
        if (summary.isBlank()) {
            diagnostics.add(DIAGNOSTIC_SUMMARY_MISSING);
        }
        List<AXMemoryCompressedEvent> events = parseEvents(root, batch, diagnostics);
        List<AXMemoryCompressedPreference> preferences = parsePreferences(root, batch, diagnostics);
        return new AXMemoryCompressionResult(schemaVersion, summary, events, preferences, diagnostics);
    }

    /** 读版本号；返回 {@code -1} 表示整批拒绝，返回 {@code 0} 表示缺版本号但继续解析。 */
    private int readSchemaVersion(JsonObject root, List<String> diagnostics) {
        JsonElement element = root.get(KEY_SCHEMA_VERSION);
        if (element == null || element.isJsonNull()) {
            diagnostics.add(DIAGNOSTIC_SCHEMA_VERSION_MISSING);
            return 0;
        }
        int version = parseIntOr(element, 0);
        if (version <= 0) {
            diagnostics.add(DIAGNOSTIC_SCHEMA_VERSION_MISSING);
            return 0;
        }
        if (version != SUPPORTED_SCHEMA_VERSION) {
            diagnostics.add(DIAGNOSTIC_SCHEMA_VERSION_UNSUPPORTED);
            return -1;
        }
        return version;
    }

    private List<AXMemoryCompressedEvent> parseEvents(JsonObject root, AXRawTurnBatch batch, List<String> diagnostics) {
        JsonElement chain = root.get(KEY_EVENT_CHAIN);
        if (chain == null || chain.isJsonNull()) {
            return List.of();
        }
        if (!chain.isJsonArray()) {
            diagnostics.add(DIAGNOSTIC_EVENT_CHAIN_INVALID);
            return List.of();
        }
        List<AXMemoryCompressedEvent> events = new ArrayList<>();
        for (JsonElement element : chain.getAsJsonArray()) {
            AXMemoryCompressedEvent event = parseEvent(element, batch, diagnostics);
            if (event != null) {
                // 不去重：重复条目各自保留，这是"事件是客观事实"的直接后果。
                events.add(event);
            }
        }
        return List.copyOf(events);
    }

    private AXMemoryCompressedEvent parseEvent(JsonElement element, AXRawTurnBatch batch, List<String> diagnostics) {
        if (element == null || !element.isJsonObject()) {
            diagnostics.add(DIAGNOSTIC_EVENT_INVALID_ITEM);
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String fact = readString(object, KEY_EVENT);
        if (fact.isBlank()) {
            fact = readString(object, KEY_EVENT_ALIAS);
        }
        if (fact.isBlank()) {
            diagnostics.add(DIAGNOSTIC_EVENT_EMPTY_FACT);
            return null;
        }
        if (fact.length() > MAX_FACT_CHARS) {
            diagnostics.add(DIAGNOSTIC_EVENT_TOO_LONG);
            return null;
        }
        if (fact.indexOf(REPLACEMENT_CHAR) >= 0) {
            diagnostics.add(DIAGNOSTIC_EVENT_INVALID_TEXT);
            return null;
        }
        boolean hasTurnRefsKey = object.has(KEY_TURN_REFS) && object.get(KEY_TURN_REFS).isJsonArray();
        List<String> turnRefs = resolveTurnRefs(object, batch);
        if (turnRefs.isEmpty()) {
            diagnostics.add(hasTurnRefsKey ? DIAGNOSTIC_EVENT_UNKNOWN_TURN_REFS : DIAGNOSTIC_EVENT_WITHOUT_TURN_REFS);
            return null;
        }
        return new AXMemoryCompressedEvent(fact, readImportance(object, diagnostics), turnRefs);
    }

    private int readImportance(JsonObject object, List<String> diagnostics) {
        JsonElement element = object.get(KEY_IMPORTANCE);
        Integer parsed = parseIntOrNull(element);
        if (parsed == null) {
            diagnostics.add(DIAGNOSTIC_IMPORTANCE_FALLBACK);
            return AXMemoryEvent.DEFAULT_IMPORTANCE;
        }
        if (parsed < AXMemoryEvent.MIN_IMPORTANCE || parsed > AXMemoryEvent.MAX_IMPORTANCE) {
            diagnostics.add(DIAGNOSTIC_IMPORTANCE_CLAMPED);
        }
        return Math.max(AXMemoryEvent.MIN_IMPORTANCE, Math.min(AXMemoryEvent.MAX_IMPORTANCE, parsed));
    }

    private List<AXMemoryCompressedPreference> parsePreferences(JsonObject root, AXRawTurnBatch batch, List<String> diagnostics) {
        JsonElement element = root.get(KEY_PREFERENCES);
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray()) {
            diagnostics.add(DIAGNOSTIC_PREFERENCES_INVALID);
            return List.of();
        }
        List<AXMemoryCompressedPreference> preferences = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            AXMemoryCompressedPreference preference = parsePreference(item, batch, diagnostics);
            if (preference != null) {
                preferences.add(preference);
            }
        }
        return List.copyOf(preferences);
    }

    private AXMemoryCompressedPreference parsePreference(JsonElement element, AXRawTurnBatch batch, List<String> diagnostics) {
        if (element == null || !element.isJsonObject()) {
            diagnostics.add(DIAGNOSTIC_PREFERENCES_INVALID);
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String fact = readString(object, KEY_EVENT);
        if (fact.isBlank()) {
            fact = readString(object, KEY_EVENT_ALIAS);
        }
        if (fact.isBlank() || fact.length() > MAX_FACT_CHARS) {
            diagnostics.add(DIAGNOSTIC_PREFERENCE_EMPTY_FACT);
            return null;
        }
        Double confidence = parseDoubleOrNull(object.get(KEY_CONFIDENCE));
        if (confidence == null) {
            // 没有置信度就没有"明示"证据（§4.5 只收 explicit）。
            diagnostics.add(DIAGNOSTIC_PREFERENCE_WITHOUT_CONFIDENCE);
            return null;
        }
        double normalized = Math.max(0.0D, Math.min(1.0D, confidence));
        if (normalized < MIN_PREFERENCE_CONFIDENCE) {
            diagnostics.add(DIAGNOSTIC_PREFERENCE_LOW_CONFIDENCE);
            return null;
        }
        List<String> turnRefs = resolveTurnRefs(object, batch);
        if (turnRefs.isEmpty()) {
            diagnostics.add(DIAGNOSTIC_PREFERENCE_WITHOUT_TURN_REFS);
            return null;
        }
        return new AXMemoryCompressedPreference(fact, normalized, turnRefs);
    }

    /**
     * 归一化 {@code turnRefs}：序号（1-based）或轮 id 都接受，结果统一成轮 id。
     *
     * <p>越界序号与本批之外的轮 id 一律丢弃（防模型编造溯源），同一轮只保留一次。</p>
     */
    private List<String> resolveTurnRefs(JsonObject object, AXRawTurnBatch batch) {
        JsonElement element = object.get(KEY_TURN_REFS);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<AXRawTurn> turns = batch == null ? List.of() : batch.turns();
        if (turns.isEmpty()) {
            return List.of();
        }
        Set<String> resolved = new LinkedHashSet<>();
        for (JsonElement ref : element.getAsJsonArray()) {
            String turnId = resolveTurnRef(ref, turns);
            if (!turnId.isBlank()) {
                resolved.add(turnId);
            }
        }
        return List.copyOf(resolved);
    }

    private String resolveTurnRef(JsonElement ref, List<AXRawTurn> turns) {
        if (ref == null || ref.isJsonNull() || !ref.isJsonPrimitive()) {
            return "";
        }
        JsonPrimitive primitive = ref.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return turnIdAt(turns, primitive.getAsInt());
        }
        if (primitive.isString()) {
            String value = primitive.getAsString() == null ? "" : primitive.getAsString().trim();
            if (value.isBlank()) {
                return "";
            }
            Integer index = tryParseInt(value);
            if (index != null) {
                return turnIdAt(turns, index);
            }
            return turns.stream().anyMatch(turn -> value.equals(turn.id())) ? value : "";
        }
        return "";
    }

    private String turnIdAt(List<AXRawTurn> turns, int oneBasedIndex) {
        if (oneBasedIndex < 1 || oneBasedIndex > turns.size()) {
            return "";
        }
        return turns.get(oneBasedIndex - 1).id();
    }

    private Integer parseIntOrNull(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            try {
                return (int) Math.round(primitive.getAsDouble());
            } catch (Exception ignored) {
                return null;
            }
        }
        if (primitive.isString()) {
            return tryParseInt(primitive.getAsString());
        }
        return null;
    }

    private int parseIntOr(JsonElement element, int fallback) {
        Integer parsed = parseIntOrNull(element);
        return parsed == null ? fallback : parsed;
    }

    private Double parseDoubleOrNull(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            try {
                double value = primitive.getAsDouble();
                return Double.isFinite(value) ? value : null;
            } catch (Exception ignored) {
                return null;
            }
        }
        if (primitive.isString()) {
            try {
                double value = Double.parseDouble(primitive.getAsString().trim());
                return Double.isFinite(value) ? value : null;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer tryParseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String readString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive()) {
            return "";
        }
        String value = element.getAsString();
        return value == null ? "" : value.trim();
    }

    private JsonObject parseObject(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 去掉一层 markdown 代码围栏（小模型最常见的输出噪声）。 */
    private String stripCodeFence(String text) {
        String value = text == null ? "" : text.trim();
        if (!value.startsWith(CODE_FENCE)) {
            return value;
        }
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd < 0) {
            return value;
        }
        String body = value.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf(CODE_FENCE);
        return (closing >= 0 ? body.substring(0, closing) : body).trim();
    }

    private boolean looksLikeJson(String text) {
        String value = text == null ? "" : text.trim();
        return value.startsWith("{") || value.startsWith("[");
    }
}
