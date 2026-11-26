package com.rheinmetal.tianshu.api.capability.skill;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The front-matter block common to text capabilities: {@code ---} at the start, {@code key: value} lines, a closing
 * {@code ---}. It is deliberately minimal; pulling a YAML parser into the mod would be a much larger dependency than
 * the format deserves.
 *
 * <p>One level of nesting is supported, because the skill format has one place that legitimately needs it:
 * {@code metadata} is the map the specification reserves for a platform's own data, so a key inside it is addressed as
 * {@code metadata.share}. A key with no value of its own opens that section; an indented key below it joins the section.
 * Deeper nesting stays unsupported on purpose — nothing needs it, and a real parser is the answer if that changes.</p>
 */
public final class FrontMatter {

    /**
     * The address of a row's sharing declaration: {@code metadata.share}.
     *
     * <p>{@code metadata} is the map the specification reserves for a platform's own data, so this is where the base's
     * one extra field lives — a top-level {@code share:} would make the file unportable, which is what happened with
     * the self-invented {@code covers:} before it.</p>
     */
    public static final String SHARE = "metadata.share";

    private final Map<String, String> values;

    private FrontMatter(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static FrontMatter parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].strip())) {
            throw new IllegalArgumentException("text capability must start with a --- front matter block");
        }
        String section = null;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if ("---".equals(line.strip())) {
                return new FrontMatter(values);
            }
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            boolean indented = line.startsWith(" ") || line.startsWith("\t");
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = unquote(line.substring(colon + 1).strip());
            if (value.isEmpty() && !indented) {
                section = key;
                continue;
            }
            if (indented && section != null) {
                values.put(section + "." + key, value);
                continue;
            }
            section = null;
            values.put(key, value);
        }
        throw new IllegalArgumentException("front matter is not closed with ---");
    }

    public String get(String key) {
        return values.get(key);
    }

    /** Everything after the closing delimiter; the whole text when there is no front matter. */
    public static String body(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].strip())) {
            return text;
        }
        for (int index = 1; index < lines.length; index++) {
            if ("---".equals(lines[index].strip())) {
                return String.join("\n", java.util.Arrays.copyOfRange(lines, index + 1, lines.length));
            }
        }
        return text;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}