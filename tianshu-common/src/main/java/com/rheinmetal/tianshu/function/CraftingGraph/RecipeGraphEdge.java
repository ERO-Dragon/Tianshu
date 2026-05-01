package com.rheinmetal.tianshu.function.CraftingGraph;

import java.util.Objects;
import java.util.UUID;

public final class RecipeGraphEdge {

    private final UUID fromNode;
    private final UUID toNode;
    private final String itemId;
    private final GraphExpansionDirection direction;
    private final GraphAnchorData fromAnchor;
    private final GraphAnchorData toAnchor;

    public RecipeGraphEdge(UUID fromNode, UUID toNode, String itemId, GraphExpansionDirection direction, GraphAnchorData fromAnchor, GraphAnchorData toAnchor) {
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.itemId = itemId;
        this.direction = direction;
        this.fromAnchor = fromAnchor;
        this.toAnchor = toAnchor;
    }

    public UUID getFromNode() { return fromNode; }
    public UUID getToNode() { return toNode; }
    public String getItemId() { return itemId; }
    public GraphExpansionDirection getDirection() { return direction; }
    public GraphAnchorData getFromAnchor() { return fromAnchor; }
    public GraphAnchorData getToAnchor() { return toAnchor; }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RecipeGraphEdge other)) return false;
        return Objects.equals(fromNode, other.fromNode)
                && Objects.equals(toNode, other.toNode)
                && Objects.equals(itemId, other.itemId)
                && direction == other.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromNode, toNode, itemId, direction);
    }
}
