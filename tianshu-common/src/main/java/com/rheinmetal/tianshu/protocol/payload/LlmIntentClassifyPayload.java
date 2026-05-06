package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmIntentClassifyPayload(String originalText, String normalizedText, String candidateIntentType, double localConfidence, double commandWordRatio, String availableCommands, String knownMcNames, int turnId, long sessionId) implements ITianshuPayload {
    public LlmIntentClassifyPayload {
        if (originalText == null) originalText = "";
        if (normalizedText == null) normalizedText = originalText;
        if (candidateIntentType == null) candidateIntentType = "";
        if (availableCommands == null) availableCommands = "";
        if (knownMcNames == null) knownMcNames = "";
    }
}
