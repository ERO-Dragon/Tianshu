package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.asr.audio.AsrAudioDiagnostics;
import com.rheinmetal.tianshu.neoforge.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Small, debug-build-only waveform monitor for tuning the ASR VAD. */
public final class AsrVadDebugOverlay {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 116;
    private static final int PANEL_LEFT = 8;
    private static final int PANEL_TOP = 8;
    private static final int CHART_LEFT = PANEL_LEFT + 28;
    private static final int CHART_TOP = PANEL_TOP + 34;
    private static final int CHART_WIDTH = 238;
    private static final int CHART_HEIGHT = 68;
    private static final int CHART_CENTER = CHART_TOP + CHART_HEIGHT / 2;
    private static final int VALUE_LEFT = CHART_LEFT + CHART_WIDTH + 12;
    private static final float FIXED_AMPLITUDE_SCALE = 0.10F;
    private static final int RAW_COLOR = 0xCC63D8FF;
    private static final int PROCESSED_COLOR = 0xCCFF9BD6;
    private static final int START_THRESHOLD_COLOR = 0xFFFFD166;
    private static final int STOP_THRESHOLD_COLOR = 0xFFA5E887;
    private static final int GRID_COLOR = 0x805E7180;
    private static final int LABEL_COLOR = 0xFFB6C7D0;

    private final ClientConfig config;
    private final TianshuCoreManager coreManager;

    public AsrVadDebugOverlay(ClientConfig config, TianshuCoreManager coreManager) {
        this.config = config;
        this.coreManager = coreManager;
    }

