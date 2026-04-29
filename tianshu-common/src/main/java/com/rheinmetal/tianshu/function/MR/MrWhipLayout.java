package com.rheinmetal.tianshu.function.MR;

import java.util.List;

public class MrWhipLayout {

    private float currentJointX = 0.0f;
    private float currentJointY = 0.0f;
    private float currentCardX = 0.0f;
    private float currentCardY = 0.0f;
    private boolean initialized = false;

    private float lastDirectionAngle = -90.0f;
    private boolean hasLastDirection = false;

    private float softBoundAlpha = 1.0f;

    public static class LayoutResult {
        public float jointX;
        public float jointY;
        public float cardX;
        public float cardY;
        public boolean whipBroken;
        public float softBoundAlpha;

        public LayoutResult(float jx, float jy, float cx, float cy, boolean broken, float alpha) {
            this.jointX = jx;
            this.jointY = jy;
            this.cardX = cx;
            this.cardY = cy;
            this.whipBroken = broken;
            this.softBoundAlpha = alpha;
        }
    }

    public LayoutResult compute(
            float anchorX, float anchorY,
            float targetCardX, float targetCardY,
            float scale, float cardWidth, float cardHeight,
            int screenWidth, int screenHeight,
            float deltaTime
    ) {
        if (!initialized) {
            float[] bInit = computeJointPoint(anchorX, anchorY, screenWidth, screenHeight);
            currentJointX = bInit[0];
            currentJointY = bInit[1];
            currentCardX = targetCardX;
            currentCardY = targetCardY;
            initialized = true;
            return new LayoutResult(currentJointX, currentJointY, currentCardX, currentCardY, false, 1.0f);
        }

        float[] bj = computeJointPoint(anchorX, anchorY, screenWidth, screenHeight);
        currentJointX = bj[0];
        currentJointY = bj[1];

        float targetCx = targetCardX;
        float targetCy = currentJointY;

        float factor = MrConstants.DAMPING_FACTOR * deltaTime * 20.0f;
        if (factor > 1.0f) factor = 1.0f;

        currentCardX += (targetCx - currentCardX) * factor;
        currentCardY += (targetCy - currentCardY) * factor;

        float dx = currentCardX - currentJointX;
        float dy = currentCardY - currentJointY;
        float distBC = (float) Math.sqrt(dx * dx + dy * dy);
        boolean whipBroken = distBC > MrConstants.WHIP_KILL_THRESHOLD;

        if (whipBroken) {
            initialized = false;
        }

        if (!MrProjector.isInSoftBounds(anchorX, anchorY, screenWidth, screenHeight)) {
            softBoundAlpha -= deltaTime * 3.0f;
            if (softBoundAlpha < 0.0f) softBoundAlpha = 0.0f;
        } else {
            softBoundAlpha += deltaTime * 3.0f;
            if (softBoundAlpha > 1.0f) softBoundAlpha = 1.0f;
        }

        return new LayoutResult(currentJointX, currentJointY, currentCardX, currentCardY, whipBroken, softBoundAlpha);
    }

    private float[] computeJointPoint(float ax, float ay, int sw, int sh) {
        float normalizedY = ay / (float) sh;

        float directionAngle;
        if (normalizedY < 0.35f) {
            directionAngle = -90.0f;
        } else if (normalizedY > 0.65f) {
            directionAngle = 90.0f;
        } else {
            float t = MrProjector.smoothstep(0.35f, 0.65f, normalizedY);
            if (hasLastDirection) {
                directionAngle = lastDirectionAngle;
            } else {
                directionAngle = normalizedY < 0.5f ? -90.0f : 90.0f;
            }

            float targetAngle = normalizedY < 0.5f ? -90.0f : 90.0f;
            if (hasLastDirection) {
                float diff = targetAngle - directionAngle;
                if (diff > 180.0f) diff -= 360.0f;
                if (diff < -180.0f) diff += 360.0f;
                directionAngle += diff * t;
            } else {
                directionAngle = -90.0f + 180.0f * t;
            }
        }

        lastDirectionAngle = directionAngle;
        hasLastDirection = true;

        double rad = Math.toRadians(directionAngle);
        float bx = ax + (float) Math.cos(rad) * MrConstants.RIGID_SEGMENT_LENGTH;
        float by = ay + (float) Math.sin(rad) * MrConstants.RIGID_SEGMENT_LENGTH;

        return new float[]{bx, by};
    }

    public static void resolveCollisions(List<MrCardSnapshot> cards) {
        int n = cards.size();
        for (int i = 0; i < n; i++) {
            MrCardSnapshot a = cards.get(i);
            for (int j = i + 1; j < n; j++) {
                MrCardSnapshot b = cards.get(j);

                float aLeft = a.cardX;
                float aRight = a.cardX + a.cardWidth * a.scale;
                float aTop = a.cardY;
                float aBottom = a.cardY + a.cardHeight * a.scale;

                float bLeft = b.cardX;
                float bRight = b.cardX + b.cardWidth * b.scale;
                float bTop = b.cardY;
                float bBottom = b.cardY + b.cardHeight * b.scale;

                boolean overlapX = aLeft < bRight && aRight > bLeft;
                boolean overlapY = aTop < bBottom && aBottom > bTop;

                if (overlapX && overlapY) {
                    float overlapH = Math.min(aRight - bLeft, bRight - aLeft);
                    float pushX = overlapH * 0.5f + 2.0f;

                    if (a.cardX <= b.cardX) {
                        a.cardX -= pushX;
                        b.cardX += pushX;
                    } else {
                        a.cardX += pushX;
                        b.cardX -= pushX;
                    }
                }
            }
        }
    }

    public void reset() {
        initialized = false;
        hasLastDirection = false;
        softBoundAlpha = 1.0f;
    }
}
