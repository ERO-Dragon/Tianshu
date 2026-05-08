package com.rheinmetal.tianshu.client.chatassistant;

import com.rheinmetal.tianshu.client.GuiGeometryBatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

public final class ChatAssistantOverlayRenderer {
    private static final int INPUT_TOTAL_MS = 5_000;
    private static final int PANEL_BG = 0xCC050B14;
    private static final int PANEL_BORDER = 0xCC55FFFF;
    private static final int PANEL_ACCENT = 0xFF55FFFF;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFB9D6E8;
    private static final int TEXT_WARNING = 0xFFFFD166;
    private static final GuiGeometryBatch GEOMETRY_BATCH = new GuiGeometryBatch();

    private ChatAssistantOverlayRenderer() {
    }

    public static void render(GuiGraphics guiGraphics, ChatAssistantClientState state) {
        if (state == null) {
            return;
        }
        ChatAssistantClientState.Snapshot snapshot = state.snapshot();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null || minecraft.getWindow() == null) {
            return;
        }
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        if (snapshot.open()) {
            renderInput(guiGraphics, minecraft.font, snapshot, screenWidth, screenHeight);
            return;
        }
        if (!snapshot.hint().isBlank()) {
            renderHint(guiGraphics, minecraft.font, snapshot.hint(), screenWidth, screenHeight);
        }
    }

    private static void renderHint(GuiGraphics guiGraphics, Font font, String hint, int screenWidth, int screenHeight) {
        int textWidth = font.width(hint);
        int width = Math.min(screenWidth - 32, textWidth + 24);
        int x = (screenWidth - width) / 2;
        int y = screenHeight - 70;
        GEOMETRY_BATCH.begin();
        GEOMETRY_BATCH.fill(x, y, x + width, y + 22, PANEL_BG);
        GEOMETRY_BATCH.rectOutline(x, y, x + width, y + 22, 1, PANEL_BORDER);
        GEOMETRY_BATCH.flush(guiGraphics);
        guiGraphics.drawString(font, hint, x + (width - textWidth) / 2, y + 7, TEXT_PRIMARY, false);
    }

    private static void renderInput(GuiGraphics guiGraphics, Font font, ChatAssistantClientState.Snapshot snapshot, int screenWidth, int screenHeight) {
        int width = Math.max(160, Math.min(380, screenWidth - 32));
        List<String> lines = wrapText(font, displayText(snapshot), width - 20, 3);
        int contentHeight = Math.max(16, lines.size() * 10);
        int height = 48 + contentHeight;
        int x = (screenWidth - width) / 2;
        int y = screenHeight - height - 42;
        GEOMETRY_BATCH.begin();
        GEOMETRY_BATCH.fill(x, y, x + width, y + height, PANEL_BG);
        GEOMETRY_BATCH.rectOutline(x, y, x + width, y + height, 1, PANEL_BORDER);
        GEOMETRY_BATCH.flush(guiGraphics);
        guiGraphics.drawString(font, "通语 · 语音聊天", x + 10, y + 8, PANEL_ACCENT, false);
        String status = statusText(snapshot);
        guiGraphics.drawString(font, status, x + width - 10 - font.width(status), y + 8, TEXT_SECONDARY, false);
        int textY = y + 24;
        int color = snapshot.text().isBlank() ? TEXT_SECONDARY : TEXT_PRIMARY;
        for (String line : lines) {
            guiGraphics.drawString(font, line, x + 10, textY, color, false);
            textY += 10;
        }
        renderProgress(guiGraphics, snapshot, x + 10, y + height - 12, width - 20);
    }

    private static String displayText(ChatAssistantClientState.Snapshot snapshot) {
        if (!snapshot.text().isBlank()) {
            return snapshot.text();
        }
        return "请说出要发送的聊天内容，说“发送”确认，“取消”撤销，“重来”清空";
    }

    private static String statusText(ChatAssistantClientState.Snapshot snapshot) {
        long remaining = Math.max(0L, snapshot.deadlineAtMillis() - System.currentTimeMillis());
        if (remaining <= 0L) {
            return "即将关闭";
        }
        return Math.max(1L, (remaining + 999L) / 1_000L) + "s";
    }

    private static void renderProgress(GuiGraphics guiGraphics, ChatAssistantClientState.Snapshot snapshot, int x, int y, int width) {
        long remaining = Math.max(0L, snapshot.deadlineAtMillis() - System.currentTimeMillis());
        float progress = Math.min(1.0F, remaining / (float) INPUT_TOTAL_MS);
        int color = remaining <= 1_500L ? TEXT_WARNING : PANEL_ACCENT;
        int barWidth = Math.max(0, Math.round(width * progress));
        GEOMETRY_BATCH.begin();
        GEOMETRY_BATCH.fill(x, y, x + width, y + 3, 0x55203040);
        GEOMETRY_BATCH.fill(x, y, x + barWidth, y + 3, color);
        GEOMETRY_BATCH.flush(guiGraphics);
    }

    private static List<String> wrapText(Font font, String text, int maxWidth, int maxLines) {
        List<String> result = new ArrayList<>();
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            result.add("");
            return result;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            String candidate = line.toString() + c;
            if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                result.add(line.toString());
                line.setLength(0);
                if (result.size() >= maxLines) {
                    return ellipsize(font, result, maxWidth);
                }
            }
            line.append(c);
        }
        if (!line.isEmpty() && result.size() < maxLines) {
            result.add(line.toString());
        }
        return ellipsize(font, result, maxWidth);
    }

    private static List<String> ellipsize(Font font, List<String> lines, int maxWidth) {
        if (lines.isEmpty()) {
            return lines;
        }
        int lastIndex = lines.size() - 1;
        String last = lines.get(lastIndex);
        while (font.width(last + "…") > maxWidth && !last.isEmpty()) {
            last = last.substring(0, last.length() - 1);
        }
        if (!last.equals(lines.get(lastIndex))) {
            lines.set(lastIndex, last + "…");
        }
        return lines;
    }
}
