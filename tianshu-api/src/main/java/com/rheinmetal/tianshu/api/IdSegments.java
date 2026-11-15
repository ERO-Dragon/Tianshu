package com.rheinmetal.tianshu.api;

/**
 * The character set and shape every namespaced id segment shares.
 *
 * <p>Tool ids and capability ids are two records with the same grammar — {@code namespace:name}, lowercase ASCII plus
 * {@code _}, {@code .} and {@code -} — and each had its own copy of the validation and of the parse. Two copies of a
 * grammar is how two ids that are supposed to be interchangeable start accepting different strings, so the rule lives
 * here once and both records call it.</p>
 */
public final class IdSegments {

    private IdSegments() {
    }

    /**
     * Rejects a segment this id family cannot carry.
     *
     * @param kind what the segment belongs to, used in the message ("tool id", "capability id")
     * @param what which half it is ("namespace", "name")
     */
    public static void require(String value, String kind, String what) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("a " + kind + " " + what + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_' || character == '.' || character == '-';
            if (!allowed) {
                throw new IllegalArgumentException(
                        "a " + kind + " " + what + " may only contain a-z, 0-9, '_', '.' and '-': " + value);
            }
        }
    }

    /**
     * Splits {@code namespace:name}.
     *
     * @return the two halves, in order
     */
    public static String[] split(String text, String kind) {
        if (text == null) {
            throw new NullPointerException("text");
        }
        int separator = text.indexOf(':');
        if (separator <= 0 || separator == text.length() - 1) {
            throw new IllegalArgumentException("a " + kind + " is namespace:name, got: " + text);
        }
        return new String[] {text.substring(0, separator), text.substring(separator + 1)};
    }
}