package com.rheinmetal.tianshu.client.diagnostics;

import java.util.Arrays;
import java.util.List;

/** Localized, player-facing progress message; diagnostic details stay in the file sink. */
public record ClientDiagnosticMessage(String translationKey, List<Object> arguments) {
    public ClientDiagnosticMessage {
        if (translationKey == null || translationKey.isBlank()) {
            throw new IllegalArgumentException("translationKey must not be blank");
        }
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public static ClientDiagnosticMessage key(String translationKey, Object... arguments) {
        return new ClientDiagnosticMessage(translationKey,
                arguments == null ? List.of() : Arrays.asList(arguments.clone()));
    }
}
