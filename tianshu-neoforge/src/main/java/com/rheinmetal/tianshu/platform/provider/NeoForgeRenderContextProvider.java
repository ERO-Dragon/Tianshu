package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IRenderContextProvider;
import com.rheinmetal.tianshu.snapshot.MatrixSnapshot;
import com.rheinmetal.tianshu.snapshot.TooltipRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class NeoForgeRenderContextProvider implements IRenderContextProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final AtomicReference<TooltipRect> cachedTooltipRect = new AtomicReference<>(null);
    private volatile long tooltipTimestamp = 0;
    private static final long TOOLTIP_EXPIRY_MS = 200;

    public NeoForgeRenderContextProvider() {
        NeoForge.EVENT_BUS.addListener(this::onTooltipPre);
        NeoForge.EVENT_BUS.addListener(this::onTooltipColor);
    }

    private void onTooltipPre(RenderTooltipEvent.Pre event) {
        try {
            int x = event.getX();
            int y = event.getY();
            Font font = event.getFont();

            int maxWidth = 0;
            int totalHeight = 0;
            List<ClientTooltipComponent> lines = event.getComponents();
            if (lines != null) {
                for (ClientTooltipComponent line : lines) {
                    try {
                        int w = line.getWidth(font);
                        if (w > maxWidth) maxWidth = w;
                        totalHeight += line.getHeight();
                    } catch (Exception ignored) {}
                }
            }

            int tooltipWidth = maxWidth + 8;
            int tooltipHeight = totalHeight + 6;

            cachedTooltipRect.set(new TooltipRect(x, y, tooltipWidth, tooltipHeight));
            tooltipTimestamp = System.currentTimeMillis();
        } catch (Exception e) {
            LOGGER.warn("捕获Tooltip矩形失败: {}", e.getMessage());
        }
    }

    private void onTooltipColor(RenderTooltipEvent.Color event) {
        try {
            int x = event.getX();
            int y = event.getY();

            cachedTooltipRect.set(new TooltipRect(x, y,
                    cachedTooltipRect.get() != null ? cachedTooltipRect.get().width : 0,
                    cachedTooltipRect.get() != null ? cachedTooltipRect.get().height : 0));
            tooltipTimestamp = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    @Override
    public TooltipRect getActiveTooltipRect() {
        TooltipRect rect = cachedTooltipRect.get();
        if (rect == null) return null;
        if (System.currentTimeMillis() - tooltipTimestamp > TOOLTIP_EXPIRY_MS) {
            cachedTooltipRect.set(null);
            return null;
        }
        return rect;
    }

    @Override
    public int getScreenWidth() {
        try {
            return Minecraft.getInstance().getWindow().getGuiScaledWidth();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int getScreenHeight() {
        try {
            return Minecraft.getInstance().getWindow().getGuiScaledHeight();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public MatrixSnapshot getProjectionMatrix() {
        try {
            float[] matrix = new float[16];
            GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, matrix);
            return new MatrixSnapshot(matrix);
        } catch (Exception e) {
            LOGGER.warn("获取投影矩阵失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public MatrixSnapshot getModelViewMatrix() {
        try {
            float[] matrix = new float[16];
            GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, matrix);
            return new MatrixSnapshot(matrix);
        } catch (Exception e) {
            LOGGER.warn("获取模型视图矩阵失败: {}", e.getMessage());
            return null;
        }
    }
}
