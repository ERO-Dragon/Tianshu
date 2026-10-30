package com.rheinmetal.tianshu.protocol.payload;

import java.util.Objects;

/**
 * One typed field of a context fact.
 *
 * <p>Facts are data, not speech: this type carries a name and a {@link FactValue}, and nothing else. It never
 * holds a rendered sentence — wording belongs to whichever consumer needs it, in that consumer's own language
 * and its own template set.
 */
public record FactField(String name, FactValue value) {
    public FactField {
        name = name == null ? "" : name.trim();
        Objects.requireNonNull(value, "fact field value is required");
    }

    public boolean blank() {
        return name.isEmpty();
    }
}
