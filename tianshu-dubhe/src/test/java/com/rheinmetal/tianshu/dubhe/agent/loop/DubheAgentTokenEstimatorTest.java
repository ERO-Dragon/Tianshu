package com.rheinmetal.tianshu.dubhe.agent.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DubheAgentTokenEstimatorTest {

    @Test
    void emptyTextCostsNothing() {
        assertEquals(0, DubheAgentTokenEstimator.estimate(null));
        assertEquals(0, DubheAgentTokenEstimator.estimate(""));
    }

    @Test
    void cjkCountsAsOneTokenAndAsciiAsThreeTenths() {
        assertEquals(2, DubheAgentTokenEstimator.estimate("中文"));
        assertEquals(1, DubheAgentTokenEstimator.estimate("abc"));
    }

    @Test
    void mixedTextRoundsUp() {
        assertEquals(3, DubheAgentTokenEstimator.estimate("中文a"));
    }
}