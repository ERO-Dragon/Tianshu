package com.rheinmetal.tianshu.fabric.mixin;

import com.rheinmetal.tianshu.fabric.ScreenWidgetAccess;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Makes every screen able to hand a widget to itself.
 *
 * <p>{@code addRenderableWidget} is protected and Fabric offers no screen event that adds a widget for us. This
 * mixin adds nothing to the game's behaviour: it forwards exactly one call to the method that already exists, so
 * the host can place one button on the pause screen.</p>
 *
 * <p>NeoForge needs none of this: its screen event hands the host an {@code addListener}.</p>
 */
@Mixin(Screen.class)
public abstract class ScreenWidgetMixin implements ScreenWidgetAccess {

    @Shadow
    protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

    @Override
    public <T extends GuiEventListener & Renderable & NarratableEntry> T tianshu$addWidget(T widget) {
        return addRenderableWidget(widget);
    }
}