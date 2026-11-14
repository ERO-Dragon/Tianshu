package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.client.host.DiagnosticChatCatalogue;
import com.rheinmetal.tianshu.client.host.DiagnosticChatNotice;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a diagnostic event into the player-facing progress line.
 *
 * <p>The kernel keeps no table of feature codes any more: a module registers its own code and wording in the
 * {@link DiagnosticChatCatalogue} while it is assembled, and this class only looks the code up. What stays here
 * is the platform's own voice — the generic "failed" line, which is not any module's vocabulary.</p>
 */
final class ClientDiagnosticProgress {
    private static final int MAX_CHAT_CONTENT_LENGTH = 160;
    private static final String FAILURE_KEY = "tianshu.chat.progress.failed";

    private ClientDiagnosticProgress() {
    }

    static ClientDiagnosticMessage messageFor(DiagnosticEvent event, DiagnosticChatCatalogue catalogue) {
        if (event == null) {
            return null;
        }
        Optional<DiagnosticChatNotice> notice = catalogue == null
                ? Optional.empty()
                : catalogue.find(event.code());
        if (notice.isPresent()) {
            DiagnosticChatNotice resolved = notice.get();
            List<Object> arguments = new ArrayList<>(resolved.argumentCandidates().size());
            for (List<String> candidates : resolved.argumentCandidates()) {
                arguments.add(summarize(firstAttribute(event, candidates)));
            }
            return ClientDiagnosticMessage.key(resolved.translationKey(), arguments.toArray());
        }
        return event.severity() == DiagnosticSeverity.ERROR || isFailure(event.code())
                ? ClientDiagnosticMessage.key(FAILURE_KEY)
                : null;
    }

    private static String firstAttribute(DiagnosticEvent event, List<String> candidates) {
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
