package com.rheinmetal.tianshu.function.CraftingGraph;

public final class GraphAnchorData {

    private final GraphAnchorKind kind;
    private final SlotViewType slotType;
    private final float x;
    private final float y;
    private final float offsetX;
    private final float offsetY;

    public GraphAnchorData(GraphAnchorKind kind, SlotViewType slotType, float x, float y, float offsetX) {
        this(kind, slotType, x, y, offsetX, 0.0f);
    }

    public GraphAnchorData(GraphAnchorKind kind, SlotViewType slotType, float x, float y, float offsetX, float offsetY) {
        this.kind = kind != null ? kind : GraphAnchorKind.NODE_CENTER;
        this.slotType = slotType;
        this.x = x;
        this.y = y;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    public static GraphAnchorData nodeCenter(RecipePanelNode node) {
        return new GraphAnchorData(
                GraphAnchorKind.NODE_CENTER,
                null,
                node.getX() + CraftingGraphConstants.NODE_WIDTH * 0.5f,
                node.getY() + CraftingGraphConstants.NODE_HEIGHT * 0.5f,
                0.0f,
                0.0f
        );
    }

    public static GraphAnchorData slotCenter(RecipePanelNode node, SlotViewData slot, float offsetX, float offsetY) {
        return new GraphAnchorData(
                GraphAnchorKind.SLOT_CENTER,
                slot != null ? slot.getType() : null,
                node.getX() + 10.0f + slot.getX() + 8.0f,
                node.getY() + 24.0f + slot.getY() + 8.0f,
                offsetX,
                offsetY
        );
    }

    public GraphAnchorKind getKind() { return kind; }
    public SlotViewType getSlotType() { return slotType; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getOffsetX() { return offsetX; }
    public float getOffsetY() { return offsetY; }
}
