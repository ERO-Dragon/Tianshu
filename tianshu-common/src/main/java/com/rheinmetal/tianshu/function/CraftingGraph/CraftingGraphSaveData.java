package com.rheinmetal.tianshu.function.CraftingGraph;

import java.util.ArrayList;
import java.util.List;

public final class CraftingGraphSaveData {
    public int version = 1;
    public long savedAtMillis;
    public List<NodeRecord> nodes = new ArrayList<>();
    public List<EdgeRecord> edges = new ArrayList<>();

    public static final class NodeRecord {
        public String uuid;
        public String parentUuid;
        public RecipePanelNodeType nodeType;
        public String itemId;
        public String displayName;
        public int selectedRecipeIndex;
        public float x;
        public float y;
    }

    public static final class EdgeRecord {
        public String fromNode;
        public String toNode;
        public String itemId;
        public GraphExpansionDirection direction;
        public float parentSlotX = Float.NaN;
        public float parentSlotY = Float.NaN;
        public AnchorRecord fromAnchor;
        public AnchorRecord toAnchor;
    }

    public static final class AnchorRecord {
        public GraphAnchorKind kind;
        public SlotViewType slotType;
        public float x;
        public float y;
        public float offsetX;
        public float offsetY;
    }
}
