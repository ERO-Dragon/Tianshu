package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.provider.IInventoryDataProvider;
import com.rheinmetal.tianshu.provider.IRecipeDataProvider;
import com.rheinmetal.tianshu.snapshot.IngredientData;
import com.rheinmetal.tianshu.snapshot.ItemSnapshot;
import com.rheinmetal.tianshu.snapshot.RecipeData;
import com.rheinmetal.tianshu.snapshot.RecipeTreeData;

import java.util.*;

public final class CraftingGraphEngine {

    private final IRecipeDataProvider recipeProvider;
    private final IInventoryDataProvider inventoryProvider;
    private final RecipeAdapterRegistry adapterRegistry = new RecipeAdapterRegistry();
    private final InventoryAnalyzer inventoryAnalyzer = new InventoryAnalyzer();
    private final CraftingGraphCamera camera = new CraftingGraphCamera();
    private final Map<UUID, RecipePanelNode> nodes = new LinkedHashMap<>();
    private final List<RecipePanelNode> nodeOrder = new ArrayList<>();
    private final List<RecipeGraphEdge> edges = new ArrayList<>();
    private final Deque<DeleteBranchCommand> undoStack = new ArrayDeque<>();

    private CraftingGraphInteractionMode mode = CraftingGraphInteractionMode.PASSIVE;
    private boolean expanded;
    private boolean draggingCamera;
    private long middleDownAtMillis;
    private boolean dirty;
    private float drawerProgress;
    private float drawerAnimationStartProgress;
    private float drawerAnimationTargetProgress;
    private long drawerAnimationStartMillis;
    private boolean drawerAnimationRunning;
    private float customAlpha = 1.0f;

    public CraftingGraphEngine(IRecipeDataProvider recipeProvider, IInventoryDataProvider inventoryProvider) {
        this.recipeProvider = recipeProvider;
        this.inventoryProvider = inventoryProvider;
    }

    public void tick() {
        updateDrawerAnimation(System.currentTimeMillis());
        camera.tick();
    }

    public RecipePanelNode createRoot(String itemId, String displayName, RecipePanelNodeType nodeType, int screenW, int screenH) {
        RecipePanelNodeType effectiveType = nodeType != null ? nodeType : RecipePanelNodeType.SOURCE;
        RecipePanelNode existing = findRoot(itemId, effectiveType);
        int drawerW = Math.max(CraftingGraphConstants.DRAWER_MIN_WIDTH, Math.min(CraftingGraphConstants.DRAWER_MAX_WIDTH, screenW / 3));
        if (existing != null) {
            focusNode(existing, drawerW, screenH, System.currentTimeMillis());
            return existing;
        }

        RecipeTreeData tree = effectiveType == RecipePanelNodeType.USAGE ? recipeProvider.getUsageTree(itemId) : recipeProvider.getRecipeTree(itemId);
        int rootCount = rootCount();
        int col = rootCount % 2;
        int row = rootCount / 2;
        float x = col * (CraftingGraphConstants.NODE_WIDTH + CraftingGraphConstants.NODE_GAP_X);
        float y = row * (CraftingGraphConstants.NODE_HEIGHT + CraftingGraphConstants.NODE_GAP_Y);
        return createRootNode(itemId, displayName, effectiveType, tree, x, y, drawerW, screenH);
    }

    public RecipePanelNode createSingleRootGraph(String itemId, String displayName, RecipePanelNodeType nodeType, int screenW, int screenH) {
        clearGraph();
        RecipePanelNodeType effectiveType = nodeType != null ? nodeType : RecipePanelNodeType.SOURCE;
        RecipeTreeData tree = effectiveType == RecipePanelNodeType.USAGE ? recipeProvider.getUsageTree(itemId) : recipeProvider.getRecipeTree(itemId);
        int drawerW = Math.max(CraftingGraphConstants.DRAWER_MIN_WIDTH, Math.min(CraftingGraphConstants.DRAWER_MAX_WIDTH, screenW / 3));
        return createRootNode(itemId, displayName, effectiveType, tree, 0.0f, 0.0f, drawerW, screenH);
    }

    private RecipePanelNode createRootNode(String itemId, String displayName, RecipePanelNodeType nodeType, RecipeTreeData tree, float x, float y, int drawerW, int screenH) {
        UUID uuid = UUID.randomUUID();
        RecipePanelNode node = new RecipePanelNode(
                uuid,
                null,
                nodeType,
                itemId,
                displayName,
                tree.getRecipes(),
                x,
                y
        );
        node.setSelectedRecipeIndex(inventoryAnalyzer.bestRecipeIndex(node.getRecipes(), inventoryProvider != null ? inventoryProvider.getAllInventoryItemsData() : null));
        nodes.put(uuid, node);
        nodeOrder.add(node);
        camera.focusWorldPoint(node.getX() + CraftingGraphConstants.NODE_WIDTH * 0.5f, node.getY() + CraftingGraphConstants.NODE_HEIGHT * 0.5f, drawerW, screenH);
        dirty = true;
        return node;
    }

    public RecipePanelNode focusNodeByUuid(UUID uuid, int viewportW, int viewportH, long nowMillis) {
        RecipePanelNode node = nodes.get(uuid);
        focusNode(node, viewportW, viewportH, nowMillis);
        return node;
    }

    public void clearGraph() {
        nodes.clear();
        nodeOrder.clear();
        edges.clear();
        undoStack.clear();
        dirty = true;
    }

    private RecipePanelNode findRoot(String itemId, RecipePanelNodeType nodeType) {
        if (itemId == null || itemId.isBlank()) return null;
        for (RecipePanelNode node : nodeOrder) {
            if (node.getParentUuid() == null && itemId.equals(node.getItemId()) && node.getNodeType() == nodeType) return node;
        }
        return null;
    }

    private int rootCount() {
        int count = 0;
        for (RecipePanelNode node : nodeOrder) {
            if (node.getParentUuid() == null) count++;
        }
        return count;
    }

    public boolean deleteBranch(UUID rootUuid) {
        DeleteBranchCommand command = createDeleteBranchCommand(rootUuid);
        if (command.isEmpty()) return false;
        applyDeleteCommand(command);
        undoStack.push(command);
        dirty = true;
        return true;
    }

    public boolean deleteBranchFromEdge(RecipeGraphEdge edge) {
        if (edge == null) return false;
        UUID childUuid = childNodeUuid(edge);
        return childUuid != null && deleteBranch(childUuid);
    }

    public boolean undoDeleteBranch() {
        if (undoStack.isEmpty()) return false;
        DeleteBranchCommand command = undoStack.pop();
        for (RecipePanelNode node : command.getDeletedNodes()) {
            nodes.put(node.getUuid(), node);
            nodeOrder.add(node);
        }
        edges.addAll(command.getDeletedEdges());
        recomputeAnchorLocks();
        dirty = true;
        return true;
    }

