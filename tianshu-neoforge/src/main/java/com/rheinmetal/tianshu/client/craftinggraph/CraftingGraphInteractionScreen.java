package com.rheinmetal.tianshu.client.craftinggraph;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class CraftingGraphInteractionScreen extends Screen {

    private final CraftingGraphController controller;

    public CraftingGraphInteractionScreen(CraftingGraphController controller) {
        super(Component.empty());
        this.controller = controller;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        controller.getRenderer().render(guiGraphics, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return controller.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return controller.mouseReleased(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return controller.mouseDragged(mouseX, mouseY, button, dragX, dragY) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalScroll, double verticalScroll) {
        return controller.mouseScrolled(mouseX, mouseY, verticalScroll) || super.mouseScrolled(mouseX, mouseY, horizontalScroll, verticalScroll);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            controller.exitGameEdit();
            this.minecraft.setScreen(null);
            return false;
        }
        if (controller.keyPressed(keyCode)) return true;
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return controller.charTyped(codePoint);
    }

    @Override
    public void onClose() {
        if (!controller.isGameEditLocked()) {
            controller.exitGameEdit();
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
