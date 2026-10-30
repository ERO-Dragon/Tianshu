package com.rheinmetal.tianshu.client.host;

import java.util.List;

/**
 * The chat line a module attaches to one of its own diagnostic codes.
 *
 * <p>It lives in api because the host renders it: the module supplies the translation key and, per placeholder,
 * the attribute names to read from its own event. The host therefore neither knows the code nor which of the
 * module's attributes carry the text — moving a message or renaming an attribute is a module-local change.</p>
 *
 * @param translationKey     the language key of the line
 * @param argumentCandidates one entry per placeholder; each entry lists the event attribute names to try in order,
 *                           the first non-blank wins
 */
public record DiagnosticChatNotice(String translationKey, List<List<String>> argumentCandidates) {

    public DiagnosticChatNotice {
        if (translationKey == null || translationKey.isBlank()) {
            throw new IllegalArgumentException("translationKey must not be blank");
        }
        argumentCandidates = argumentCandidates == null
                ? List.of()
                : argumentCandidates.stream()
                        .map(candidates -> candidates == null ? List.<String>of() : List.copyOf(candidates))
                        .toList();
    }

    /** One placeholder, filled from the first non-blank of {@code attributeCandidates}. */
    public static DiagnosticChatNotice single(String translationKey, String... attributeCandidates) {
        return new DiagnosticChatNotice(translationKey, List.of(List.of(attributeCandidates)));
    }
}
