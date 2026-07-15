package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.SettingsListCard;
import com.rheinmetal.tianshu.neoforge.ui.settings.NeoForgeUiText;
import com.rheinmetal.tianshu.client.api.text.UiText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

final class SettingsListCardWidget extends AbstractWidget {
    private static final int PADDING_X = 7;
    private static final int PADDING_Y = 7;
    private static final int LINE_HEIGHT = 9;
    private static final int TITLE_COLOR = 0xFFFFFF;
    private static final int STATUS_COLOR = 0xFFE0A8;
    private static final int DETAIL_COLOR = 0xC8C8C8;
    private static final int BADGE_COLOR = 0xB8D7FF;
    private static final int BACKGROUND = 0x18000000;
    private static final int HOVER_BACKGROUND = 0x26000000;
    private static final int BORDER_LIGHT = 0x33FFFFFF;
    private static final int BORDER_DARK = 0x55000000;
    private final SettingsListCard card;
    private final List<FormattedCharSequence> detailLines;
    private final Runnable onClick;

    SettingsListCardWidget(int x, int y, int width, int height, SettingsListCard card, Runnable onClick) {
        super(x, y, width, height, card == null ? Component.empty() : NeoForgeUiText.toComponent(card.title()));
        this.card = card == null ? SettingsListCard.text(UiText.literal("")) : card;
        this.detailLines = wrapDetails(Minecraft.getInstance().font, this.card, width);
        this.onClick = onClick;
        this.active = false;
    }

    static int heightFor(Font font, SettingsListCard card, int width) {
        SettingsListCard safeCard = card == null ? SettingsListCard.text(UiText.literal("")) : card;
        int titleLines = Math.max(1, font.split(NeoForgeUiText.toComponent(safeCard.title()), Math.max(1, width - PADDING_X * 2)).size());
        int detailLines = Math.min(4, wrapDetails(font, safeCard, width).size());
        int badgeRows = safeCard.badges().isEmpty() ? 0 : 1;
        return PADDING_Y * 2 + Math.min(2, titleLines) * LINE_HEIGHT + detailLines * LINE_HEIGHT + badgeRows * (LINE_HEIGHT + 4) + 5;
    }

    private static List<FormattedCharSequence> wrapDetails(Font font, SettingsListCard card, int width) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        int textWidth = Math.max(1, width - PADDING_X * 2);
        for (UiText detail : card.details()) {
            lines.addAll(font.split(NeoForgeUiText.toComponent(detail), textWidth));
        }
        return lines;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int right = getX() + getWidth();
        int bottom = getY() + getHeight();
        guiGraphics.fill(getX(), getY(), right, bottom, isHoveredOrFocused() ? HOVER_BACKGROUND : BACKGROUND);
        guiGraphics.fill(getX(), getY(), right, getY() + 1, BORDER_LIGHT);
        guiGraphics.fill(getX(), getY(), getX() + 1, bottom, BORDER_LIGHT);
        guiGraphics.fill(getX(), bottom - 1, right, bottom, BORDER_DARK);
        guiGraphics.fill(right - 1, getY(), right, bottom, BORDER_DARK);

        Font font = Minecraft.getInstance().font;
        int textX = getX() + PADDING_X;
        int textY = getY() + PADDING_Y;
        int contentRight = right - PADDING_X;
        int titleWidth = Math.max(1, getWidth() - PADDING_X * 2 - statusWidth(font));
        for (FormattedCharSequence titleLine : font.split(NeoForgeUiText.toComponent(card.title()), titleWidth)) {
            guiGraphics.drawString(font, titleLine, textX, textY, TITLE_COLOR, false);
            textY += LINE_HEIGHT;
            break;
        }
        Component status = NeoForgeUiText.toComponent(card.status());
        if (!status.getString().isBlank()) {
            int statusX = Math.max(textX, contentRight - font.width(status));
            guiGraphics.drawString(font, status, statusX, getY() + PADDING_Y, STATUS_COLOR, false);
        }
        int maxDetailLines = Math.max(0, Math.min(4, (bottom - textY - PADDING_Y - badgeHeight()) / LINE_HEIGHT));
        for (int i = 0; i < Math.min(maxDetailLines, detailLines.size()); i++) {
            guiGraphics.drawString(font, detailLines.get(i), textX, textY, DETAIL_COLOR, false);
            textY += LINE_HEIGHT;
        }
        drawBadges(guiGraphics, font, textX, bottom - PADDING_Y - LINE_HEIGHT, contentRight);
    }

    private int statusWidth(Font font) {
        Component status = NeoForgeUiText.toComponent(card.status());
        return status.getString().isBlank() ? 0 : font.width(status) + 8;
    }

    private int badgeHeight() {
        return card.badges().isEmpty() ? 0 : LINE_HEIGHT + 2;
    }

    private void drawBadges(GuiGraphics guiGraphics, Font font, int x, int y, int right) {
        if (card.badges().isEmpty()) {
            return;
        }
        int badgeX = x;
        for (UiText badgeText : card.badges()) {
            Component badge = NeoForgeUiText.toComponent(badgeText);
            if (badge == null || badge.getString().isBlank()) {
                continue;
            }
            int width = font.width(badge) + 10;
            if (badgeX + width > right) {
                break;
            }
            guiGraphics.drawString(font, badge, badgeX, y, BADGE_COLOR, false);
            badgeX += width + 8;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (active && clicked(mouseX, mouseY) && onClick != null) {
            onClick.run();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
    }
}
