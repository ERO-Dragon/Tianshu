package com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E 抽取结果解析器。优先按设计文档要求的严格 JSON 数组解析；
 * 当小模型输出非 JSON（含 think 残段、markdown 控制符或自由文本）时，
 * 按行降级解析以保证链路鲁棒性。
 *
 * 降级解析约束：剥除 <think>...</think> 残段；跳过明显非事实行
 * （纯符号、JSON 控制符、markdown 围栏）；去行首编号与列表标记；
 * 仍受长度上限、Unicode 替换符过滤和去重约束。
 */
public final class AXMemoryFactExtractionParser {
    private static final int MAX_FACT_CHARS = 512;
    private static final String FACT_KEY = "fact";

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDERED_LIST_PREFIX = Pattern.compile("^\\s*\\d+[.)、]\\s*");
    private static final Pattern UNORDERED_LIST_PREFIX = Pattern.compile("^\\s*[-*•]\\s+");

    public List<String> parse(String text) {
        String normalized = stripThinkBlock(text == null ? "" : text).trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        List<String> facts = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        List<String> jsonFacts = parseJsonArray(normalized);
        if (!jsonFacts.isEmpty()) {
            for (String fact : jsonFacts) {
                if (acceptFact(fact, seen)) {
                    facts.add(fact);
                }
            }
            return facts;
        }

        for (String line : normalized.split("\\r?\\n")) {
            String fact = cleanLine(line);
            if (fact.isBlank()) {
                continue;
            }
            if (acceptFact(fact, seen)) {
                facts.add(fact);
            }
        }
        return facts;
    }

    private List<String> parseJsonArray(String text) {
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (Exception e) {
            return List.of();
        }
        if (root == null || !root.isJsonArray()) {
            return List.of();
        }
        List<String> facts = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            facts.add(readFact(element));
        }
        return facts;
    }

    private String readFact(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return "";
        }
        JsonObject object = element.getAsJsonObject();
        if (object.size() != 1 || !object.has(FACT_KEY) || object.get(FACT_KEY).isJsonNull() || !object.get(FACT_KEY).isJsonPrimitive()) {
            return "";
        }
        JsonPrimitive primitive = object.get(FACT_KEY).getAsJsonPrimitive();
        if (!primitive.isString()) {
            return "";
        }
        String value = primitive.getAsString();
        return value == null ? "" : value.trim();
    }

    private boolean acceptFact(String fact, Set<String> seen) {
        if (fact.isBlank() || fact.length() > MAX_FACT_CHARS || fact.indexOf('\uFFFD') >= 0) {
            return false;
        }
        return seen.add(AXHashing.sha256Short(fact));
    }

    private String cleanLine(String raw) {
        String line = raw == null ? "" : raw.trim();
        if (line.isBlank()) {
            return "";
        }
        Matcher ordered = ORDERED_LIST_PREFIX.matcher(line);
        if (ordered.find()) {
            line = ordered.replaceFirst("");
        }
        Matcher unordered = UNORDERED_LIST_PREFIX.matcher(line);
        if (unordered.find()) {
            line = unordered.replaceFirst("");
        }
        line = line.trim();
        if (line.isBlank()) {
            return "";
        }
        if (isControlLine(line)) {
            return "";
        }
        return stripTrailingPunctuation(line);
    }

    private boolean isControlLine(String line) {
        if (line.length() <= 3) {
            return true;
        }
        char first = line.charAt(0);
        char last = line.charAt(line.length() - 1);
        if (first == '<' || last == '>' || first == '`' || last == '`') {
            return true;
        }
        if (line.startsWith("```") || line.startsWith("~~~") || line.startsWith("[" ) || line.startsWith("]")
                || line.startsWith("{") || line.startsWith("}")) {
            return true;
        }
        if (line.startsWith("- ") || line.startsWith("* ")) {
            return true;
        }
        boolean onlyPunctOrDigit = true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isDigit(c)
                    && "!\"#&'()*+,-./:;<=>?@[\\]_{}~。、，；：（）「」『』“”‘’·…—".indexOf(c) < 0) {
                onlyPunctOrDigit = false;
                break;
            }
        }
        return onlyPunctOrDigit;
    }

    private String stripTrailingPunctuation(String line) {
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == '.' || c == '。' || c == ',' || c == '，' || c == ';' || c == '；' || c == '!' || c == '！' || c == '?') {
                end--;
            } else {
                break;
            }
        }
        return end == line.length() ? line : line.substring(0, end).trim();
    }

    private String stripThinkBlock(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = THINK_BLOCK.matcher(text).replaceAll("");
        int start = result.indexOf("<think>");
        if (start >= 0) {
            int end = result.indexOf("</think>", start);
            if (end < 0) {
                result = result.substring(0, start);
            } else {
                result = result.substring(0, start) + result.substring(end + "</think>".length());
            }
        }
        return result;
    }
}