    public CraftingGraphSaveData createSaveData() {
        CraftingGraphSaveData data = new CraftingGraphSaveData();
        data.savedAtMillis = System.currentTimeMillis();
        for (RecipePanelNode node : nodeOrder) {
            CraftingGraphSaveData.NodeRecord record = new CraftingGraphSaveData.NodeRecord();
            record.uuid = node.getUuid().toString();
            record.parentUuid = node.getParentUuid() != null ? node.getParentUuid().toString() : null;
            record.nodeType = node.getNodeType();
            record.itemId = node.getItemId();
            record.displayName = node.getDisplayName();
            record.selectedRecipeIndex = node.getSelectedRecipeIndex();
            record.x = node.getX();
            record.y = node.getY();
            data.nodes.add(record);
        }
        for (RecipeGraphEdge edge : edges) {
            CraftingGraphSaveData.EdgeRecord record = new CraftingGraphSaveData.EdgeRecord();
            record.fromNode = edge.getFromNode().toString();
            record.toNode = edge.getToNode().toString();
            record.itemId = edge.getItemId();
            record.direction = edge.getDirection();
            record.parentSlotX = edge.getParentSlotX();
            record.parentSlotY = edge.getParentSlotY();
            record.fromAnchor = anchorRecord(edge.getFromAnchor());
            record.toAnchor = anchorRecord(edge.getToAnchor());
            data.edges.add(record);
        }
        return data;
    }

    public void restoreSaveData(CraftingGraphSaveData data) {
        nodes.clear();
        nodeOrder.clear();
        edges.clear();
        undoStack.clear();
        if (data != null) {
            for (CraftingGraphSaveData.NodeRecord record : data.nodes) {
                UUID uuid = UUID.fromString(record.uuid);
                UUID parentUuid = record.parentUuid != null ? UUID.fromString(record.parentUuid) : null;
                RecipeTreeData tree = record.nodeType == RecipePanelNodeType.USAGE ? recipeProvider.getUsageTree(record.itemId) : recipeProvider.getRecipeTree(record.itemId);
                RecipePanelNode node = new RecipePanelNode(uuid, parentUuid, record.nodeType, record.itemId, record.displayName, tree.getRecipes(), record.x, record.y);
                node.setSelectedRecipeIndex(record.selectedRecipeIndex);
                nodes.put(uuid, node);
                nodeOrder.add(node);
            }
            for (CraftingGraphSaveData.EdgeRecord record : data.edges) {
                UUID fromNode = UUID.fromString(record.fromNode);
                UUID toNode = UUID.fromString(record.toNode);
                if (nodes.containsKey(fromNode) && nodes.containsKey(toNode)) {
                    edges.add(new RecipeGraphEdge(fromNode, toNode, record.itemId, record.direction, record.parentSlotX, record.parentSlotY, anchorData(record.fromAnchor), anchorData(record.toAnchor)));
                }
            }
        }
        recomputeAnchorLocks();
        dirty = true;
    }

    private CraftingGraphSaveData.AnchorRecord anchorRecord(GraphAnchorData anchor) {
        CraftingGraphSaveData.AnchorRecord record = new CraftingGraphSaveData.AnchorRecord();
        record.kind = anchor.getKind();
        record.slotType = anchor.getSlotType();
        record.x = anchor.getX();
        record.y = anchor.getY();
        record.offsetX = anchor.getOffsetX();
        record.offsetY = anchor.getOffsetY();
        return record;
    }

    private GraphAnchorData anchorData(CraftingGraphSaveData.AnchorRecord record) {
        return new GraphAnchorData(record.kind, record.slotType, record.x, record.y, record.offsetX, record.offsetY);
    }

    private DeleteBranchCommand createDeleteBranchCommand(UUID rootUuid) {
        if (rootUuid == null || !nodes.containsKey(rootUuid)) return new DeleteBranchCommand(Collections.emptyList(), Collections.emptyList());
        Set<UUID> branchUuids = collectBranchUuids(rootUuid);
        List<RecipePanelNode> deletedNodes = new ArrayList<>();
        for (UUID uuid : branchUuids) {
            RecipePanelNode node = nodes.get(uuid);
            if (node != null) deletedNodes.add(node);
        }
        List<RecipeGraphEdge> deletedEdges = new ArrayList<>();
        for (RecipeGraphEdge edge : edges) {
            if (branchUuids.contains(edge.getFromNode()) || branchUuids.contains(edge.getToNode())) deletedEdges.add(edge);
        }
        return new DeleteBranchCommand(deletedNodes, deletedEdges);
    }

