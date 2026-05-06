package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LlmCommandRepairPayload(String originalText, String normalizedText, String guessedIntentType, String availableCommands, String knownMcNames, int turnId, long sessionId) implements ITianshuPayload {
    public LlmCommandRepairPayload {
        if (originalText == null) originalText = "";
        if (normalizedText == null) normalizedText = originalText;
        if (guessedIntentType == null) guessedIntentType = "";
        if (availableCommands == null) availableCommands = "";
        if (knownMcNames == null) knownMcNames = "";
    }
}
