package com.rheinmetal.tianshu.function.CraftingGraph;

public final class CraftingGraphCamera {

    private float offsetX;
    private float offsetY;
    private float targetOffsetX;
    private float targetOffsetY;
    private float zoom = 1.0f;
    private float targetZoom = 1.0f;

    public void tick() {
        offsetX += (targetOffsetX - offsetX) * CraftingGraphConstants.CAMERA_LERP;
        offsetY += (targetOffsetY - offsetY) * CraftingGraphConstants.CAMERA_LERP;
        zoom += (targetZoom - zoom) * CraftingGraphConstants.CAMERA_LERP;
    }

    public void pan(float dx, float dy) {
        targetOffsetX += dx;
        targetOffsetY += dy;
    }

    public void zoomAt(float screenX, float screenY, double wheelDelta) {
        float oldZoom = targetZoom;
        float factor = wheelDelta > 0.0 ? CraftingGraphConstants.ZOOM_STEP : 1.0f / CraftingGraphConstants.ZOOM_STEP;
        float newZoom = clamp(oldZoom * factor, CraftingGraphConstants.MIN_ZOOM, CraftingGraphConstants.MAX_ZOOM);
        if (newZoom == oldZoom) return;

        float worldX = (screenX - targetOffsetX) / oldZoom;
        float worldY = (screenY - targetOffsetY) / oldZoom;
        targetZoom = newZoom;
        targetOffsetX = screenX - worldX * newZoom;
        targetOffsetY = screenY - worldY * newZoom;
    }

    public float screenToWorldX(float screenX) {
        return (screenX - offsetX) / zoom;
    }

    public float screenToWorldY(float screenY) {
        return (screenY - offsetY) / zoom;
    }

    public void focusWorldPoint(float worldX, float worldY, int screenW, int screenH) {
        targetOffsetX = screenW * 0.5f - worldX * targetZoom;
        targetOffsetY = screenH * 0.5f - worldY * targetZoom;
    }

    public float getOffsetX() { return offsetX; }
    public float getOffsetY() { return offsetY; }
    public float getZoom() { return zoom; }
    public float getTargetZoom() { return targetZoom; }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