    private Set<UUID> collectBranchUuids(UUID rootUuid) {
        Set<UUID> result = new LinkedHashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(rootUuid);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (!result.add(current)) continue;
            for (RecipeGraphEdge edge : edges) {
                UUID child = childNodeUuid(edge);
                UUID parent = parentNodeUuid(edge);
                if (current.equals(parent) && child != null && !result.contains(child)) queue.add(child);
            }
        }
        return result;
    }

    private void applyDeleteCommand(DeleteBranchCommand command) {
        Set<UUID> deletedUuids = new HashSet<>();
        for (RecipePanelNode node : command.getDeletedNodes()) {
            deletedUuids.add(node.getUuid());
        }
        edges.removeIf(edge -> deletedUuids.contains(edge.getFromNode()) || deletedUuids.contains(edge.getToNode()));
        for (UUID uuid : deletedUuids) {
            nodes.remove(uuid);
        }
        nodeOrder.removeIf(node -> deletedUuids.contains(node.getUuid()));
        recomputeAnchorLocks();
    }

    private UUID childNodeUuid(RecipeGraphEdge edge) {
        if (edge == null) return null;
        return edge.getDirection() == GraphExpansionDirection.SOURCE ? edge.getFromNode() : edge.getToNode();
    }

    private UUID parentNodeUuid(RecipeGraphEdge edge) {
        if (edge == null) return null;
        return edge.getDirection() == GraphExpansionDirection.SOURCE ? edge.getToNode() : edge.getFromNode();
    }

    private void recomputeAnchorLocks() {
        refreshEdgeAnchors();
        for (RecipePanelNode node : nodes.values()) {
            node.setInputAnchorsLocked(false);
            node.setOutputAnchorsLocked(false);
        }
        for (RecipeGraphEdge edge : edges) {
            RecipePanelNode parent = nodes.get(parentNodeUuid(edge));
            GraphAnchorData parentAnchor = parentAnchor(edge);
            lockAnchorSide(parent, parentAnchor);
        }
    }

    private void refreshEdgeAnchors() {
        for (RecipeGraphEdge edge : edges) {
            RecipePanelNode from = nodes.get(edge.getFromNode());
            RecipePanelNode to = nodes.get(edge.getToNode());
            if (from == null || to == null) continue;
            GraphAnchorData fromAnchor;
            GraphAnchorData toAnchor;
            if (edge.getDirection() == GraphExpansionDirection.SOURCE) {
                fromAnchor = anchorForChildNode(from);
                toAnchor = anchorForQueriedItem(to, edge.getItemId(), edge, GraphExpansionDirection.SOURCE);
            } else {
                fromAnchor = anchorForQueriedItem(from, edge.getItemId(), edge, GraphExpansionDirection.USAGE);
                toAnchor = anchorForChildNode(to);
            }
            if (fromAnchor != null && toAnchor != null) edge.setAnchors(fromAnchor, toAnchor);
        }
    }

    private GraphAnchorData parentAnchor(RecipeGraphEdge edge) {
        if (edge == null) return null;
        return edge.getDirection() == GraphExpansionDirection.SOURCE ? edge.getToAnchor() : edge.getFromAnchor();
    }

    private GraphAnchorData anchorForQueriedItem(RecipePanelNode node, String itemId, RecipeGraphEdge edge, GraphExpansionDirection direction) {
        SlotViewData slot = bestQueriedSlot(node, itemId, edge, direction);
        if (slot != null) return GraphAnchorData.slotCenter(node, slot, slotRowPadOffset(slot), slotTypePadOffset(slot));
        return anchorForNodeSide(node, direction == GraphExpansionDirection.USAGE ? -1.0f : 1.0f);
    }

    private SlotViewData bestQueriedSlot(RecipePanelNode node, String itemId, RecipeGraphEdge edge, GraphExpansionDirection direction) {
        if (node == null || itemId == null || itemId.isBlank()) return null;
        UniversalRecipeViewModel model = getViewModel(node);
        SlotViewData positioned = null;
        SlotViewData subject = null;
        SlotViewData input = null;
        SlotViewData output = null;
        SlotViewData any = null;
        for (SlotViewData slot : model.getSlots()) {
            if (slot == null || slot.getItem() == null || !matchesItemId(slot.getItem(), itemId)) continue;
            if (any == null) any = slot;
            if (positioned == null && edge != null && edge.hasParentSlotPosition() && sameSlotPosition(slot, edge.getParentSlotX(), edge.getParentSlotY())) positioned = slot;
            if (isSubjectSlot(node, slot) && subject == null) subject = slot;
            if (slot.getType() == SlotViewType.OUTPUT && output == null) output = slot;
            if (slot.getType() != SlotViewType.OUTPUT && input == null) input = slot;
        }
        if (positioned != null) return positioned;
        if (direction == GraphExpansionDirection.USAGE) {
            if (subject != null) return subject;
            if (output != null) return output;
            if (input != null) return input;
        } else {
            if (input != null) return input;
            if (output != null) return output;
            if (subject != null) return subject;
        }
        return any;
    }

    private boolean sameSlotPosition(SlotViewData slot, float x, float y) {
        return slot != null && Math.abs(slot.getX() - x) < 0.5f && Math.abs(slot.getY() - y) < 0.5f;
    }

    public void previousRecipe(RecipePanelNode node) {
        if (node == null) return;
        int before = node.getSelectedRecipeIndex();
        node.previousRecipe();
        if (before != node.getSelectedRecipeIndex()) afterNodeRecipeChanged(node);
    }

    public void nextRecipe(RecipePanelNode node) {
        if (node == null) return;
        int before = node.getSelectedRecipeIndex();
        node.nextRecipe();
        if (before != node.getSelectedRecipeIndex()) afterNodeRecipeChanged(node);
    }

    public RecipePanelNode expandIngredient(UUID parentUuid, String itemId, String displayName, long nowMillis) {
        return expandFromSlot(parentUuid, null, itemId, displayName, GraphExpansionDirection.SOURCE, nowMillis);
    }

    public RecipePanelNode expandFromSlot(UUID parentUuid, SlotViewData slot, String itemId, String displayName, GraphExpansionDirection direction, long nowMillis) {
        RecipePanelNode parent = nodes.get(parentUuid);
        if (parent == null || itemId == null || itemId.isBlank()) return null;

        if (shouldHighlightCurrentSubject(parent, slot, direction)) {
            parent.highlight(nowMillis, 1000L);
            return parent;
        }

        RecipeTreeData tree = direction == GraphExpansionDirection.USAGE ? recipeProvider.getUsageTree(itemId) : recipeProvider.getRecipeTree(itemId);
        if (tree.getRecipes().isEmpty()) return null;

        GraphAnchorData parentAnchor = anchorForQueriedObject(parent, slot, direction);
        if (direction == GraphExpansionDirection.SOURCE) {
            RecipePanelNode existingChild = findExistingChild(parentUuid, itemId, direction, parentAnchor);
            if (existingChild != null) {
                existingChild.highlight(nowMillis, 1000L);
                return existingChild;
            }

            RecipePanelNode ancestor = findAncestor(parentUuid, itemId, RecipePanelNodeType.SOURCE);
            if (ancestor != null) {
                ancestor.highlight(nowMillis, 1000L);
                return ancestor;
            }
        }

        long siblingCount = edges.stream()
                .filter(edge -> edge.getDirection() == direction && parentUuid.equals(parentNodeUuid(edge)))
                .count();
        float verticalStep = CraftingGraphConstants.NODE_HEIGHT + CraftingGraphConstants.NODE_GAP_Y;
        float x = parent.getX();
        float y = direction == GraphExpansionDirection.USAGE
                ? parent.getY() - verticalStep
                : parent.getY() + verticalStep;

        UUID uuid = UUID.randomUUID();
        RecipePanelNodeType nodeType = direction == GraphExpansionDirection.USAGE ? RecipePanelNodeType.USAGE : RecipePanelNodeType.SOURCE;
        RecipePanelNode node = new RecipePanelNode(uuid, parentUuid, nodeType, itemId, displayName, tree.getRecipes(), x, y);
        selectBestRecipe(node);
        nodes.put(uuid, node);
        nodeOrder.add(node);
        GraphAnchorData childAnchor = anchorForChildNode(node);
        float parentSlotX = slot != null ? slot.getX() : Float.NaN;
        float parentSlotY = slot != null ? slot.getY() : Float.NaN;
        if (direction == GraphExpansionDirection.SOURCE) {
            edges.add(new RecipeGraphEdge(uuid, parentUuid, itemId, direction, parentSlotX, parentSlotY, childAnchor, parentAnchor));
        } else {
            edges.add(new RecipeGraphEdge(parentUuid, uuid, itemId, direction, parentSlotX, parentSlotY, parentAnchor, childAnchor));
        }
        relayoutChildren(parentUuid, direction);
        resolveLayoutCollisions(node.getUuid());
        lockAnchorSide(parent, parentAnchor);
        dirty = true;
        return node;
    }

    private GraphAnchorData anchorForQueriedObject(RecipePanelNode node, SlotViewData slot, GraphExpansionDirection direction) {
        if (node == null) return null;
        SlotViewData anchorSlot = slot != null ? slot : bestSubjectAnchorSlot(node, direction);
        if (anchorSlot != null) return GraphAnchorData.slotCenter(node, anchorSlot, slotRowPadOffset(anchorSlot), slotTypePadOffset(anchorSlot));
        return anchorForNodeSide(node, direction == GraphExpansionDirection.USAGE ? -1.0f : 1.0f);
    }

    private GraphAnchorData anchorForChildNode(RecipePanelNode node) {
        if (node == null) return null;
        if (node.getNodeType() == RecipePanelNodeType.SOURCE) return GraphAnchorData.nodeCenter(node);
        SlotViewData anchorSlot = bestSubjectAnchorSlot(node, GraphExpansionDirection.USAGE);
        if (anchorSlot != null) return GraphAnchorData.slotCenter(node, anchorSlot, slotRowPadOffset(anchorSlot), slotTypePadOffset(anchorSlot));
        return GraphAnchorData.nodeCenter(node);
    }

    private SlotViewData bestSubjectAnchorSlot(RecipePanelNode node, GraphExpansionDirection direction) {
        UniversalRecipeViewModel model = getViewModel(node);
        SlotViewData fallback = null;
        for (SlotViewData slot : model.getSlots()) {
            if (slot == null || slot.getItem() == null) continue;
            if (isSubjectSlot(node, slot)) return slot;
            if (fallback == null && direction == GraphExpansionDirection.SOURCE && slot.getType() == SlotViewType.OUTPUT) fallback = slot;
            if (fallback == null && direction == GraphExpansionDirection.USAGE && slot.getType() != SlotViewType.OUTPUT) fallback = slot;
        }
        return fallback;
    }

    private GraphAnchorData anchorForNodeSide(RecipePanelNode node, float verticalSide) {
        SlotViewData slot = bestSubjectAnchorSlot(node, verticalSide > 0.0f ? GraphExpansionDirection.SOURCE : GraphExpansionDirection.USAGE);
        if (slot != null) return GraphAnchorData.slotCenter(node, slot, slotRowPadOffset(slot), slotTypePadOffset(slot));
        return anchorForNodeSlotSide(node, verticalSide > 0.0f ? SlotViewType.OUTPUT : SlotViewType.INPUT);
    }

    private GraphAnchorData anchorForNodeSlotSide(RecipePanelNode node, SlotViewType slotType) {
        if (node == null) return null;
        boolean output = slotType == SlotViewType.OUTPUT;
        float slotX = CraftingGraphConstants.NODE_WIDTH * 0.5f - 9.0f;
        float slotY = output ? CraftingGraphConstants.NODE_HEIGHT - 24.0f : 22.0f;
        SlotViewType resolvedType = output ? SlotViewType.OUTPUT : SlotViewType.INPUT;
        return GraphAnchorData.slotCenter(node, new SlotViewData(null, resolvedType, slotX, slotY), 0.0f, slotTypePadOffset(resolvedType));
    }

    private float slotTypePadOffset(SlotViewData slot) {
        return slotTypePadOffset(slot != null ? slot.getType() : null);
    }

    private float slotTypePadOffset(SlotViewType slotType) {
        float inputOffset = -7.0f;
        if (slotType == SlotViewType.OUTPUT) return 7.0f;
        return inputOffset;
    }

    private float slotRowPadOffset(SlotViewData slot) {
        if (slot == null) return 0.0f;
        return switch (slotRowIndex(slot)) {
            case 0 -> -6.0f;
            case 1 -> 0.0f;
            default -> 6.0f;
        };
    }

    private int slotRowIndex(SlotViewData slot) {
        if (slot == null) return 1;
        float normalizedY = Math.max(0.0f, slot.getY());
        return Math.floorMod((int) Math.floor((normalizedY + 2.0f) / 20.0f), 3);
    }

    private void relayoutChildren(UUID parentUuid, GraphExpansionDirection direction) {
        RecipePanelNode parent = nodes.get(parentUuid);
        if (parent == null) return;
        List<RecipePanelNode> children = new ArrayList<>();
        for (RecipeGraphEdge edge : edges) {
            if (edge.getDirection() != direction || !parentUuid.equals(parentNodeUuid(edge))) continue;
            RecipePanelNode child = nodes.get(childNodeUuid(edge));
            if (child != null) children.add(child);
        }
        if (children.isEmpty()) return;
        float stepX = CraftingGraphConstants.NODE_WIDTH + CraftingGraphConstants.NODE_GAP_X;
        float stepY = CraftingGraphConstants.NODE_HEIGHT + CraftingGraphConstants.NODE_GAP_Y;
        float parentCenterX = parent.getX() + CraftingGraphConstants.NODE_WIDTH * 0.5f;
        int rowCount = children.size();
        float rowWidth = rowCount * CraftingGraphConstants.NODE_WIDTH + (rowCount - 1) * CraftingGraphConstants.NODE_GAP_X;
        float startX = parentCenterX - rowWidth * 0.5f;
        float y = direction == GraphExpansionDirection.USAGE
                ? parent.getY() - stepY
                : parent.getY() + stepY;
        for (int col = 0; col < rowCount; col++) {
            RecipePanelNode child = children.get(col);
            float x = startX + col * stepX;
            moveBranch(child.getUuid(), x - child.getX(), y - child.getY());
        }
    }

    private void resolveLayoutCollisions(UUID changedUuid) {
        for (int i = 0; i < 8; i++) {
            boolean changed = resolveRowCollisions();
            UUID parentUuid = parentUuidOf(changedUuid);
            while (parentUuid != null) {
                relayoutChildren(parentUuid, GraphExpansionDirection.USAGE);
                relayoutChildren(parentUuid, GraphExpansionDirection.SOURCE);
                parentUuid = parentUuidOf(parentUuid);
            }
            changed |= resolveRowCollisions();
            if (!changed) return;
        }
    }

    private boolean resolveRowCollisions() {
        List<RecipePanelNode> ordered = new ArrayList<>(nodeOrder);
        ordered.sort(Comparator.comparingDouble(this::layoutRow).thenComparingDouble(RecipePanelNode::getX));
        boolean changed = false;
        int start = 0;
        while (start < ordered.size()) {
            int row = layoutRow(ordered.get(start));
            int end = start + 1;
            while (end < ordered.size() && layoutRow(ordered.get(end)) == row) end++;
            changed |= resolveSingleRowCollisions(ordered.subList(start, end));
            start = end;
        }
        return changed;
    }

    private boolean resolveSingleRowCollisions(List<RecipePanelNode> rowNodes) {
        if (rowNodes.size() < 2) return false;
        float minGap = CraftingGraphConstants.NODE_GAP_X;
        float previousRight = Float.NEGATIVE_INFINITY;
        RecipePanelNode previousRightNode = null;
        boolean changed = false;
        for (RecipePanelNode node : rowNodes) {
            float requiredX = previousRight + minGap;
            if (node.getX() < requiredX && previousRightNode != null) {
                moveBranch(layoutShiftRoot(node, previousRightNode).getUuid(), requiredX - node.getX(), 0.0f);
                changed = true;
            }
            float nodeRight = node.getX() + CraftingGraphConstants.NODE_WIDTH;
            if (nodeRight > previousRight) {
                previousRight = nodeRight;
                previousRightNode = node;
            }
        }
        return changed;
    }

    private RecipePanelNode layoutShiftRoot(RecipePanelNode node, RecipePanelNode blocker) {
        if (node == null) return blocker;
        if (blocker != null && isAncestor(blocker.getUuid(), node.getUuid())) {
            RecipePanelNode pathChild = childOnPath(blocker.getUuid(), node.getUuid());
            if (pathChild != null) return pathChild;
        }
        return node;
    }

    private boolean isAncestor(UUID ancestorUuid, UUID nodeUuid) {
        UUID current = parentUuidOf(nodeUuid);
        while (current != null) {
            if (current.equals(ancestorUuid)) return true;
            current = parentUuidOf(current);
        }
        return false;
    }

    private RecipePanelNode childOnPath(UUID ancestorUuid, UUID nodeUuid) {
        RecipePanelNode node = nodes.get(nodeUuid);
        RecipePanelNode child = node;
        while (node != null && node.getParentUuid() != null) {
            if (node.getParentUuid().equals(ancestorUuid)) return child;
            child = node;
            node = nodes.get(node.getParentUuid());
        }
        return null;
    }

    private int layoutRow(RecipePanelNode node) {
        float stepY = CraftingGraphConstants.NODE_HEIGHT + CraftingGraphConstants.NODE_GAP_Y;
        return Math.round(node.getY() / stepY);
    }

    private UUID parentUuidOf(UUID uuid) {
        RecipePanelNode node = nodes.get(uuid);
        return node != null ? node.getParentUuid() : null;
    }

    private void moveBranch(UUID rootUuid, float dx, float dy) {
        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) return;
        Set<UUID> branch = collectBranchUuids(rootUuid);
        for (UUID uuid : branch) {
            RecipePanelNode node = nodes.get(uuid);
            if (node != null) node.setPosition(node.getX() + dx, node.getY() + dy);
        }
        refreshEdgeAnchors();
    }

    private RecipePanelNode findExistingChild(UUID parentUuid, String itemId, GraphExpansionDirection direction, GraphAnchorData parentAnchor) {
        for (RecipeGraphEdge edge : edges) {
            if (edge.getDirection() != direction || !parentUuid.equals(parentNodeUuid(edge)) || !Objects.equals(itemId, edge.getItemId())) continue;
            if (!sameAnchor(parentAnchor, direction == GraphExpansionDirection.SOURCE ? edge.getToAnchor() : edge.getFromAnchor())) continue;
            UUID childUuid = childNodeUuid(edge);
            RecipePanelNode child = childUuid != null ? nodes.get(childUuid) : null;
            if (child != null) return child;
        }
        return null;
    }

    private boolean sameAnchor(GraphAnchorData a, GraphAnchorData b) {
        if (a == null || b == null) return a == b;
        return a.getKind() == b.getKind()
                && a.getSlotType() == b.getSlotType()
                && Math.abs((a.getX() + a.getOffsetX()) - (b.getX() + b.getOffsetX())) < 0.5f
                && Math.abs((a.getY() + a.getOffsetY()) - (b.getY() + b.getOffsetY())) < 0.5f;
    }

    private boolean isSubjectSlot(RecipePanelNode node, SlotViewData slot) {
        if (node == null || slot == null || slot.getItem() == null) return false;
        if (node.getNodeType() == RecipePanelNodeType.SOURCE && slot.getType() != SlotViewType.OUTPUT) return false;
        if (node.getNodeType() == RecipePanelNodeType.USAGE && slot.getType() == SlotViewType.OUTPUT) return false;
        return matchesItemId(slot.getItem(), node.getItemId());
    }

    private boolean matchesSubjectItem(IngredientData item, String subjectItemId) {
        return matchesItemId(item, subjectItemId);
    }

    private boolean matchesItemId(IngredientData item, String subjectItemId) {
        if (item == null || subjectItemId == null || subjectItemId.isBlank()) return false;
        String itemId = normalizedItemId(item.getItemId());
        if (subjectItemId.equals(itemId)) return true;
        return item.getTagItems().contains(subjectItemId);
    }

    private boolean shouldHighlightCurrentSubject(RecipePanelNode parent, SlotViewData slot, GraphExpansionDirection direction) {
        return isSubjectSlot(parent, slot)
                && parent.getNodeType() == RecipePanelNodeType.SOURCE
                && direction == GraphExpansionDirection.SOURCE;
    }

    private void lockAnchorSide(RecipePanelNode node, GraphAnchorData anchor) {
        if (node == null || anchor == null) return;
        SlotViewType anchorSlotType = anchor.getSlotType();
        if (anchorSlotType == SlotViewType.OUTPUT) {
            node.setOutputAnchorsLocked(true);
        } else {
            node.setInputAnchorsLocked(true);
        }
    }

    private RecipePanelNode findAncestor(UUID nodeUuid, String itemId, RecipePanelNodeType nodeType) {
        RecipePanelNode cursor = nodes.get(nodeUuid);
        while (cursor != null) {
            if (itemId.equals(cursor.getItemId()) && cursor.getNodeType() == nodeType) return cursor;
            UUID parentUuid = cursor.getParentUuid();
            cursor = parentUuid != null ? nodes.get(parentUuid) : null;
        }
        return null;
    }

    public RecipePanelNode hitNode(float worldX, float worldY) {
        for (int i = nodeOrder.size() - 1; i >= 0; i--) {
            RecipePanelNode node = nodeOrder.get(i);
            if (worldX >= node.getX() && worldX <= node.getX() + CraftingGraphConstants.NODE_WIDTH
                    && worldY >= node.getY() && worldY <= node.getY() + CraftingGraphConstants.NODE_HEIGHT) {
                return node;
            }
        }
        return null;
    }

    public boolean hitPreviousRecipe(RecipePanelNode node, float worldX, float worldY) {
        if (node == null || !node.canSwitchRecipe() || !node.hasMultipleRecipesInSelectedCategory()) return false;
        float x = node.getX() + CraftingGraphConstants.NODE_WIDTH - 42.0f;
        float y = node.getY() + CraftingGraphConstants.NODE_HEIGHT - 17.0f;
        return worldX >= x - 3.0f && worldX <= x + 12.0f && worldY >= y - 3.0f && worldY <= y + 10.0f;
    }

    public boolean hitNextRecipe(RecipePanelNode node, float worldX, float worldY) {
        if (node == null || !node.canSwitchRecipe() || !node.hasMultipleRecipesInSelectedCategory()) return false;
        float x = node.getX() + CraftingGraphConstants.NODE_WIDTH - 22.0f;
        float y = node.getY() + CraftingGraphConstants.NODE_HEIGHT - 17.0f;
        return worldX >= x - 3.0f && worldX <= x + 12.0f && worldY >= y - 3.0f && worldY <= y + 10.0f;
    }

    public boolean hitRecipePickerButton(RecipePanelNode node, float worldX, float worldY) {
        if (node == null || !node.canSwitchRecipe() || node.filteredRecipeIndices().size() <= 1) return false;
        float x = recipePickerButtonX(node);
        float y = recipePickerButtonY(node);
        return worldX >= x && worldX <= x + 14.0f && worldY >= y && worldY <= y + 12.0f;
    }

    public int hitRecipePickerEntry(RecipePanelNode node, float worldX, float worldY) {
        if (node == null || !node.isRecipePickerOpen()) return -1;
        PickerGrid grid = recipePickerGrid(node);
        if (worldX < grid.x || worldX > grid.x + grid.w || worldY < grid.y || worldY > grid.y + grid.h) return -1;
        if (worldX < grid.contentLeft || worldX > grid.contentRight || worldY < grid.contentTop || worldY > grid.contentBottom) return -1;
        float contentX = worldX - grid.contentLeft;
        float contentY = worldY - grid.contentTop + node.getRecipePickerScroll();
        if (contentX < 0.0f || contentY < 0.0f) return -1;
        int col = (int) (contentX / CraftingGraphConstants.RECIPE_PICKER_CELL);
        int row = (int) (contentY / CraftingGraphConstants.RECIPE_PICKER_CELL);
        if (col < 0 || col >= grid.columns) return -1;
        float cellX = grid.contentLeft + col * CraftingGraphConstants.RECIPE_PICKER_CELL;
        float cellY = grid.contentTop + row * CraftingGraphConstants.RECIPE_PICKER_CELL - node.getRecipePickerScroll();
        if (worldX < cellX || worldX > cellX + 20.0f || worldY < cellY || worldY > cellY + 20.0f) return -1;
        List<Integer> indices = node.filteredRecipeIndices();
        int visibleIndex = row * grid.columns + col;
        return visibleIndex >= 0 && visibleIndex < indices.size() ? indices.get(visibleIndex) : -1;
    }

    public boolean scrollRecipePicker(float worldX, float worldY, double scrollDelta) {
        RecipePanelNode node = hitOpenRecipePicker(worldX, worldY);
        if (node == null) return false;
        float maxScroll = maxRecipePickerScroll(node);
        float next = node.getRecipePickerScroll() - (float) scrollDelta * CraftingGraphConstants.RECIPE_PICKER_SCROLL_STEP;
        node.setRecipePickerScroll(Math.max(0.0f, Math.min(maxScroll, next)));
        return true;
    }

    public RecipePanelNode hitOpenRecipePicker(float worldX, float worldY) {
        for (int i = nodeOrder.size() - 1; i >= 0; i--) {
            RecipePanelNode node = nodeOrder.get(i);
            if (!node.isRecipePickerOpen()) continue;
            float x = recipePickerX(node);
            float y = recipePickerY(node);
            float h = recipePickerHeight(node);
            if (worldX >= x && worldX <= x + CraftingGraphConstants.RECIPE_PICKER_WIDTH && worldY >= y && worldY <= y + h) return node;
        }
        return null;
    }

    public void toggleRecipePicker(RecipePanelNode node) {
        if (node == null || node.getRecipes().size() <= 1) return;
        boolean nextOpen = !node.isRecipePickerOpen();
        closeRecipePickersExcept(node);
        node.setRecipePickerOpen(nextOpen);
        node.setRecipePickerScroll(Math.min(node.getRecipePickerScroll(), maxRecipePickerScroll(node)));
    }

    public void closeRecipePickersExcept(RecipePanelNode keptNode) {
        for (RecipePanelNode node : nodes.values()) {
            if (node != keptNode) node.setRecipePickerOpen(false);
        }
    }

    public void selectRecipeFromPicker(RecipePanelNode node, int index) {
        if (node == null || !node.canSwitchRecipe()) return;
        node.setSelectedRecipeIndex(index);
        node.setRecipePickerOpen(false);
        afterNodeRecipeChanged(node);
    }

    private void afterNodeRecipeChanged(RecipePanelNode node) {
        if (node == null) return;
        recomputeAnchorLocks();
        dirty = true;
    }

    public boolean hitRecipeCategory(RecipePanelNode node, float worldX, float worldY) {
        int index = hitRecipeCategoryIndex(node, worldX, worldY);
        if (index < 0) return false;
        List<String> categories = node.recipeCategories();
        if (index >= categories.size()) return true;
        String before = node.getSelectedRecipeCategory();
        int selectedBefore = node.getSelectedRecipeIndex();
        node.selectRecipeCategory(categories.get(index));
        node.setRecipePickerOpen(false);
        if (!Objects.equals(before, node.getSelectedRecipeCategory()) || selectedBefore != node.getSelectedRecipeIndex()) afterNodeRecipeChanged(node);
        return true;
    }

    private int hitRecipeCategoryIndex(RecipePanelNode node, float worldX, float worldY) {
        if (node == null || !node.canSwitchRecipe()) return -1;
        List<String> categories = node.recipeCategories();
        if (categories.isEmpty()) return -1;
        float listX = node.getX() + 7.0f;
        float listY = node.getY() + 21.0f;
        float listW = CraftingGraphConstants.NODE_CATEGORY_WIDTH;
        float listH = CraftingGraphConstants.NODE_HEIGHT - 42.0f;
        if (worldX < listX || worldX > listX + listW || worldY < listY || worldY > listY + listH) return -1;
        int max = Math.min(categories.size(), Math.max(1, (int) ((listH - 24.0f) / 18.0f)));
        int index = (int) ((worldY - (listY + 12.0f)) / 18.0f);
        if (index < 0 || index >= max) return -1;
        return index;
    }

    public float recipePickerButtonX(RecipePanelNode node) {
        return node.getX() + CraftingGraphConstants.NODE_CATEGORY_WIDTH + 10.0f;
    }

    public float recipePickerButtonY(RecipePanelNode node) {
        return node.getY() + 22.0f;
    }

    public float recipePickerX(RecipePanelNode node) {
        return node.getX() + CraftingGraphConstants.NODE_CATEGORY_WIDTH + 10.0f;
    }

    public float recipePickerY(RecipePanelNode node) {
        return node.getY() + CraftingGraphConstants.NODE_CONTENT_Y - 2.0f;
    }

    public float recipePickerHeight(RecipePanelNode node) {
        int rows = (int) Math.ceil(node.filteredRecipeIndices().size() / (float) recipePickerColumns());
        float contentHeight = rows * CraftingGraphConstants.RECIPE_PICKER_CELL + CraftingGraphConstants.RECIPE_PICKER_PADDING * 2.0f;
        return Math.min(CraftingGraphConstants.RECIPE_PICKER_MAX_HEIGHT, contentHeight);
    }

    public float maxRecipePickerScroll(RecipePanelNode node) {
        int rows = (int) Math.ceil(node.filteredRecipeIndices().size() / (float) recipePickerColumns());
        float contentHeight = rows * CraftingGraphConstants.RECIPE_PICKER_CELL + CraftingGraphConstants.RECIPE_PICKER_PADDING * 2.0f;
        return Math.max(0.0f, contentHeight - recipePickerHeight(node));
    }

    public int recipePickerColumns() {
        return recipePickerGrid(null).columns;
    }

    private PickerGrid recipePickerGrid(RecipePanelNode node) {
        float x = node != null ? recipePickerX(node) : 0.0f;
        float y = node != null ? recipePickerY(node) : 0.0f;
        float w = CraftingGraphConstants.RECIPE_PICKER_WIDTH;
        float h = node != null ? recipePickerHeight(node) : CraftingGraphConstants.RECIPE_PICKER_MAX_HEIGHT;
        float pad = CraftingGraphConstants.RECIPE_PICKER_PADDING;
        boolean scrollable = node != null && maxRecipePickerScroll(node) > 0.0f;
        float contentLeft = x + pad;
        float contentTop = y + pad;
        float contentRight = x + w - pad - (scrollable ? 7.0f : 0.0f);
        float contentBottom = y + h - pad;
        int columns = Math.max(1, (int) ((contentRight - contentLeft) / CraftingGraphConstants.RECIPE_PICKER_CELL));
        return new PickerGrid(x, y, w, h, contentLeft, contentTop, contentRight, contentBottom, columns);
    }

    private record PickerGrid(float x, float y, float w, float h, float contentLeft, float contentTop, float contentRight, float contentBottom, int columns) {
    }

    public EdgeHitResult hitEdge(float worldX, float worldY) {
        EdgeHitResult best = null;
        float bestDistance = Float.MAX_VALUE;
        for (RecipeGraphEdge edge : edges) {
            GraphAnchorData from = edge.getFromAnchor();
            GraphAnchorData to = edge.getToAnchor();
            float midY = edgeMidY(edge);
            float distance = distanceToOrthogonalEdge(worldX, worldY,
                    from.getX() + from.getOffsetX(), from.getY() + from.getOffsetY(),
                    to.getX() + to.getOffsetX(), to.getY() + to.getOffsetY(), midY);
            if (distance < bestDistance && distance <= 36.0f) {
                bestDistance = distance;
                best = new EdgeHitResult(edge, (from.getX() + from.getOffsetX() + to.getX() + to.getOffsetX()) * 0.5f, midY);
            }
        }
        return best;
    }

    private float edgeMidY(RecipeGraphEdge edge) {
        GraphAnchorData from = edge.getFromAnchor();
        GraphAnchorData to = edge.getToAnchor();
        return (from.getY() + from.getOffsetY() + to.getY() + to.getOffsetY()) * 0.5f;
    }

    private float distanceToOrthogonalEdge(float x, float y, float x1, float y1, float x2, float y2, float midY) {
        float d1 = distanceToSegment(x, y, x1, y1, x1, midY);
        float d2 = distanceToSegment(x, y, x1, midY, x2, midY);
        float d3 = distanceToSegment(x, y, x2, midY, x2, y2);
        return Math.min(d1, Math.min(d2, d3));
    }

    private float distanceToSegment(float x, float y, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = dx * dx + dy * dy;
        if (length <= 0.0001f) {
            float px = x - x1;
            float py = y - y1;
            return px * px + py * py;
        }
        float t = Math.max(0.0f, Math.min(1.0f, ((x - x1) * dx + (y - y1) * dy) / length));
        float px = x1 + t * dx;
        float py = y1 + t * dy;
        float ox = x - px;
        float oy = y - py;
        return ox * ox + oy * oy;
    }

    public SlotHitResult hitSlot(float worldX, float worldY) {
        for (int i = nodeOrder.size() - 1; i >= 0; i--) {
            RecipePanelNode node = nodeOrder.get(i);
            UniversalRecipeViewModel model = getViewModel(node);
            float originX = node.getX() + CraftingGraphConstants.NODE_CONTENT_X;
            float originY = node.getY() + CraftingGraphConstants.NODE_CONTENT_Y;
            for (SlotViewData slot : model.getSlots()) {
                float slotX = originX + slot.getX();
                float slotY = originY + slot.getY();
                if (worldX >= slotX - 2.0f && worldX <= slotX + 18.0f && worldY >= slotY - 2.0f && worldY <= slotY + 18.0f) {
                    return new SlotHitResult(node.getUuid(), slot);
                }
            }
        }
        return null;
    }

    public UniversalRecipeViewModel getViewModel(RecipePanelNode node) {
        return adapterRegistry.adapt(node != null ? node.getSelectedRecipe() : null);
    }

    public boolean isIngredientSatisfied(IngredientData ingredient) {
        return getAvailableCount(ingredient) >= requiredCount(ingredient);
    }

    public int getAvailableCount(IngredientData ingredient) {
        if (inventoryProvider == null || ingredient == null) return 0;
        List<ItemSnapshot> items = inventoryProvider.getAllInventoryItemsData();
        if (items == null || items.isEmpty()) return 0;
        int count = 0;
        for (ItemSnapshot item : items) {
            if (matchesIngredient(ingredient, item)) count += Math.max(0, item.getCount());
        }
        return count;
    }

    public int getRequiredCount(IngredientData ingredient) {
        return requiredCount(ingredient);
    }

    public String getBestAvailableItemId(IngredientData ingredient) {
        if (inventoryProvider == null || ingredient == null) return null;
        List<ItemSnapshot> items = inventoryProvider.getAllInventoryItemsData();
        if (items == null || items.isEmpty()) return null;
        String bestItemId = null;
        int bestCount = 0;
        for (ItemSnapshot item : items) {
            if (matchesIngredient(ingredient, item) && item.getCount() > bestCount) {
                bestItemId = item.getItemId();
                bestCount = item.getCount();
            }
        }
        return bestItemId;
    }

    private void selectBestRecipe(RecipePanelNode node) {
        if (node == null || node.getRecipes().size() <= 1) return;
        node.setSelectedRecipeIndex(inventoryAnalyzer.bestRecipeIndex(node.getRecipes(), inventoryProvider != null ? inventoryProvider.getAllInventoryItemsData() : null));
    }

    private int scoreRecipe(RecipeData recipe) {
        if (recipe == null || recipe.getIngredients() == null || recipe.getIngredients().isEmpty()) return 0;
        int score = 0;
        for (IngredientData ingredient : recipe.getIngredients()) {
            int required = requiredCount(ingredient);
            int available = getAvailableCount(ingredient);
            score += Math.min(available, required) * 100;
            if (available >= required) score += 10000;
        }
        return score;
    }

    private boolean matchesIngredient(IngredientData ingredient, ItemSnapshot item) {
        if (ingredient == null || item == null || item.getItemId() == null) return false;
        String itemId = normalizedItemId(ingredient.getItemId());
        if (itemId != null && !itemId.isBlank() && !itemId.startsWith("#") && itemId.equals(item.getItemId())) return true;
        return ingredient.getTagItems().contains(item.getItemId());
    }

    private String normalizedItemId(String itemId) {
        if (itemId == null) return null;
        int separator = itemId.indexOf('/');
        return separator >= 0 ? itemId.substring(0, separator) : itemId;
    }

    private int requiredCount(IngredientData ingredient) {
        if (ingredient == null) return 1;
        return Math.max(1, ingredient.getCount());
    }

    public void setHeld(boolean held) {
        if (mode == CraftingGraphInteractionMode.LOCKED) return;
        mode = held ? CraftingGraphInteractionMode.HELD : CraftingGraphInteractionMode.PASSIVE;
    }

    public void toggleExpanded() {
        setExpanded(!expanded);
    }

    public void setExpanded(boolean expanded) {
        if (this.expanded == expanded) return;
        this.expanded = expanded;
        startDrawerAnimation(expanded ? 1.0f : 0.0f, System.currentTimeMillis());
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void toggleLocked(boolean locked) {
        mode = locked ? CraftingGraphInteractionMode.LOCKED : CraftingGraphInteractionMode.PASSIVE;
        if (locked) setExpanded(true);
    }

    public float getAlpha() {
        float baseAlpha = switch (mode) {
            case PASSIVE -> CraftingGraphConstants.DEFAULT_ALPHA;
            case HELD -> CraftingGraphConstants.INTERACTIVE_ALPHA;
            case LOCKED -> CraftingGraphConstants.LOCKED_ALPHA;
        };
        return baseAlpha * customAlpha;
    }

    public boolean isVisible() {
        return expanded || mode != CraftingGraphInteractionMode.PASSIVE;
    }

    public boolean isInteractive() {
        return mode == CraftingGraphInteractionMode.HELD || mode == CraftingGraphInteractionMode.LOCKED;
    }

    public void beginMiddlePress(long nowMillis) {
        middleDownAtMillis = nowMillis;
        draggingCamera = true;
    }

    public void endMiddlePress() {
        draggingCamera = false;
        middleDownAtMillis = 0L;
    }

    public boolean shouldLockByMiddleHold(long nowMillis) {
        return middleDownAtMillis > 0L && nowMillis - middleDownAtMillis >= CraftingGraphConstants.MIDDLE_LOCK_MILLIS;
    }

    public boolean hasRecipes(String itemId, RecipePanelNodeType nodeType) {
        if (itemId == null || itemId.isBlank()) return false;
        RecipeTreeData tree = nodeType == RecipePanelNodeType.USAGE ? recipeProvider.getUsageTree(itemId) : recipeProvider.getRecipeTree(itemId);
        return tree != null && tree.getRecipes() != null && !tree.getRecipes().isEmpty();
    }

    public void focusNode(RecipePanelNode node, int viewportW, int viewportH, long nowMillis) {
        if (node == null) return;
        camera.focusWorldPoint(node.getX() + CraftingGraphConstants.NODE_WIDTH * 0.5f, node.getY() + CraftingGraphConstants.NODE_HEIGHT * 0.5f, viewportW, viewportH);
        node.highlight(nowMillis, 1200L);
    }

    public CraftingGraphCamera getCamera() { return camera; }
    public Collection<RecipePanelNode> getNodes() { return Collections.unmodifiableCollection(nodes.values()); }
    public List<RecipeGraphEdge> getEdges() { return Collections.unmodifiableList(edges); }
    public RecipePanelNode getNode(UUID uuid) { return nodes.get(uuid); }
    public CraftingGraphInteractionMode getMode() { return mode; }
    public boolean isDraggingCamera() { return draggingCamera; }
    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void markClean() { dirty = false; }
    public boolean isEmpty() { return nodes.isEmpty(); }
    public boolean canUndoDelete() { return !undoStack.isEmpty(); }
    public float getDrawerProgress() {
        return drawerProgress;
    }

    private void startDrawerAnimation(float target, long now) {
        updateDrawerAnimation(now);
        drawerAnimationStartProgress = drawerProgress;
        drawerAnimationTargetProgress = target;
        drawerAnimationStartMillis = now;
        drawerAnimationRunning = true;
    }

    private void updateDrawerAnimation(long now) {
        if (!drawerAnimationRunning) {
            return;
        }
        drawerProgress = computeDrawerProgress(now);
        if (drawerProgress == drawerAnimationTargetProgress) {
            drawerAnimationRunning = false;
        }
    }

    private float computeDrawerProgress(long now) {
        float distance = Math.abs(drawerAnimationTargetProgress - drawerAnimationStartProgress);
        if (distance <= 0.0001f) return drawerAnimationTargetProgress;
        float direction = drawerAnimationTargetProgress > drawerAnimationStartProgress ? 1.0f : -1.0f;
        float elapsedSeconds = Math.max(0.0f, (now - drawerAnimationStartMillis) / 1000.0f);
        float acceleration = 4.0f;
        float duration = (float) Math.sqrt(2.0f * distance / acceleration);
        float initialVelocity = acceleration * duration;
        float travelled = initialVelocity * elapsedSeconds - 0.5f * acceleration * elapsedSeconds * elapsedSeconds;
        if (elapsedSeconds >= duration || travelled >= distance) return drawerAnimationTargetProgress;
        return drawerAnimationStartProgress + direction * Math.max(0.0f, travelled);
    }

    public float renderDrawerProgress(float partialTick) {
        if (!drawerAnimationRunning) return drawerProgress;
        return computeDrawerProgress(System.currentTimeMillis());
    }

    public void setCustomAlpha(float customAlpha) {
        this.customAlpha = Math.max(0.2f, Math.min(1.0f, customAlpha));
    }
}
