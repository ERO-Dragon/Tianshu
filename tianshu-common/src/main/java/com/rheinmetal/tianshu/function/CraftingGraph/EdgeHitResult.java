package com.rheinmetal.tianshu.function.CraftingGraph;

public final class EdgeHitResult {

    private final RecipeGraphEdge edge;
    private final float x;
    private final float y;

    public EdgeHitResult(RecipeGraphEdge edge, float x, float y) {
        this.edge = edge;
        this.x = x;
        this.y = y;
    }

    public RecipeGraphEdge getEdge() {
        return edge;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }
}
