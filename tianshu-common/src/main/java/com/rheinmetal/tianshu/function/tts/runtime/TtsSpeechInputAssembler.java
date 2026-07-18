package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.function.tts.text.TtsStreamBuffer;
import com.rheinmetal.tianshu.protocol.payload.TtsTextInputMode;
import com.rheinmetal.tianshu.text.SentenceSegmenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TtsSpeechInputAssembler {
    private final Map<TtsSpeechSessionKey, StreamState> streams = new HashMap<>();

    public synchronized Batch accept(
            TtsSpeechSessionKey key,
            TtsTextInputMode inputMode,
            String text,
            boolean end
    ) {
        if (key == null) {
            throw new IllegalArgumentException("TTS speech session key is required");
        }
        TtsTextInputMode mode = inputMode == null ? TtsTextInputMode.DOCUMENT : inputMode;
        if (mode == TtsTextInputMode.DOCUMENT) {
            return document(text);
        }

        StreamState state = streams.get(key);
        boolean opened = state == null;
        if (state == null) {
            state = new StreamState(mode);
            streams.put(key, state);
        } else if (state.mode != mode) {
            throw new IllegalArgumentException("TTS speech input mode cannot change within a session");
        }

        List<String> sentences = new ArrayList<>();
        String normalized = normalize(text);
        if (mode == TtsTextInputMode.SENTENCE_STREAM) {
            if (!normalized.isBlank()) {
                sentences.add(normalized);
            }
        } else {
            sentences.addAll(state.buffer.appendSegments(text));
        }
        if (end) {
            if (mode == TtsTextInputMode.RAW_TEXT_STREAM) {
                state.buffer.flush().ifPresent(sentences::add);
            }
            streams.remove(key);
        }
        return new Batch(opened, List.copyOf(sentences), end);
    }

    public synchronized void cancel(TtsSpeechSessionKey key) {
        StreamState removed = streams.remove(key);
        if (removed != null) {
            removed.buffer.clear();
        }
    }

    public synchronized boolean isOpen(TtsSpeechSessionKey key) {
        return key != null && streams.containsKey(key);
    }

    public synchronized void clear() {
        streams.values().forEach(state -> state.buffer.clear());
        streams.clear();
    }

    private Batch document(String text) {
        TtsStreamBuffer buffer = new TtsStreamBuffer(new SentenceSegmenter());
        List<String> sentences = new ArrayList<>(buffer.appendSegments(text));
        buffer.flush().ifPresent(sentences::add);
        return new Batch(true, List.copyOf(sentences), true);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record Batch(boolean opened, List<String> sentences, boolean ended) {
        public Batch {
            sentences = sentences == null ? List.of() : List.copyOf(sentences);
        }
    }

    private static final class StreamState {
        private final TtsTextInputMode mode;
        private final TtsStreamBuffer buffer = new TtsStreamBuffer(new SentenceSegmenter());

        private StreamState(TtsTextInputMode mode) {
            this.mode = mode;
        }
    }
}
