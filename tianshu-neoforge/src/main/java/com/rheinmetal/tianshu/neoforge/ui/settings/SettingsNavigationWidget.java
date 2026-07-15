package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.neoforge.ui.settings.NeoForgeUiText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class SettingsNavigationWidget extends AbstractWidget {
    private static final int ITEM_HEIGHT = 24;
    private final List<ModuleSettingsCategory> categories;
    private final String selectedModuleId;
    private final Consumer<ModuleSettingsCategory> onSelect;
    private final Predicate<String> dirtyModules;
    private int scrollOffset;

    SettingsNavigationWidget(int x, int y, int width, int height, List<ModuleSettingsCategory> categories, String selectedModuleId, Consumer<ModuleSettingsCategory> onSelect, Predicate<String> dirtyModules) {
        super(x, y, width, height, Component.empty());
        this.categories = categories;
        this.selectedModuleId = selectedModuleId;
        this.onSelect = onSelect;
        this.dirtyModules = dirtyModules == null ? moduleId -> false : dirtyModules;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int visible = Math.max(1, getHeight() / ITEM_HEIGHT);
        int start = Math.max(0, Math.min(scrollOffset, Math.max(0, categories.size() - visible)));
        for (int i = 0; i < visible && start + i < categories.size(); i++) {
            ModuleSettingsCategory category = categories.get(start + i);
            int itemY = getY() + i * ITEM_HEIGHT;
            boolean selected = category.moduleId().equals(selectedModuleId);
            boolean dirty = dirtyModules.test(category.moduleId());
            boolean hovered = mouseX >= getX() && mouseX <= getX() + getWidth() && mouseY >= itemY && mouseY <= itemY + ITEM_HEIGHT;
            int bg = selected ? 0x80333333 : hovered ? 0x33000000 : 0x00000000;
            if (bg != 0) {
                guiGraphics.fill(getX() + 2, itemY + 2, getX() + getWidth() - 2, itemY + ITEM_HEIGHT - 2, bg);
            }
            int textX = getX() + 8;
            if (dirty) {
                guiGraphics.fill(getX() + 4, itemY + 10, getX() + 7, itemY + 13, 0xFFFFCC33);
                textX += 6;
            }
            guiGraphics.drawString(Minecraft.getInstance().font, NeoForgeUiText.toComponent(category.title()), textX, itemY + 8, selected ? 0xFFFFFF : 0xE0E0E0, false);
        }
        drawScrollbar(guiGraphics, visible, start);
    }

    private void drawScrollbar(GuiGraphics guiGraphics, int visible, int start) {
        if (categories.size() <= visible) {
            return;
        }
        int trackX = getX() + getWidth() - 5;
        int trackTop = getY() + 3;
        int trackBottom = getY() + getHeight() - 3;
        int trackHeight = Math.max(1, trackBottom - trackTop);
        int thumbHeight = Math.max(16, trackHeight * visible / Math.max(1, categories.size()));
        int maxStart = Math.max(1, categories.size() - visible);
        int thumbY = trackTop + (trackHeight - thumbHeight) * start / maxStart;
        guiGraphics.fill(trackX, trackTop, trackX + 2, trackBottom, 0x66000000);
        guiGraphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFFB0B0B0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        int index = scrollOffset + (int) ((mouseY - getY()) / ITEM_HEIGHT);
        if (index >= 0 && index < categories.size()) {
            onSelect.accept(categories.get(index));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        int visible = Math.max(1, getHeight() / ITEM_HEIGHT);
        int max = Math.max(0, categories.size() - visible);
        if (verticalAmount < 0) {
            scrollOffset = Math.min(max, scrollOffset + 1);
        } else if (verticalAmount > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        }
        return true;
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
    }
}
