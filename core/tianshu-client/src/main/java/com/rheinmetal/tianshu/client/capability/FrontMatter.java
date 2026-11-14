package com.rheinmetal.tianshu.client.capability;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The front-matter block common to text capabilities: {@code ---} at the start, {@code key: value} lines, a closing
 * {@code ---}. It is deliberately minimal; nested YAML is not needed by the standard skill format and pulling a YAML
 * parser into the mod would be a much larger dependency than the format deserves.
 */
public final class FrontMatter {

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
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if ("---".equals(line.strip())) {
                return new FrontMatter(values);
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
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
}
