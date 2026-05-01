package com.rheinmetal.tianshu.function.MR;

public final class MrProjector {

    private MrProjector() {}

    public static float[] project(
            double worldX, double worldY, double worldZ,
            float[] modelViewMatrix,
            float[] projectionMatrix,
            int screenWidth, int screenHeight
    ) {
        if (modelViewMatrix == null || modelViewMatrix.length != 16) return null;
        if (projectionMatrix == null || projectionMatrix.length != 16) return null;

        float x = (float) worldX;
        float y = (float) worldY;
        float z = (float) worldZ;
        float w = 1.0f;

        float vx = modelViewMatrix[0] * x + modelViewMatrix[4] * y + modelViewMatrix[8]  * z + modelViewMatrix[12] * w;
        float vy = modelViewMatrix[1] * x + modelViewMatrix[5] * y + modelViewMatrix[9]  * z + modelViewMatrix[13] * w;
        float vz = modelViewMatrix[2] * x + modelViewMatrix[6] * y + modelViewMatrix[10] * z + modelViewMatrix[14] * w;
        float vw = modelViewMatrix[3] * x + modelViewMatrix[7] * y + modelViewMatrix[11] * z + modelViewMatrix[15] * w;

        if (vw != 0.0f) {
            vx /= vw;
            vy /= vw;
            vz /= vw;
            vw = 1.0f;
        }

        float cx = projectionMatrix[0] * vx + projectionMatrix[4] * vy + projectionMatrix[8]  * vz + projectionMatrix[12] * vw;
        float cy = projectionMatrix[1] * vx + projectionMatrix[5] * vy + projectionMatrix[9]  * vz + projectionMatrix[13] * vw;
        float cw = projectionMatrix[3] * vx + projectionMatrix[7] * vy + projectionMatrix[11] * vz + projectionMatrix[15] * vw;

        if (cw <= 0.001f) return null;

        float ndcX = cx / cw;
        float ndcY = cy / cw;

        if (ndcX < -3.0f || ndcX > 3.0f || ndcY < -3.0f || ndcY > 3.0f) return null;

        float screenX = (ndcX * 0.5f + 0.5f) * screenWidth;
        float screenY = (1.0f - (ndcY * 0.5f + 0.5f)) * screenHeight;

        return new float[]{screenX, screenY};
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }
}
