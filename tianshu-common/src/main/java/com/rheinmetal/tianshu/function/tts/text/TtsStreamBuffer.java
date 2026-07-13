package com.rheinmetal.tianshu.function.tts.text;

import com.rheinmetal.tianshu.text.SentenceSegmenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TtsStreamBuffer {
    private final SentenceSegmenter segmenter;
    private final StringBuilder buffer = new StringBuilder();

    public TtsStreamBuffer(SentenceSegmenter segmenter) {
        this.segmenter = segmenter == null ? new SentenceSegmenter() : segmenter;
    }

    public synchronized Optional<String> append(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        buffer.append(text);
        return drainNext();
    }

    public synchronized List<String> appendSegments(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        buffer.append(text);
        return drainAvailable();
    }

    public synchronized List<String> drainAvailable() {
        List<String> segments = new ArrayList<>();
        Optional<String> segment;
        while ((segment = drainNext()).isPresent()) {
            segments.add(segment.get());
        }
        return List.copyOf(segments);
    }

    private Optional<String> drainNext() {
        SentenceSegmenter.SegmentBoundary boundary = segmenter.nextBoundary(buffer.toString());
        if (!boundary.shouldFlush()) {
            return Optional.empty();
        }
        return flush(boundary.endIndex());
    }

    public synchronized Optional<String> flush() {
        if (buffer.isEmpty()) {
            return Optional.empty();
        }
        return flush(buffer.length());
    }

    public synchronized void clear() {
        buffer.setLength(0);
    }

    private Optional<String> flush(int endIndex) {
        int safeEnd = Math.max(0, Math.min(endIndex, buffer.length()));
        String text = buffer.substring(0, safeEnd).trim();
        buffer.delete(0, safeEnd);
        trimLeadingWhitespace();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
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
