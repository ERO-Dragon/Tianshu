package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;

/** Maps selected lifecycle events to concise player-facing progress updates. */
final class ClientDiagnosticProgress {
    private static final int MAX_CHAT_CONTENT_LENGTH = 160;

    private ClientDiagnosticProgress() {
    }

    static ClientDiagnosticMessage messageFor(DiagnosticEvent event) {
        if (event == null) {
            return null;
        }
        return switch (event.code()) {
            case "RECOGNITION_COMPLETE", "STREAM_RESULT" ->
                    contentMessage("tianshu.chat.progress.asr.recognition", event, "text", "output", "input");
            case "ARBITRATION_ACCEPTED" ->
                    arbitrationMessage("tianshu.chat.progress.ia.arbitration.accepted", event);
            case "ARBITRATION_REJECTED" ->
                    arbitrationMessage("tianshu.chat.progress.ia.arbitration.rejected", event);
            case "DIALOGUE_DELIVERY" ->
                    contentMessage("tianshu.chat.progress.ax.input", event, "normalizedText", "repairedText", "input");
            case "LLM_SUBMITTED" ->
                    ClientDiagnosticMessage.key("tianshu.chat.progress.thinking");
            case "CHAT_COMPLETED", "STREAM_COMPLETED" ->
                    ClientDiagnosticMessage.key(
                            "tianshu.chat.progress.llm.reply",
                            summarize(event.attributes().get("input")),
                            summarize(event.attributes().get("output"))
                    );
            case "SYNTHESIS_COMPLETED" ->
                    contentMessage("tianshu.chat.progress.tts.input", event, "text", "input", "output");
            default -> event.severity() == DiagnosticSeverity.ERROR || isFailure(event.code())
                    ? ClientDiagnosticMessage.key("tianshu.chat.progress.failed")
                    : null;
        };
    }

    private static ClientDiagnosticMessage arbitrationMessage(String key, DiagnosticEvent event) {
        return ClientDiagnosticMessage.key(
                key,
                summarize(firstAttribute(event, "normalizedText", "repairedText", "input")),
                summarize(event.attributes().get("reason"))
        );
    }

    private static ClientDiagnosticMessage contentMessage(String key, DiagnosticEvent event, String... candidates) {
        return ClientDiagnosticMessage.key(key, summarize(firstAttribute(event, candidates)));
    }

    private static String firstAttribute(DiagnosticEvent event, String... candidates) {
        for (String candidate : candidates) {
            String value = event.attributes().get(candidate);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_CHAT_CONTENT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_CHAT_CONTENT_LENGTH - 1) + "...";
    }

    private static boolean isFailure(String code) {
        return code != null && (code.endsWith("_FAILED") || code.endsWith("_REJECTED"));
    }
}
