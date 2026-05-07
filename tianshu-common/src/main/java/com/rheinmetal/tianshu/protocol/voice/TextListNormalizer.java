package com.rheinmetal.tianshu.protocol.voice;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class TextListNormalizer {
    private TextListNormalizer() {
    }

    static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String text = value.trim();
            if (!text.isEmpty()) {
                normalized.add(text);
            }
        }
        return List.copyOf(normalized);
    }

    static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch) && !isCommonPunctuation(ch)) {
                builder.append(Character.toLowerCase(ch));
            }
        }
        return builder.toString();
    }

    static List<String> collectMatches(String text, List<String> words) {
        if (text.isEmpty() || words.isEmpty()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String word : words) {
            if (text.contains(normalizeText(word))) {
                matches.add(word);
            }
        }
        return List.copyOf(matches);
    }

    private static boolean isCommonPunctuation(char ch) {
        return ch == ',' || ch == '.' || ch == '?' || ch == '!' || ch == ';' || ch == ':'
            || ch == '，' || ch == '。' || ch == '？' || ch == '！' || ch == '；' || ch == '：'
            || ch == '、' || ch == '“' || ch == '”' || ch == '‘' || ch == '’' || ch == '（'
            || ch == '）' || ch == '(' || ch == ')' || ch == '[' || ch == ']' || ch == '{'
            || ch == '}' || ch == '<' || ch == '>' || ch == '《' || ch == '》';
    }
}
