package com.rheinmetal.tianshu.function.auxilium.core.output;

import com.rheinmetal.tianshu.function.tts.text.TtsSentenceSegmenter;

import java.util.ArrayList;
import java.util.List;

final class AXSentenceBuffer {
    private final TtsSentenceSegmenter segmenter = new TtsSentenceSegmenter(12, 24, 180);
    private final StringBuilder buffer = new StringBuilder();

    List<String> append(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        buffer.append(text);
        return drain(false);
    }

    List<String> flush() {
        return drain(true);
    }

    void clear() {
        buffer.setLength(0);
    }

    private List<String> drain(boolean force) {
        List<String> result = new ArrayList<>();
        while (!buffer.isEmpty()) {
            TtsSentenceSegmenter.SegmentBoundary boundary = segmenter.nextBoundary(buffer.toString());
            if (!boundary.shouldFlush()) {
                break;
            }
            collect(result, boundary.endIndex());
        }
        if (force && !buffer.isEmpty()) {
            collect(result, buffer.length());
        }
        return List.copyOf(result);
    }

    private void collect(List<String> result, int endIndex) {
        int safeEnd = Math.max(0, Math.min(endIndex, buffer.length()));
        String sentence = buffer.substring(0, safeEnd).trim();
        buffer.delete(0, safeEnd);
        trimLeadingWhitespace();
        if (!sentence.isBlank()) {
            result.add(sentence);
        }
    }

    private void trimLeadingWhitespace() {
        int index = 0;
        while (index < buffer.length() && Character.isWhitespace(buffer.charAt(index))) {
            index++;
        }
        if (index > 0) {
            buffer.delete(0, index);
        }
    }
}
