package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.IngredientData;

public final class SlotViewData {

    private final IngredientData item;
    private final SlotViewType type;
    private final float x;
    private final float y;

    public SlotViewData(IngredientData item, SlotViewType type, float x, float y) {
        this.item = item;
        this.type = type;
        this.x = x;
        this.y = y;
    }

    public IngredientData getItem() { return item; }
    public SlotViewType getType() { return type; }
    public float getX() { return x; }
    public float getY() { return y; }
}
