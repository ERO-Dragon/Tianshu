package com.rheinmetal.tianshu.function.auxilium.event;

import java.util.Locale;

/**
 * Shape of the sentence produced when occurrences of one kind are folded together.
 *
 * <p>The form decides how many arguments the aggregate sentence receives, so it is declared per
 * kind in the tier table instead of being guessed at render time.
 */
public enum AXEventAggregateForm {
    /** Sentence receives the occurrence count only. */
    COUNT("count"),
    /** Sentence receives the occurrence count and the first and last numeric metric. */
    RANGE("range");

    private final String id;

    AXEventAggregateForm(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Parses a form id; blank or unknown input falls back to {@link #COUNT}. */
    public static AXEventAggregateForm fromId(String id) {
        if (id == null || id.isBlank()) {
            return COUNT;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (AXEventAggregateForm form : values()) {
            if (form.id.equals(normalized)) {
                return form;
            }
        }
        return COUNT;
    }
}
