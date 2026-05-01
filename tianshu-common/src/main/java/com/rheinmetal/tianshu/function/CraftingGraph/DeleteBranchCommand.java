package com.rheinmetal.tianshu.function.CraftingGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class DeleteBranchCommand {

    private final List<RecipePanelNode> deletedNodes;
    private final List<RecipeGraphEdge> deletedEdges;

    public DeleteBranchCommand(List<RecipePanelNode> deletedNodes, List<RecipeGraphEdge> deletedEdges) {
        this.deletedNodes = deletedNodes != null ? Collections.unmodifiableList(new ArrayList<>(deletedNodes)) : Collections.emptyList();
        this.deletedEdges = deletedEdges != null ? Collections.unmodifiableList(new ArrayList<>(deletedEdges)) : Collections.emptyList();
    }

    public List<RecipePanelNode> getDeletedNodes() {
        return deletedNodes;
    }

    public List<RecipeGraphEdge> getDeletedEdges() {
        return deletedEdges;
    }

    public boolean isEmpty() {
        return deletedNodes.isEmpty() && deletedEdges.isEmpty();
    }

    public UUID getRootNodeUuid() {
        return deletedNodes.isEmpty() ? null : deletedNodes.get(0).getUuid();
    }
}
