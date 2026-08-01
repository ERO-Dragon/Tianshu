package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossTextChunkerTest {
    private final MossTextChunker chunker = new MossTextChunker(6, MossTextChunkerTest::codePoints);

    @Test
    void greedilyMergesCompleteSemanticUnitsUnderTokenLimit() {
        List<String> chunks = chunker.split("你好。世界！再见。");

        assertEquals(List.of("你好。世界！", "再见。"), chunks);
    }

    @Test
    void everyChunkRespectsTokenizerLimit() {
        List<String> chunks = chunker.split("甲乙丙，丁戊己。庚辛壬癸");

        assertTrue(chunks.stream().allMatch(chunk -> codePoints(chunk) <= 6));
    }

    @Test
    void oversizedUnpunctuatedTextUsesTokenizerAwareFallback() {
        List<String> chunks = new MossTextChunker(4, MossTextChunkerTest::codePoints)
                .split("abcdefghij");

        assertEquals(List.of("abcd", "efgh", "ij"), chunks);
    }

    @Test
    void preservesNormalizedContentAndOrder() {
        String input = "  第一段，没有丢失；第二段也没有。最后一段  ";

        List<String> chunks = chunker.split(input);

        assertEquals(input.trim(), String.join("", chunks));
    }

    @Test
    void contextualSentenceLimitUsesSameTokenizerCeiling() {
        int accepted = chunker.contextualSentenceLimit(List.of("甲乙。", "丙丁。", "戊己。"));

        assertEquals(2, accepted);
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }
}
