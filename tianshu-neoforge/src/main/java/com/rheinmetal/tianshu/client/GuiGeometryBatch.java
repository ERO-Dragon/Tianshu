package com.rheinmetal.tianshu.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

public class GuiGeometryBatch {
    private static final int RECT_STRIDE = 5;
    private int[] rectData = new int[512 * RECT_STRIDE];
    private int rectCount;

    public void begin() {
        rectCount = 0;
    }

    public void fill(float left, float top, float right, float bottom, int color) {
        if (((color >>> 24) & 0xFF) == 0) return;
        int x1 = Math.round(Math.min(left, right));
        int y1 = Math.round(Math.min(top, bottom));
        int x2 = Math.round(Math.max(left, right));
        int y2 = Math.round(Math.max(top, bottom));
        if (x1 == x2 || y1 == y2) return;
        ensureCapacity(rectCount + 1);
        int index = rectCount * RECT_STRIDE;
        rectData[index] = x1;
        rectData[index + 1] = y1;
        rectData[index + 2] = x2;
        rectData[index + 3] = y2;
        rectData[index + 4] = color;
        rectCount++;
    }

    public void line(int x1, int y1, int x2, int y2, int width, int color) {
        if (x1 == x2 && y1 == y2) return;
        if (width <= 0) width = 1;

        if (x1 == x2) {
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            int hw = width / 2;
            fill(x1 - hw, minY, x1 - hw + width, maxY + 1, color);
        } else if (y1 == y2) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int hh = width / 2;
            fill(minX, y1 - hh, maxX + 1, y1 - hh + width, color);
        } else {
            line(x1, y1, x2, y1, width, color);
            line(x2, y1, x2, y2, width, color);
        }
    }

    public void pixelLine(int x1, int y1, int x2, int y2, int color) {
        if (x1 == x2 || y1 == y2) {
            line(x1, y1, x2, y2, 1, color);
            return;
        }
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps == 0) {
            fill(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = Math.round(x1 + (x2 - x1) * (i / (float) steps));
            int y = Math.round(y1 + (y2 - y1) * (i / (float) steps));
            fill(x, y, x + 1, y + 1, color);
        }
    }

    public void rectOutline(int left, int top, int right, int bottom, int width, int color) {
        line(left, top, right, top, width, color);
        line(right, top, right, bottom, width, color);
        line(right, bottom, left, bottom, width, color);
        line(left, bottom, left, top, width, color);
    }

    public void flush(GuiGraphics g) {
        if (rectCount == 0) return;
        Matrix4f matrix = g.pose().last().pose();
        VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i < rectCount; i++) {
            int index = i * RECT_STRIDE;
            int left = rectData[index];
            int top = rectData[index + 1];
            int right = rectData[index + 2];
            int bottom = rectData[index + 3];
            int color = rectData[index + 4];
            consumer.addVertex(matrix, left, top, 0.0f).setColor(color);
            consumer.addVertex(matrix, left, bottom, 0.0f).setColor(color);
            consumer.addVertex(matrix, right, bottom, 0.0f).setColor(color);
            consumer.addVertex(matrix, right, top, 0.0f).setColor(color);
        }
        g.flush();
        rectCount = 0;
    }

    private void ensureCapacity(int targetRectCount) {
        int target = targetRectCount * RECT_STRIDE;
        if (target <= rectData.length) return;
        int next = rectData.length;
        while (next < target) next *= 2;
        int[] expanded = new int[next];
        System.arraycopy(rectData, 0, expanded, 0, rectCount * RECT_STRIDE);
        rectData = expanded;
    }
}
