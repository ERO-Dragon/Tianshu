package com.rheinmetal.tianshu.protocol.payload;

/**
 * The typed value of one {@link FactField}.
 *
 * <p>Facts are data, not speech: a fact states what the numbers and identifiers <em>are</em>, and every
 * consumer decides how (or whether) to word them. The variants exist so a consumer never has to guess from
 * a string — {@link Number} is a decimal that needs formatting, {@link Count} is an integer, {@link Identifier}
 * is a namespaced id, {@link Text} is already-final text supplied by the game (a display name), {@link Flag}
 * is a boolean, and {@link Record} is a repeated structure such as one inventory item or one potion effect.
 *
 * <p>This mirrors the shape the event layer already uses for its wording ({@code AXEventText} with typed
 * arguments): semantics in the contract, rendering on the consuming side.
 */
public sealed interface FactValue {
    /** A decimal that a consumer may format for display. */
    record Number(double value) implements FactValue {
    }

    /** An integer count, level or tick value. */
    record Count(int value) implements FactValue {
    }

    /** A namespaced identifier such as {@code minecraft:overworld}. */
    record Identifier(String value) implements FactValue {
        public Identifier {
            value = value == null ? "" : value.trim();
        }
    }

    /** Text the game already localized (a display name), passed through unchanged. */
    record Text(String value) implements FactValue {
        public Text {
            value = value == null ? "" : value.trim();
        }
    }

    /** A boolean state. */
    record Flag(boolean value) implements FactValue {
    }

    /** A repeated structure, for example one inventory item or one active effect. */
    record Record(java.util.List<FactField> fields) implements FactValue {
        public Record {
            fields = fields == null ? java.util.List.of() : java.util.List.copyOf(fields);
        }
    }
}
