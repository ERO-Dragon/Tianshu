package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record AXMemoryPosition(double x, double y, double z) {
    private static final Pattern CSV_POSITION = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)\\s*[,，]\\s*(-?\\d+(?:\\.\\d+)?)\\s*[,，]\\s*(-?\\d+(?:\\.\\d+)?)"
    );
    private static final Pattern XYZ_POSITION = Pattern.compile(
            "(?i)x\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?).*?y\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?).*?z\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?)"
    );

    static Optional<AXMemoryPosition> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Optional<AXMemoryPosition> xyz = parse(text, XYZ_POSITION);
        if (xyz.isPresent()) {
            return xyz;
        }
        return parse(text, CSV_POSITION);
    }

    double distanceTo(AXMemoryPosition other) {
        if (other == null) {
            return 0.0D;
        }
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Optional<AXMemoryPosition> parse(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AXMemoryPosition(
                    Double.parseDouble(matcher.group(1)),
                    Double.parseDouble(matcher.group(2)),
                    Double.parseDouble(matcher.group(3))
            ));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
