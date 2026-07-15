package com.rheinmetal.tianshu.neoforge.ui.settings;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

final class SettingsSlider extends AbstractSliderButton {
    private final Component label;
    private final double min;
    private final double max;
    private final Consumer<Double> onChange;
    private final boolean showLabel;
    private double actualValue;

    SettingsSlider(int x, int y, int width, int height, Component label, double value, double min, double max, Consumer<Double> onChange) {
        this(x, y, width, height, label, value, min, max, onChange, true);
    }

    SettingsSlider(int x, int y, int width, int height, Component label, double value, double min, double max, Consumer<Double> onChange, boolean showLabel) {
        super(x, y, width, height, Component.empty(), normalize(value, min, max));
        this.label = label == null ? Component.empty() : label;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        this.showLabel = showLabel;
        this.actualValue = clamp(value, min, max);
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        String valueLabel = String.format("%.2f", actualValue);
        setMessage(showLabel && !label.getString().isBlank()
                ? Component.literal(label.getString() + ": " + valueLabel)
                : Component.literal(valueLabel));
    }

    @Override
    protected void applyValue() {
        actualValue = min + (max - min) * value;
        if (onChange != null) {
            onChange.accept(actualValue);
        }
        updateMessage();
    }

    private static double normalize(double value, double min, double max) {
        if (max <= min) {
            return 0.0;
        }
        return (clamp(value, min, max) - min) / (max - min);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
