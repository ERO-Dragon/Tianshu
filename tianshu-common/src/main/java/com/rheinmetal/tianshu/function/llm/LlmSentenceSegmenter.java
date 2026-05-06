package com.rheinmetal.tianshu.function.llm;

public final class LlmSentenceSegmenter {
    private final StringBuilder buffer = new StringBuilder();

    public String accept(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        buffer.append(text);
        if (hasNaturalBoundary(buffer)) {
            return flush();
        }
        return null;
    }

    public String finish() {
        if (buffer.length() == 0) {
            return null;
        }
        return flush();
    }

    private String flush() {
        String value = buffer.toString();
        buffer.setLength(0);
        return value;
    }

    private boolean hasNaturalBoundary(StringBuilder value) {
        if (value.length() == 0) {
            return false;
        }
        char last = value.charAt(value.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '!' || last == '?' || last == '.' || last == '\n';
    }
}
