package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结论的可执行判定（设计 §5.6）：schema 解析 + 三句上限 + 300 token 上限。 */
class AXDelegateConclusionParserTest {

    @Test
    void parsesTheDocumentedSchema() {
        AXDelegateConclusion conclusion = new AXDelegateConclusionParser().parse(
                "{\"conclusion\":\"暮色森林从要塞旁的传送门进入。\",\"sources\":[\"wiki\",\" \"]}");

        assertEquals("暮色森林从要塞旁的传送门进入。", conclusion.text());
        assertEquals(List.of("wiki"), conclusion.sources());
    }

    @Test
    void bareTextIsAcceptedAsTheConclusion() {
        AXDelegateConclusion conclusion = new AXDelegateConclusionParser().parse("  铁砧可以修复工具。  ");

        assertEquals("铁砧可以修复工具。", conclusion.text());
        assertTrue(conclusion.sources().isEmpty());
    }

    @Test
    void sentencesBeyondThreeAreCutWhole() {
        AXDelegateConclusion conclusion = new AXDelegateConclusionParser().parse(
                "第一句。第二句。第三句。第四句。第五句。");

        assertEquals("第一句。第二句。第三句。", conclusion.text());
    }

    @Test
    void tokenBudgetDropsWholeSentencesBeforeTruncating() {
        // 计数器按字符计价：三个 200 字的句子 = 600 token > 300，逐句丢弃直到落进预算，因此只剩第一句。
        String sentence = "甲".repeat(199) + "。";
        AXDelegateConclusion conclusion = new AXDelegateConclusionParser(String::length)
                .parse(sentence + sentence + sentence);

        assertEquals(sentence, conclusion.text());
    }

    @Test
    void singleOversizedSentenceIsTruncatedToTheBudget() {
        AXDelegateConclusionParser parser = new AXDelegateConclusionParser(String::length);

        String clamped = parser.clamp("乙".repeat(600));

        assertTrue(clamped.length() <= AXDelegateConclusionParser.MAX_CONCLUSION_TOKENS);
        assertTrue(clamped.length() > AXDelegateConclusionParser.MAX_CONCLUSION_TOKENS - 8);
    }

    @Test
    void estimateTokensIsConservativeButMonotonic() {
        assertTrue(AXDelegateConclusionParser.estimateTokens("abcd") > 0);
        assertTrue(AXDelegateConclusionParser.estimateTokens("abcd")
                <= AXDelegateConclusionParser.estimateTokens("abcdefgh"));
        assertEquals(0, AXDelegateConclusionParser.estimateTokens(""));
    }

    @Test
    void missingConclusionIsRejected() {
        AXDelegateConclusionParser parser = new AXDelegateConclusionParser();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> parser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"sources\":[]}"));
        assertEquals(0, calls.get());
    }
}
