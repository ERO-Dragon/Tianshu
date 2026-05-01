package com.rheinmetal.tianshu.function.MR;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MrWhipLayout {

    private float currentJointX = 0.0f;
    private float currentJointY = 0.0f;
    private float currentCardX = 0.0f;
    private float currentCardY = 0.0f;
    private boolean initialized = false;

    public static class LayoutResult {
        public float jointX;
        public float jointY;
        public float cardX;
        public float cardY;

        public LayoutResult(float jx, float jy, float cx, float cy) {
            this.jointX = jx;
            this.jointY = jy;
            this.cardX = cx;
            this.cardY = cy;
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
            float[] bInit = computeJointPoint(anchorX, anchorY, scale, screenWidth, screenHeight);
            currentJointX = bInit[0];
            currentJointY = bInit[1];
            currentCardX = targetCardX;
            currentCardY = targetCardY;
            initialized = true;
            return new LayoutResult(currentJointX, currentJointY, currentCardX, currentCardY);
        }

        float[] bj = computeJointPoint(anchorX, anchorY, scale, screenWidth, screenHeight);
        currentJointX = bj[0];
        currentJointY = bj[1];

        float dx = targetCardX - currentCardX;
        float dy = targetCardY - currentCardY;
        float distToTarget = (float) Math.sqrt(dx * dx + dy * dy);
        float dynamicDamping = MrConstants.DAMPING_FACTOR * (1.0f + distToTarget / MrConstants.DAMPING_DISTANCE_REFERENCE);
        dynamicDamping = Math.min(MrConstants.DAMPING_MAX_FACTOR, dynamicDamping);
        float factor = dynamicDamping * deltaTime * 20.0f;
        if (factor > 1.0f) factor = 1.0f;

        currentCardX += dx * factor;
        currentCardY += dy * factor;

        return new LayoutResult(currentJointX, currentJointY, currentCardX, currentCardY);
    }

    private float[] computeJointPoint(float ax, float ay, float scale, int sw, int sh) {
        float normalizedY = ay / (float) sh;
        float segmentLength = MrConstants.RIGID_SEGMENT_LENGTH * scale;
        float bx;
        float by;

        if (normalizedY < 0.2f) {
            bx = ax;
            by = ay + segmentLength;
        } else if (normalizedY > 0.8f) {
            bx = ax;
            by = ay - segmentLength;
        } else {
            float centerX = sw * 0.5f;
            float direction = ax < centerX ? 1.0f : -1.0f;
            bx = ax + direction * segmentLength;
            by = ay;
        }

        return new float[]{bx, by};
    }

    public static void resolveCollisions(List<MrCardSnapshot> cards) {
        if (cards.size() <= 1) return;

        List<MrCardSnapshot> sorted = new ArrayList<>(cards);
        Collections.sort(sorted, Comparator
                .comparing((MrCardSnapshot c) -> !c.isFocused)
                .thenComparingDouble(c -> c.cardY + c.cardHeight * 0.5f));

        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < sorted.size(); i++) {
                MrCardSnapshot a = sorted.get(i);
                float aCenterY = a.cardY + a.cardHeight * 0.5f;

                for (int j = i + 1; j < sorted.size(); j++) {
                    MrCardSnapshot b = sorted.get(j);
                    float bCenterY = b.cardY + b.cardHeight * 0.5f;

                    float aLeft = a.cardX;
                    float aRight = a.cardX + a.cardWidth;
                    float aTop = a.cardY;
                    float aBottom = a.cardY + a.cardHeight;

                    float bLeft = b.cardX;
                    float bRight = b.cardX + b.cardWidth;
                    float bTop = b.cardY;
                    float bBottom = b.cardY + b.cardHeight;

                    boolean overlapX = aLeft < bRight && aRight > bLeft;
                    boolean overlapY = aTop < bBottom && aBottom > bTop;

                    if (overlapX && overlapY) {
                        if (a.isFocused && !b.isFocused) {
                            float pushY = pushAwayFromFocusedY(a, b);
                            b.cardY += pushY;
                        } else if (!a.isFocused && b.isFocused) {
                            float pushY = pushAwayFromFocusedY(b, a);
                            a.cardY += pushY;
                        } else {
                            float horizontalGuideY = a.cardY + a.cardHeight * 0.5f;

                            if (bCenterY >= horizontalGuideY) {
                                float pushY = (a.cardY + a.cardHeight) - b.cardY + 2.0f;
                                b.cardY += pushY;
                            } else {
                                float pushY = b.cardY + b.cardHeight - a.cardY + 2.0f;
                                a.cardY += pushY;
                            }
                        }
                    }
                }
            }
        }

        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < sorted.size(); i++) {
                MrCardSnapshot a = sorted.get(i);
                for (int j = i + 1; j < sorted.size(); j++) {
                    MrCardSnapshot b = sorted.get(j);

                    float aLeft = a.cardX;
                    float aRight = a.cardX + a.cardWidth;
                    float aTop = a.cardY;
                    float aBottom = a.cardY + a.cardHeight;

                    float bLeft = b.cardX;
                    float bRight = b.cardX + b.cardWidth;
                    float bTop = b.cardY;
                    float bBottom = b.cardY + b.cardHeight;

                    boolean overlapX = aLeft < bRight && aRight > bLeft;
                    boolean overlapY = aTop < bBottom && aBottom > bTop;

                    if (overlapX && overlapY) {
                        if (a.isFocused && !b.isFocused) {
                            b.cardX = pushAwayFromFocusedX(a, b);
                        } else if (!a.isFocused && b.isFocused) {
                            a.cardX = pushAwayFromFocusedX(b, a);
                        } else if (a.cardX <= b.cardX) {
                            b.cardX = aRight + 2.0f;
                        } else {
                            a.cardX = bRight + 2.0f;
                        }
                    }
                }
            }
        }
    }

    private static float pushAwayFromFocusedY(MrCardSnapshot focused, MrCardSnapshot other) {
        float focusedCenterY = focused.cardY + focused.cardHeight * 0.5f;
        float otherCenterY = other.cardY + other.cardHeight * 0.5f;
        float spacing = 2.0f;
        if (otherCenterY >= focusedCenterY) {
            return focused.cardY + focused.cardHeight - other.cardY + spacing;
        }
        return focused.cardY - (other.cardY + other.cardHeight) - spacing;
    }

    private static float pushAwayFromFocusedX(MrCardSnapshot focused, MrCardSnapshot other) {
        float focusedCenterX = focused.cardX + focused.cardWidth * 0.5f;
        float otherCenterX = other.cardX + other.cardWidth * 0.5f;
        float spacing = 2.0f;
        if (otherCenterX >= focusedCenterX) {
            return focused.cardX + focused.cardWidth + spacing;
        }
        return focused.cardX - other.cardWidth - spacing;
    }

    public void reset() {
        initialized = false;
    }
}
