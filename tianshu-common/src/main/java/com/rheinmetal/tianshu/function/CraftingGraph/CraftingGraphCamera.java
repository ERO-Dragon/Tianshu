package com.rheinmetal.tianshu.function.CraftingGraph;

public final class CraftingGraphCamera {

    private float offsetX;
    private float offsetY;
    private float targetOffsetX;
    private float targetOffsetY;
    private float zoom = 1.0f;
    private float targetZoom = 1.0f;
    private float zoomStart;
    private float zoomTarget;
    private float zoomAnchorScreenX;
    private float zoomAnchorScreenY;
    private float zoomAnchorWorldX;
    private float zoomAnchorWorldY;
    private long zoomAnimationStartMillis;
    private boolean zoomAnimating;

    public void tick() {
        updateZoomAnimation(System.currentTimeMillis());
    }

    public void renderFrame() {
        updateZoomAnimation(System.currentTimeMillis());
    }

    public void pan(float dx, float dy) {
        offsetX += dx;
        offsetY += dy;
        targetOffsetX = offsetX;
        targetOffsetY = offsetY;
    }

    public void zoomAt(float screenX, float screenY, double wheelDelta) {
        updateZoomAnimation(System.currentTimeMillis());
        float oldZoom = zoom;
        float factor = wheelDelta > 0.0 ? CraftingGraphConstants.ZOOM_STEP : 1.0f / CraftingGraphConstants.ZOOM_STEP;
        float newZoom = clamp(targetZoom * factor, CraftingGraphConstants.MIN_ZOOM, CraftingGraphConstants.MAX_ZOOM);
        if (newZoom == targetZoom) return;

        zoomStart = oldZoom;
        zoomTarget = newZoom;
        targetZoom = newZoom;
        zoomAnchorScreenX = screenX;
        zoomAnchorScreenY = screenY;
        zoomAnchorWorldX = (screenX - offsetX) / oldZoom;
        zoomAnchorWorldY = (screenY - offsetY) / oldZoom;
        zoomAnimationStartMillis = System.currentTimeMillis();
        zoomAnimating = true;
        applyZoom(zoomStart);
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
        offsetX = targetOffsetX;
        offsetY = targetOffsetY;
    }

    public float getOffsetX() { return offsetX; }
    public float getOffsetY() { return offsetY; }
    public float getZoom() { return zoom; }
    public float getTargetZoom() { return targetZoom; }

    private void updateZoomAnimation(long now) {
        if (!zoomAnimating) return;
        float nextZoom = computeZoom(now);
        applyZoom(nextZoom);
        if (nextZoom == zoomTarget) {
            zoomAnimating = false;
        }
    }

    private float computeZoom(long now) {
        float distance = Math.abs(zoomTarget - zoomStart);
        if (distance <= 0.0001f) return zoomTarget;
        float direction = zoomTarget > zoomStart ? 1.0f : -1.0f;
        float elapsedSeconds = Math.max(0.0f, (now - zoomAnimationStartMillis) / 1000.0f);
        float acceleration = 4.0f;
        float duration = (float) Math.sqrt(2.0f * distance / acceleration);
        float initialVelocity = acceleration * duration;
        float travelled = initialVelocity * elapsedSeconds - 0.5f * acceleration * elapsedSeconds * elapsedSeconds;
        if (elapsedSeconds >= duration || travelled >= distance) return zoomTarget;
        return zoomStart + direction * Math.max(0.0f, travelled);
    }

    private void applyZoom(float newZoom) {
        zoom = newZoom;
        targetZoom = zoomTarget;
        offsetX = zoomAnchorScreenX - zoomAnchorWorldX * zoom;
        offsetY = zoomAnchorScreenY - zoomAnchorWorldY * zoom;
        targetOffsetX = offsetX;
        targetOffsetY = offsetY;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
