package com.rheinmetal.tianshu.dubhe.shared.failure;

import com.rheinmetal.tianshu.dubhe.dialogue.output.DubheReplyTextRepository;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DubheActionFailureTest {

    private static final DubheReplyTextRepository ZH =
            new DubheReplyTextRepository(DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN));

    @Test
    void toolErrorsMapOntoGameFacingCauses() {
        assertEquals(DubheActionFailure.NOT_ALLOWED, DubheActionFailure.fromToolError("NO_PERMISSION", ""));
        assertEquals(DubheActionFailure.NOT_ALLOWED, DubheActionFailure.fromToolError("UNSUPPORTED_ACTOR", ""));
        assertEquals(DubheActionFailure.TARGET_UNKNOWN, DubheActionFailure.fromToolError("UNKNOWN_TOOL", ""));
        assertEquals(DubheActionFailure.TARGET_OFF, DubheActionFailure.fromToolError("DISABLED", ""));
        assertEquals(DubheActionFailure.TARGET_OFF, DubheActionFailure.fromToolError("UNAVAILABLE", ""));
        assertEquals(DubheActionFailure.TARGET_BUSY, DubheActionFailure.fromToolError("BUSY", ""));
        assertEquals(DubheActionFailure.TIME_LIMIT, DubheActionFailure.fromToolError("TIMEOUT", ""));
        assertEquals(DubheActionFailure.TOO_OFTEN, DubheActionFailure.fromToolError("RATE_LIMITED", ""));
        assertEquals(DubheActionFailure.CANCELLED, DubheActionFailure.fromToolError("CANCELLED", ""));
        assertEquals(DubheActionFailure.SERVER_LIMIT,
                DubheActionFailure.fromToolError("FAILED", "blocked by the server autonomy ceiling: conservative"));
        assertEquals(DubheActionFailure.FAILED, DubheActionFailure.fromToolError("FAILED", "socket closed"));
    }

    @Test
    void everyCauseResolvesToPlayerFacingWording() {
        for (DubheActionFailure cause : DubheActionFailure.values()) {
            if (cause == DubheActionFailure.GAME_REASON) {
                continue;
            }
            String line = ZH.actionFailure(cause, "");
            assertFalse(line.isBlank(), "missing wording for " + cause);
            assertFalse(line.contains("_"), "a cause id must not leak into speech: " + line);
        }
    }

    @Test
    void aGameReasonIsRelayedAfterCleaning() {
        assertEquals("箱子锁着", ZH.actionFailure(DubheActionFailure.GAME_REASON, "  箱子锁着 "));
        String longReason = "x".repeat(300);
        assertEquals(120, ZH.actionFailure(DubheActionFailure.GAME_REASON, longReason).length());
        assertEquals(ZH.actionFailure(DubheActionFailure.FAILED, ""),
                ZH.actionFailure(DubheActionFailure.GAME_REASON, "   "),
                "no game reason means the generic line");
    }
}