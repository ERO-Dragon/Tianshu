package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record IrResultPayload(boolean matched, String normalizedText, String intentType, String targetCapability, double confidence, boolean repaired, String reason, int turnId, long sessionId) implements ITianshuPayload {
    public IrResultPayload {
        if (normalizedText == null) normalizedText = "";
        if (intentType == null) intentType = "";
        if (targetCapability == null) targetCapability = "";
        if (reason == null) reason = "";
    }
}
