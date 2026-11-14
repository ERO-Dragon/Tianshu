package com.rheinmetal.tianshu.api.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolWireLimitsTest {

    @Test
    void aResultThatDoesNotFitBecomesAStructuredFailure() {
        ToolWireLimits.Fitted fitted = ToolWireLimits.fitResult(true,
                "x".repeat(ToolWireLimits.MAX_JSON + 1), "", "");

        assertFalse(fitted.ok());
        assertEquals("FAILED", fitted.errorCode());
        assertEquals("", fitted.valueJson());
        assertTrue(fitted.message().length() <= ToolWireLimits.MAX_MESSAGE);
    }

    @Test
    void aNormalResultIsUntouchedAndLongTextIsClamped() {
        ToolWireLimits.Fitted normal = ToolWireLimits.fitResult(true, "{\"ok\":true}", "", "");
        assertTrue(normal.ok());
        assertEquals("{\"ok\":true}", normal.valueJson());

        ToolWireLimits.Fitted longText = ToolWireLimits.fitResult(false, "",
                "C".repeat(ToolWireLimits.MAX_CODE + 5), "m".repeat(ToolWireLimits.MAX_MESSAGE + 5));
        assertEquals(ToolWireLimits.MAX_CODE, longText.errorCode().length());
        assertEquals(ToolWireLimits.MAX_MESSAGE, longText.message().length());
    }

    @Test
    void requestsStayStrict() {
        assertEquals("abc", ToolWireLimits.text("abc", "id", 3));
        assertThrows(IllegalArgumentException.class, () -> ToolWireLimits.text("abcd", "id", 3));
        assertThrows(IllegalArgumentException.class, () -> ToolWireLimits.text(null, "id", 3));
    }
}
