package com.rheinmetal.tianshu.dubhe.agent.delegate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheResumeIntentTest {

    private static final List<String> WORDS = List.of("继续", "接着", "好", "好的", "continue", "yes");

    @Test
    void shortAgreementsAreRecognizedIncludingPunctuation() {
        assertTrue(DubheResumeIntent.accepts("继续", WORDS));
        assertTrue(DubheResumeIntent.accepts("继续。", WORDS));
        assertTrue(DubheResumeIntent.accepts("  好 ", WORDS));
        assertTrue(DubheResumeIntent.accepts("Continue!", WORDS));
    }

    @Test
    void aSentenceThatHappensToStartWithAgreementIsANewRequest() {
        assertFalse(DubheResumeIntent.accepts("继续查暮色森林", WORDS));
        assertFalse(DubheResumeIntent.accepts("好的，那铁砧怎么修", WORDS));
        assertFalse(DubheResumeIntent.accepts("", WORDS));
        assertFalse(DubheResumeIntent.accepts(null, WORDS));
    }

    @Test
    void withoutWordsNothingMatches() {
        assertFalse(DubheResumeIntent.accepts("继续", List.of()));
        assertFalse(DubheResumeIntent.accepts("继续", null));
    }
}