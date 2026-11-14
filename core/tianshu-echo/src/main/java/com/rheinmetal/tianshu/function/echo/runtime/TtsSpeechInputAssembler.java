package com.rheinmetal.tianshu.function.echo.runtime;

import com.rheinmetal.tianshu.function.echo.text.TtsStreamBuffer;
import com.rheinmetal.tianshu.function.echo.contract.TtsTextInputMode;
import com.rheinmetal.tianshu.text.SentenceSegmenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        state.lastPacketMillis = System.currentTimeMillis();

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

    /** Every stream that has been opened but not ended yet, so a stop can leave a barrier for it. */
    public synchronized java.util.Set<TtsSpeechSessionKey> openKeys() {
        return java.util.Set.copyOf(streams.keySet());
    }

    /**
     * Closes a stream the way its own end packet would, returning whatever text it still held. The caller
     * uses this to reclaim a stream whose sender went silent.
     */
    public synchronized Batch endStream(TtsSpeechSessionKey key) {
        StreamState state = streams.remove(key);
        if (state == null) {
            return new Batch(false, List.of(), true);
        }
        Optional<String> tail = state.buffer.flush();
        return new Batch(false, tail.map(List::of).orElse(List.of()), true);
    }

    /**
     * The open streams that have received no packet for at least {@code idleMillis}.
     *
     * <p>The stream's own record of when it last heard from its caller belongs here, where the stream lives:
     * a caller that died mid-stream is a property of the stream, not of the runtime. Reporting instead of
     * closing keeps the decision (flush the tail, complete the session) in the runtime.</p>
     */
    public synchronized List<TtsSpeechSessionKey> idleKeys(long now, long idleMillis) {
        List<TtsSpeechSessionKey> idle = new ArrayList<>();
        for (Map.Entry<TtsSpeechSessionKey, StreamState> entry : streams.entrySet()) {
            if (now - entry.getValue().lastPacketMillis >= idleMillis) {
                idle.add(entry.getKey());
            }
        }
        return List.copyOf(idle);
    }

    public synchronized void clear() {
        streams.values().forEach(state -> state.buffer.clear());
        streams.clear();
    }

    private Batch document(String text) {
        TtsStreamBuffer buffer = new TtsStreamBuffer(SentenceSegmenter.spokenDialogue());
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
        private final TtsStreamBuffer buffer = new TtsStreamBuffer(SentenceSegmenter.spokenDialogue());
        /** When the last packet of this stream arrived; the only thing that keeps it alive. */
        private long lastPacketMillis = System.currentTimeMillis();

        private StreamState(TtsTextInputMode mode) {
            this.mode = mode;
        }
    }
}
