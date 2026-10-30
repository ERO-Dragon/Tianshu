package com.rheinmetal.tianshu.fabric;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

/**
 * "Add this widget to that screen", stated without naming a mixin.
 *
 * <p>The type a screen is cast to at runtime must be an ordinary interface: a mixin class may never be loaded
 * by name, so the code that adds a widget talks to this and the mixin is only what makes a screen implement
 * it.</p>
 */
public interface ScreenWidgetAccess {

    <T extends GuiEventListener & Renderable & NarratableEntry> T tianshu$addWidget(T widget);
}