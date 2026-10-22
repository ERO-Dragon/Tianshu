package com.rheinmetal.tianshu.function.auxilium.core.delegate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委派指令的识别（设计 §4.1 / §5.1 步骤 ③）：只有明确的 {@code {"action":"delegate","query":…}}
 * 才算委派，其余一律按既有答句路径处理。
 */
class AXDelegateSignalTest {

    @Test
    void explicitDelegateActionYieldsTheQuery() {
        AXDelegateSignal signal = AXDelegateSignal.parse("{\"action\":\"delegate\",\"query\":\"  暮色森林的进入方式  \"}");

        AXDelegateSignal.Delegated delegated = assertInstanceOf(AXDelegateSignal.Delegated.class, signal);
        assertEquals("暮色森林的进入方式", delegated.query());
    }

    @Test
    void blankQueryIsInvalidInsteadOfCreatingATicket() {
        AXDelegateSignal signal = AXDelegateSignal.parse("{\"action\":\"delegate\"}");

        AXDelegateSignal.Invalid invalid = assertInstanceOf(AXDelegateSignal.Invalid.class, signal);
        assertEquals(AXDelegateSignal.INVALID_QUERY, invalid.reasonCode());
    }

    @Test
    void delegateActionIgnoresExtraFields() {
        AXDelegateSignal signal = AXDelegateSignal.parse(
                "{\"action\":\"delegate\",\"query\":\"附魔台怎么用\",\"confidence\":0.9}");

        assertInstanceOf(AXDelegateSignal.Delegated.class, signal);
    }

    @Test
    void plainAnswerTextIsNotADelegateInstruction() {
        assertInstanceOf(AXDelegateSignal.Absent.class, AXDelegateSignal.parse("暮色森林从要塞旁的传送门进入。"));
        assertInstanceOf(AXDelegateSignal.Absent.class, AXDelegateSignal.parse(""));
        assertInstanceOf(AXDelegateSignal.Absent.class, AXDelegateSignal.parse(null));
    }

    @Test
    void answerActionIsLeftToTheExistingAnswerPath() {
        // 本片不改答句契约（§4.1 的重问与资源键降级属另一片），因此 action=answer 照旧算 Absent。
        assertInstanceOf(AXDelegateSignal.Absent.class,
                AXDelegateSignal.parse("{\"action\":\"answer\",\"text\":\"铁砧可以修复工具。\"}"));
    }

    @Test
    void malformedJsonIsNotADelegateInstruction() {
        assertInstanceOf(AXDelegateSignal.Absent.class, AXDelegateSignal.parse("{\"action\":\"delegate\""));
        assertInstanceOf(AXDelegateSignal.Absent.class, AXDelegateSignal.parse("[1,2,3]"));
        assertTrue(AXDelegateSignal.parse("{}") instanceof AXDelegateSignal.Absent);
    }
}
