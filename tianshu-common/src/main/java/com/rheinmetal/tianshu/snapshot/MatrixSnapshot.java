package com.rheinmetal.tianshu.snapshot;

public final class MatrixSnapshot {

    private final float[] data;

    public MatrixSnapshot(float[] data) {
        if (data == null || data.length != 16) {
            throw new IllegalArgumentException("Matrix data must be a 16-element column-major array");
        }
        this.data = data.clone();
    }

    public float[] getData() {
        return data.clone();
    }

    public float get(int index) {
        return data[index];
    }
}
