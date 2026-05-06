package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmCommandRepairResultPayload(String originalText, String normalizedText, String repairedText, boolean changed, String reason, int repairDepth, int turnId, long sessionId) implements ITianshuPayload {
    public LlmCommandRepairResultPayload {
        if (originalText == null) originalText = "";
        if (normalizedText == null) normalizedText = originalText;
        if (repairedText == null) repairedText = "";
        if (reason == null) reason = "";
    }
}
