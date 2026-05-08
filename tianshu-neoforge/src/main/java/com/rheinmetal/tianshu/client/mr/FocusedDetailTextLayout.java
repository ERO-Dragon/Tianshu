package com.rheinmetal.tianshu.client.mr;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FocusedDetailTextLayout {

    private FocusedDetailTextLayout() {}

    public static Result layout(Font font, String text, int visibleChars, int maxWidth, int maxLines) {
        if (font == null || text == null || text.isEmpty() || visibleChars <= 0 || maxWidth <= 0 || maxLines <= 0) {
            return new Result(Collections.emptyList(), false);
        }
        int safeChars = Math.max(0, Math.min(visibleChars, text.length()));
        List<String> lines = wrapText(font, text.substring(0, safeChars), maxWidth);
        boolean clipped = lines.size() > maxLines;
        if (clipped) {
            lines = new ArrayList<>(lines.subList(0, maxLines));
        }
        return new Result(lines, clipped);
    }

    public static int countWrappedLines(String text, int maxWidth, float averageCharWidth) {
        if (text == null || text.isEmpty()) return 0;
        float safeCharWidth = Math.max(1.0f, averageCharWidth);
        int maxCharsPerLine = Math.max(1, (int) (Math.max(1, maxWidth) / safeCharWidth));
        int lines = 0;
        String[] explicitLines = text.split("\n", -1);
        for (String line : explicitLines) {
            lines += Math.max(1, (line.length() + maxCharsPerLine - 1) / maxCharsPerLine);
        }
        return Math.max(1, lines);
    }

    private static List<String> wrapText(Font font, String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        String[] explicitLines = text.split("\n", -1);
        for (String explicitLine : explicitLines) {
            appendWrappedLine(font, explicitLine, maxWidth, result);
        }
        if (result.isEmpty()) result.add("");
        return result;
    }

    private static void appendWrappedLine(Font font, String line, int maxWidth, List<String> result) {
        if (line.isEmpty()) {
            result.add("");
            return;
        }
        int start = 0;
        while (start < line.length()) {
            int end = line.length();
            while (end > start + 1 && font.width(line.substring(start, end)) > maxWidth) {
                end--;
            }
            result.add(line.substring(start, end));
            start = end;
        }
    }

    public static final class Result {
        private final List<String> lines;
        private final boolean clipped;

        private Result(List<String> lines, boolean clipped) {
            this.lines = Collections.unmodifiableList(lines);
            this.clipped = clipped;
        }

        public List<String> getLines() {
            return lines;
        }

        public boolean isClipped() {
            return clipped;
        }
    }
}
