package com.rheinmetal.tianshu.dubhe.agent.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheAgentReportTest {

    @Test
    void theThreeFieldsAreEnoughToDeriveTheState() {
        DubheAgentReport done = new DubheAgentReport("查到三条线索", "", "");
        assertTrue(done.hasResult());
        assertFalse(done.hasBlock());
        assertEquals("查到三条线索", done.speech());

        DubheAgentReport blocked = new DubheAgentReport("", "没查到", "");
        assertFalse(blocked.hasResult());
        assertEquals("没查到", blocked.speech());

        DubheAgentReport partial = new DubheAgentReport("找到一半", "另一半没查到", "要继续吗");
        assertEquals("找到一半 要继续吗", partial.speech());
    }

    @Test
    void anEmptyReportIsAModelFailure() {
        assertThrows(IllegalArgumentException.class, () -> new DubheAgentReport("", "  ", null));
    }

    @Test
    void theAnswerIsParsedEvenWhenTheModelWrapsItInProse() {
        DubheAgentReport report = DubheAgentReport.fromJson(
                "好的。\n{\"result\":\"铁砧可以修复工具\",\"need\":\"要我演示吗\"}");

        assertEquals("铁砧可以修复工具", report.result());
        assertEquals("要我演示吗", report.need());
        assertEquals("铁砧可以修复工具 要我演示吗", report.speech());
    }

    @Test
    void anAnswerWithoutAReportObjectIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DubheAgentReport.fromJson("没问题"));
    }

    @Test
    void theCeilingCutsRunawayTextAndRecordsIt() {
        DubheAgentReport report = new DubheAgentReport("中".repeat(1_000), "", "");

        DubheAgentReport clamped = report.clamped();

        assertTrue(clamped.truncated());
        assertTrue(DubheAgentTokenEstimator.estimate(clamped.result()) <= DubheAgentReport.MAX_TOKENS);
        assertTrue(DubheAgentTokenEstimator.estimate(report.result()) > DubheAgentReport.MAX_TOKENS);
    }

    @Test
    void aShortReportIsNotTouched() {
        DubheAgentReport report = new DubheAgentReport("短结论", "", "");

        assertEquals(report, report.clamped());
        assertFalse(report.clamped().truncated());
    }
}