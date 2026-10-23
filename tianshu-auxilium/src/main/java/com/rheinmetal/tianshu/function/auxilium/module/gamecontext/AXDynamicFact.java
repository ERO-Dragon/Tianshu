package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import java.util.List;

/**
 * One rendered dynamic fact, ready for the prompt.
 *
 * <p>{@code text} is this module's rendering — AX owns the wording, and it renders from the typed fields of the
 * presence fact, in the language of the prompt. The structured data itself is not carried any further: nothing
 * downstream reads it, so a second copy here would only be a second place to keep in sync.</p>
 */
public record AXDynamicFact(
        String factId,
        String text,
        int priority,
        String source,
        String subject,
        List<String> tags,
        long updatedAtMillis,
        long ttlMillis
) {
    public AXDynamicFact {
        factId = clean(factId, "dynamic.fact.unknown");
        text = clean(text, "");
        priority = Math.max(0, Math.min(100, priority));
        source = clean(source, "presence");
        subject = clean(subject, "");
        tags = tags == null ? List.of() : List.copyOf(tags.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
        updatedAtMillis = updatedAtMillis <= 0L ? System.currentTimeMillis() : updatedAtMillis;
        ttlMillis = Math.max(0L, ttlMillis);
    }

    public static AXDynamicFact of(String text, int priority, String source) {
        return new AXDynamicFact(
                "dynamic.fact.inline",
                text,
                priority,
                source,
                "",
                List.of(),
                System.currentTimeMillis(),
                0L
        );
    }

    public boolean isEmpty() {
        return text.isBlank();
    }

    public boolean isExpired(long nowMillis) {
        return ttlMillis > 0L && updatedAtMillis + ttlMillis <= nowMillis;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
