package com.rheinmetal.tianshu.function.llm;

final class LlmThinkingContentFilter {
    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private final StringBuilder pending = new StringBuilder();
    private boolean inThinkBlock;

    String append(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        pending.append(text);
        StringBuilder output = new StringBuilder();
        drain(output);
        return output.toString();
    }

    String flush() {
        String result = inThinkBlock ? "" : pending.toString();
        pending.setLength(0);
        inThinkBlock = false;
        return result;
    }

    static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        LlmThinkingContentFilter filter = new LlmThinkingContentFilter();
        return filter.append(text) + filter.flush();
    }

    private void drain(StringBuilder output) {
        while (pending.length() > 0) {
            if (inThinkBlock) {
                if (!drainThinkBlock()) {
                    return;
                }
                continue;
            }
            if (!drainVisibleText(output)) {
                return;
            }
        }
    }

    private boolean drainThinkBlock() {
        int closeIndex = pending.indexOf(CLOSE_TAG);
        if (closeIndex >= 0) {
            pending.delete(0, closeIndex + CLOSE_TAG.length());
            inThinkBlock = false;
            return true;
        }
        keepPossibleTagSuffix(CLOSE_TAG);
        return false;
    }

    private boolean drainVisibleText(StringBuilder output) {
        int openIndex = pending.indexOf(OPEN_TAG);
        if (openIndex >= 0) {
            output.append(pending, 0, openIndex);
            pending.delete(0, openIndex + OPEN_TAG.length());
            inThinkBlock = true;
            return true;
        }
        int keep = suffixLengthMatchingTagPrefix(OPEN_TAG);
        int emitLength = pending.length() - keep;
        if (emitLength > 0) {
            output.append(pending, 0, emitLength);
            pending.delete(0, emitLength);
        }
        return false;
    }

    private void keepPossibleTagSuffix(String tag) {
        int keep = suffixLengthMatchingTagPrefix(tag);
        if (keep <= 0) {
            pending.setLength(0);
            return;
        }
        pending.delete(0, pending.length() - keep);
    }

    private int suffixLengthMatchingTagPrefix(String tag) {
        int limit = Math.min(pending.length(), tag.length() - 1);
        for (int length = limit; length > 0; length--) {
            if (pending.substring(pending.length() - length).equals(tag.substring(0, length))) {
                return length;
            }
        }
        return 0;
    }
}
