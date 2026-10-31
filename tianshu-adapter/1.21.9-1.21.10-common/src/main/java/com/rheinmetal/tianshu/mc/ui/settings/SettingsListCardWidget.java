package com.rheinmetal.tianshu.mc.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.SettingsListCard;
import com.rheinmetal.tianshu.mc.ui.settings.UiTexts;
import com.rheinmetal.tianshu.client.api.text.UiText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
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
    /** Title, status and badges are resolved once: row widgets outlive many frames, and each frame used to re-split them. */
    private final Component title;
    private final Component status;
    private final int statusWidth;
    private final FormattedCharSequence titleLine;
    private final List<Component> badges;
    private final List<FormattedCharSequence> detailLines;
    private final Runnable onClick;

    SettingsListCardWidget(int x, int y, int width, int height, SettingsListCard card, Runnable onClick) {
        super(x, y, width, height, card == null ? Component.empty() : UiTexts.toComponent(card.title()));
        this.card = card == null ? SettingsListCard.text(UiText.literal("")) : card;
        Font font = Minecraft.getInstance().font;
        this.title = UiTexts.toComponent(this.card.title());
        this.status = UiTexts.toComponent(this.card.status());
        this.statusWidth = status.getString().isBlank() ? 0 : font.width(status) + 8;
        List<FormattedCharSequence> titleLines = font.split(title, Math.max(1, width - PADDING_X * 2 - statusWidth));
        this.titleLine = titleLines.isEmpty() ? null : titleLines.get(0);
        this.badges = resolveBadges(this.card);
        this.detailLines = wrapDetails(font, this.card, width);
        this.onClick = onClick;
        this.active = false;
    }

    private static List<Component> resolveBadges(SettingsListCard card) {
        List<Component> resolved = new ArrayList<>();
        for (UiText badgeText : card.badges()) {
            Component badge = UiTexts.toComponent(badgeText);
            if (badge != null && !badge.getString().isBlank()) {
                resolved.add(badge);
            }
        }
        return List.copyOf(resolved);
    }

    static int heightFor(Font font, SettingsListCard card, int width) {
        SettingsListCard safeCard = card == null ? SettingsListCard.text(UiText.literal("")) : card;
        int titleLines = Math.max(1, font.split(UiTexts.toComponent(safeCard.title()), Math.max(1, width - PADDING_X * 2)).size());
        int detailLines = Math.min(4, wrapDetails(font, safeCard, width).size());
        int badgeRows = safeCard.badges().isEmpty() ? 0 : 1;
        return PADDING_Y * 2 + Math.min(2, titleLines) * LINE_HEIGHT + detailLines * LINE_HEIGHT + badgeRows * (LINE_HEIGHT + 4) + 5;
    }

    private static List<FormattedCharSequence> wrapDetails(Font font, SettingsListCard card, int width) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        int textWidth = Math.max(1, width - PADDING_X * 2);
        for (UiText detail : card.details()) {
            lines.addAll(font.split(UiTexts.toComponent(detail), textWidth));
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
        if (titleLine != null) {
            guiGraphics.drawString(font, titleLine, textX, textY, TITLE_COLOR, false);
            textY += LINE_HEIGHT;
        }
        if (statusWidth > 0) {
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

    private int badgeHeight() {
        return card.badges().isEmpty() ? 0 : LINE_HEIGHT + 2;
    }

    private void drawBadges(GuiGraphics guiGraphics, Font font, int x, int y, int right) {
        if (badges.isEmpty()) {
            return;
        }
        int badgeX = x;
        for (Component badge : badges) {
            int width = font.width(badge) + 10;
            if (badgeX + width > right) {
                break;
            }
            guiGraphics.drawString(font, badge, badgeX, y, BADGE_COLOR, false);
            badgeX += width + 8;
        }
    }

    /** 1.21.9 hands the click over as one event record. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (active && isMouseOver(event.x(), event.y()) && onClick != null) {
            onClick.run();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
    }
}
