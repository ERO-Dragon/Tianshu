package com.rheinmetal.tianshu.function.MR;

public final class MrProjector {

    private MrProjector() {}

    public static float[] project(
            double worldX, double worldY, double worldZ,
            double playerX, double playerY, double playerZ,
            float yaw, float pitch,
            float[] projMatrix, float[] mvMatrix,
            int screenWidth, int screenHeight
    ) {
        float relX = (float) (worldX - playerX);
        float relY = (float) (worldY - playerY);
        float relZ = (float) (worldZ - playerZ);

        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        float cosY = (float) Math.cos(yawRad);
        float sinY = (float) Math.sin(yawRad);
        float cosP = (float) Math.cos(pitchRad);
        float sinP = (float) Math.sin(pitchRad);

        float rx = relX * cosY + relZ * sinY;
        float ry = relY;
        float rz = -relX * sinY + relZ * cosY;

        float ey = ry * cosP - rz * sinP;
        float ez = ry * sinP + rz * cosP;

        if (ez >= -0.1f) return null;

        float[] m = mvMatrix != null ? mvMatrix : projMatrix;
        if (m == null || m.length != 16) return null;

        float clipX = m[0] * rx + m[4] * ey + m[8] * ez + m[12];
        float clipY = m[1] * rx + m[5] * ey + m[9] * ez + m[13];
        float clipW = m[3] * rx + m[7] * ey + m[11] * ez + m[15];

        if (clipW <= 0.001f) return null;

        float ndcX = clipX / clipW;
        float ndcY = clipY / clipW;

        if (ndcX < -1.5f || ndcX > 1.5f || ndcY < -1.5f || ndcY > 1.5f) return null;

        float screenX = (ndcX * 0.5f + 0.5f) * screenWidth;
        float screenY = (1.0f - (ndcY * 0.5f + 0.5f)) * screenHeight;

        return new float[]{screenX, screenY};
    }

    public static boolean isInSoftBounds(float sx, float sy, int sw, int sh) {
        float marginPxX = sw * MrConstants.SOFT_MARGIN_PERCENT;
        float marginPxY = sh * MrConstants.SOFT_MARGIN_PERCENT;
        return sx >= marginPxX && sx <= sw - marginPxX
                && sy >= marginPxY && sy <= sh - marginPxY;
    }

    public static boolean isInHardBounds(float sx, float sy, int sw, int sh) {
        float marginPxX = sw * MrConstants.HARD_MARGIN_PERCENT;
        float marginPxY = sh * MrConstants.HARD_MARGIN_PERCENT;
        return sx >= marginPxX && sx <= sw - marginPxX
                && sy >= marginPxY && sy <= sh - marginPxY;
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }
}
