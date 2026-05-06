package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmIntentClassifyResultPayload(String originalText, String normalizedText, boolean commandLike, double commandProbability, String guessedIntentType, String reason, int turnId, long sessionId) implements ITianshuPayload {
    public LlmIntentClassifyResultPayload {
        if (originalText == null) originalText = "";
        if (normalizedText == null) normalizedText = originalText;
        if (guessedIntentType == null) guessedIntentType = "";
        if (reason == null) reason = "";
    }
}
