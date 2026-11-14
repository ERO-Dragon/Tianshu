package com.rheinmetal.tianshu.function.echo.text;

import com.rheinmetal.tianshu.text.SentenceSegmenter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The buffer sits between raw caller chunks and the sentence segmenter, so its job is to hand the
 * segmenter exactly the text the indices in its answer refer to. A leading newline used to shift
 * every index and cut a word in half.
 */
class TtsStreamBufferTest {

    private static TtsStreamBuffer buffer() {
        return new TtsStreamBuffer(SentenceSegmenter.spokenDialogue());
    }

    @Test
    void leadingWhitespaceDoesNotSplitTheFirstWord() {
        TtsStreamBuffer buffer = buffer();

        assertEquals(List.of("Hello.", "World."), buffer.appendSegments("   Hello. World."));
    }

    @Test
    void rawChunksKeepTheSpaceBetweenThem() {
        TtsStreamBuffer buffer = buffer();

        buffer.appendSegments("Hello");
        buffer.appendSegments(" ");
        List<String> sentences = buffer.appendSegments("world.");

        assertEquals(List.of("Hello world."), sentences);
        assertEquals(Optional.empty(), buffer.flush());
    }

    @Test
    void whitespaceOnlyTextProducesNoSentence() {
        TtsStreamBuffer buffer = buffer();

        assertEquals(List.of(), buffer.appendSegments("   "));
        assertEquals(List.of(), buffer.flush().map(List::of).orElse(List.of()));
    }
}
