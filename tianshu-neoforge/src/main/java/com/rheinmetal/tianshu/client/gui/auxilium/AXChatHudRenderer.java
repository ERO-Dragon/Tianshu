package com.rheinmetal.tianshu.client.gui.auxilium;

import com.rheinmetal.tianshu.function.auxilium.output.AXOutputMode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public final class AXChatHudRenderer {
    private static final int MAX_WIDTH = 360;
    private static final int MAX_LINES = 4;
    private static final int TEXT_COLOR = 0xFFEAF7FF;

    private final AXChatHudState state;
    private final AXClientOutputConfig config;
    private final AXHudLogoRenderer logoRenderer = new AXHudLogoRenderer();

    public AXChatHudRenderer(AXChatHudState state, AXClientOutputConfig config) {
        this.state = state;
        this.config = config;
    }

    public void render(GuiGraphics graphics, float partialTick) {
        AXOutputMode outputMode = config == null ? AXOutputMode.DISABLED : config.outputMode();
        if (state == null || outputMode == null || !outputMode.uiEnabled()) {
            return;
        }
        AXChatHudState.Snapshot snapshot = state.snapshot();
        if (snapshot.empty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        Font font = minecraft.font;
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        int width = Math.min(MAX_WIDTH, Math.max(180, screenWidth - 48));
        int centerX = screenWidth / 2;
        int logoBottom = screenHeight - 34;
        logoRenderer.render(graphics, centerX, logoBottom, partialTick);

        List<FormattedCharSequence> lines = font.split(net.minecraft.network.chat.Component.literal(snapshot.text()), width);
        if (lines.size() > MAX_LINES) {
            lines = lines.subList(Math.max(0, lines.size() - MAX_LINES), lines.size());
        }
        int lineHeight = font.lineHeight + 2;
        int textHeight = lines.size() * lineHeight;
        int panelLeft = centerX - width / 2 - 8;
        int panelRight = centerX + width / 2 + 8;
        int panelBottom = logoBottom - logoRenderer.reservedHeight();
        int panelTop = panelBottom - textHeight - 10;
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0x8A071116);
        graphics.fill(panelLeft, panelBottom - 1, panelRight, panelBottom, snapshot.active() ? 0xD0A7F3FF : 0x90F5D06F);
        int y = panelTop + 6;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, centerX - width / 2, y, TEXT_COLOR, true);
            y += lineHeight;
        }
    }
}