    public void render(GuiGraphics graphics) {
        if (graphics == null || config == null || coreManager == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        AsrAudioDiagnostics diagnostics = coreManager.findService(AsrAudioDiagnostics.class).orElse(null);
        if (diagnostics == null) {
            return;
        }
        boolean visible = config.isDebugEnabled()
                && minecraft.player != null
                && minecraft.level != null
                && minecraft.screen == null;
        diagnostics.setEnabled(visible);
        if (!visible) {
            return;
        }

        AsrAudioDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        Font font = minecraft.font;
        graphics.fill(PANEL_LEFT, PANEL_TOP, PANEL_LEFT + PANEL_WIDTH, PANEL_TOP + PANEL_HEIGHT, 0xB00A1018);
        graphics.renderOutline(PANEL_LEFT, PANEL_TOP, PANEL_WIDTH, PANEL_HEIGHT, 0xD06E8A99);
        graphics.drawString(font, Component.translatable("tianshu.debug.asr_vad.title"), PANEL_LEFT + 8, PANEL_TOP + 5, 0xFFE5F4F7, false);
        graphics.drawString(font, Component.translatable(
                "tianshu.debug.asr_vad.metadata",
                Component.translatable(snapshot.speaking()
                        ? "tianshu.debug.asr_vad.speaking"
                        : "tianshu.debug.asr_vad.silent")
        ), PANEL_LEFT + 8, PANEL_TOP + 18, snapshot.speaking() ? 0xFFFFD166 : 0xFFB6C7D0, false);
        drawChart(graphics, snapshot);
        drawScaleLabels(graphics, font);
        drawLatestValues(graphics, font, snapshot);
    }

    private static void drawChart(GuiGraphics graphics, AsrAudioDiagnostics.Snapshot snapshot) {
        graphics.fill(CHART_LEFT, CHART_TOP, CHART_LEFT + CHART_WIDTH, CHART_TOP + CHART_HEIGHT, 0x80131F29);
        graphics.fill(CHART_LEFT, CHART_CENTER, CHART_LEFT + CHART_WIDTH, CHART_CENTER + 1, GRID_COLOR);
        graphics.fill(CHART_LEFT, CHART_TOP, CHART_LEFT + CHART_WIDTH, CHART_TOP + 1, GRID_COLOR);
        graphics.fill(CHART_LEFT, CHART_TOP + CHART_HEIGHT - 1, CHART_LEFT + CHART_WIDTH, CHART_TOP + CHART_HEIGHT, GRID_COLOR);
        drawSortedWaveforms(graphics, snapshot);
        drawThresholdCurve(graphics, snapshot.startThresholds(), snapshot.sampleCount(), START_THRESHOLD_COLOR);
        drawThresholdCurve(graphics, snapshot.stopThresholds(), snapshot.sampleCount(), STOP_THRESHOLD_COLOR);
    }

    private static void drawSortedWaveforms(GuiGraphics graphics, AsrAudioDiagnostics.Snapshot snapshot) {
        int count = snapshot.sampleCount();
        if (count <= 0) {
            return;
        }
        for (int index = 0; index < count; index++) {
            int x = sampleX(index, count);
            float rawHeight = envelopeHeight(snapshot.rawMin()[index], snapshot.rawMax()[index]);
            float processedHeight = envelopeHeight(snapshot.processedMin()[index], snapshot.processedMax()[index]);
            if (rawHeight > processedHeight) {
                drawWaveformColumn(graphics, x, snapshot.rawMin()[index], snapshot.rawMax()[index], RAW_COLOR);
                drawWaveformColumn(graphics, x, snapshot.processedMin()[index], snapshot.processedMax()[index], PROCESSED_COLOR);
            } else {
                drawWaveformColumn(graphics, x, snapshot.processedMin()[index], snapshot.processedMax()[index], PROCESSED_COLOR);
                drawWaveformColumn(graphics, x, snapshot.rawMin()[index], snapshot.rawMax()[index], RAW_COLOR);
            }
        }
    }

    private static void drawWaveformColumn(GuiGraphics graphics, int x, float min, float max, int color) {
        int yMin = waveformY(max);
        int yMax = waveformY(min);
        if (yMax < yMin) {
            int swap = yMin;
            yMin = yMax;
            yMax = swap;
        }
        graphics.fill(x, yMin, x + 2, Math.min(CHART_TOP + CHART_HEIGHT, yMax + 1), color);
    }

    private static void drawThresholdCurve(GuiGraphics graphics, float[] thresholds, int sampleCount, int color) {
        if (sampleCount <= 0 || thresholds.length == 0) {
            return;
        }
        int count = Math.min(sampleCount, thresholds.length);
        int previousX = CHART_LEFT;
        int previousUpper = thresholdY(thresholds[0], true);
        int previousLower = thresholdY(thresholds[0], false);
        graphics.fill(previousX, previousUpper, previousX + 1, previousUpper + 1, color);
        graphics.fill(previousX, previousLower, previousX + 1, previousLower + 1, color);
        for (int index = 1; index < count; index++) {
            int x = sampleX(index, count);
            int upper = thresholdY(thresholds[index], true);
            int lower = thresholdY(thresholds[index], false);
            drawCurveSegment(graphics, previousX, previousUpper, x, upper, color);
            drawCurveSegment(graphics, previousX, previousLower, x, lower, color);
            previousX = x;
            previousUpper = upper;
            previousLower = lower;
        }
    }

    private static void drawScaleLabels(GuiGraphics graphics, Font font) {
        graphics.drawString(font, Component.translatable("tianshu.debug.asr_vad.scale.high"), PANEL_LEFT + 5, CHART_TOP - 3, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable("tianshu.debug.asr_vad.scale.zero"), PANEL_LEFT + 12, CHART_CENTER - 4, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable("tianshu.debug.asr_vad.scale.low"), PANEL_LEFT + 5, CHART_TOP + CHART_HEIGHT - 7, LABEL_COLOR, false);
    }

    private static void drawLatestValues(GuiGraphics graphics, Font font, AsrAudioDiagnostics.Snapshot snapshot) {
        int y = CHART_TOP;
        drawValue(graphics, font, "tianshu.debug.asr_vad.latest.start", snapshot.startThreshold(), y, START_THRESHOLD_COLOR);
        drawValue(graphics, font, "tianshu.debug.asr_vad.latest.stop", snapshot.stopThreshold(), y + 12, STOP_THRESHOLD_COLOR);
        drawValue(graphics, font, "tianshu.debug.asr_vad.latest.raw", snapshot.rawRms(), y + 28, RAW_COLOR);
        drawValue(graphics, font, "tianshu.debug.asr_vad.latest.processed", snapshot.processedRms(), y + 40, PROCESSED_COLOR);
    }

    private static void drawValue(GuiGraphics graphics, Font font, String key, double value, int y, int color) {
        graphics.drawString(font, Component.translatable(key, format(value)), VALUE_LEFT, y, color, false);
    }

    private static int sampleX(int index, int count) {
        return CHART_LEFT + Math.min(CHART_WIDTH - 1,
                Math.round(index * (CHART_WIDTH - 1.0F) / Math.max(1, count - 1)));
    }

    private static int thresholdY(float threshold, boolean upper) {
        float normalized = Math.max(0.0F, Math.min(1.0F, threshold / FIXED_AMPLITUDE_SCALE));
        float signed = upper ? normalized : -normalized;
        return Math.max(CHART_TOP, Math.min(CHART_TOP + CHART_HEIGHT - 1,
                Math.round(CHART_CENTER - signed * (CHART_HEIGHT / 2.0F))));
    }

    private static int waveformY(float value) {
        float normalized = Math.max(-1.0F, Math.min(1.0F, value / FIXED_AMPLITUDE_SCALE));
        return Math.max(CHART_TOP, Math.min(CHART_TOP + CHART_HEIGHT - 1,
                Math.round(CHART_CENTER - normalized * (CHART_HEIGHT / 2.0F))));
    }

    private static float envelopeHeight(float min, float max) {
        return Math.max(Math.abs(min), Math.abs(max));
    }

    private static void drawCurveSegment(GuiGraphics graphics, int fromX, int fromY, int toX, int toY, int color) {
        int distance = Math.max(1, toX - fromX);
        for (int x = fromX; x <= toX; x++) {
            float progress = (x - fromX) / (float) distance;
            int y = Math.round(fromY + (toY - fromY) * progress);
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
