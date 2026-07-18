package com.rheinmetal.tianshu.function.tts.runtime;

import com.rheinmetal.tianshu.protocol.payload.TtsTextInputMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsSpeechInputAssemblerTest {
    @Test
    void sessionIdentityIncludesRealSourceId() {
        TtsSpeechSessionKey ax = TtsSpeechSessionKey.of("module.ax", 42L, 3, "a");
        TtsSpeechSessionKey npc = TtsSpeechSessionKey.of("other.npc", 42L, 3, "b");

        assertNotEquals(ax, npc);
    }

    @Test
    void sentenceStreamDoesNotSplitCallerDefinedSentence() {
        TtsSpeechInputAssembler assembler = new TtsSpeechInputAssembler();
        TtsSpeechSessionKey key = TtsSpeechSessionKey.of("module.ax", 1L, 1, "e1");

        TtsSpeechInputAssembler.Batch batch = assembler.accept(
                key,
                TtsTextInputMode.SENTENCE_STREAM,
                "first. second.",
                false
        );

        assertTrue(batch.opened());
        assertFalse(batch.ended());
        assertEquals(List.of("first. second."), batch.sentences());
    }

    @Test
    void rawStreamSegmentsAcrossChunksAndOnlyEndsExplicitly() {
        TtsSpeechInputAssembler assembler = new TtsSpeechInputAssembler();
        TtsSpeechSessionKey key = TtsSpeechSessionKey.of("other.npc", 2L, 1, "e2");

        TtsSpeechInputAssembler.Batch first = assembler.accept(key, TtsTextInputMode.RAW_TEXT_STREAM, "hello ", false);
        TtsSpeechInputAssembler.Batch second = assembler.accept(key, TtsTextInputMode.RAW_TEXT_STREAM, "world. tail", false);
        TtsSpeechInputAssembler.Batch end = assembler.accept(key, TtsTextInputMode.RAW_TEXT_STREAM, "", true);

        assertTrue(first.sentences().isEmpty());
        assertEquals(List.of("hello world."), second.sentences());
        assertFalse(second.ended());
        assertEquals(List.of("tail"), end.sentences());
        assertTrue(end.ended());
    }

    @Test
    void documentIsSegmentedAndClosedInOneAdmission() {
        TtsSpeechInputAssembler assembler = new TtsSpeechInputAssembler();
        TtsSpeechSessionKey key = TtsSpeechSessionKey.of("system", 0L, 0, "document");

        TtsSpeechInputAssembler.Batch batch = assembler.accept(
                key,
                TtsTextInputMode.DOCUMENT,
                "one. two.",
                true
        );

        assertTrue(batch.opened());
        assertTrue(batch.ended());
        assertEquals(List.of("one.", "two."), batch.sentences());
    }
}
