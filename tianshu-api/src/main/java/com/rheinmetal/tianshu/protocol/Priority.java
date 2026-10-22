package com.rheinmetal.tianshu.protocol;

public enum Priority {
    CRITICAL(500),
    HIGH(400),
    NORMAL(300),
    LOW(200),
    BACKGROUND(100);

    private final int weight;

    Priority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public boolean atLeast(Priority other) {
        return weight >= other.weight;
    }
}
