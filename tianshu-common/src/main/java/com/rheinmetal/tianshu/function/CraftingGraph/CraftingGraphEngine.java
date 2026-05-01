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
    private final List<RecipeGraphEdge> edges = new ArrayList<>();
    private final Deque<DeleteBranchCommand> undoStack = new ArrayDeque<>();

    private CraftingGraphInteractionMode mode = CraftingGraphInteractionMode.PASSIVE;
    private boolean draggingCamera;
    private long middleDownAtMillis;
    private boolean dirty;
    private float drawerProgress;
    private float customAlpha = 1.0f;

    public CraftingGraphEngine(IRecipeDataProvider recipeProvider, IInventoryDataProvider inventoryProvider) {
        this.recipeProvider = recipeProvider;
        this.inventoryProvider = inventoryProvider;
    }

    public void tick() {
        float targetDrawerProgress = isVisible() ? 1.0f : 0.0f;
        drawerProgress += (targetDrawerProgress - drawerProgress) * CraftingGraphConstants.DRAWER_LERP;
        camera.tick();
    }

    public RecipePanelNode createRoot(String itemId, String displayName, RecipePanelNodeType nodeType, int screenW, int screenH) {
        RecipeTreeData tree = nodeType == RecipePanelNodeType.USAGE ? recipeProvider.getUsageTree(itemId) : recipeProvider.getRecipeTree(itemId);
        UUID uuid = UUID.randomUUID();
        RecipePanelNode node = new RecipePanelNode(
                uuid,
                null,
                nodeType != null ? nodeType : RecipePanelNodeType.SOURCE,
                itemId,
                displayName,
                tree.getRecipes(),
                0.0f,
                0.0f
        );
        node.setSelectedRecipeIndex(inventoryAnalyzer.bestRecipeIndex(node.getRecipes(), inventoryProvider != null ? inventoryProvider.getAllInventoryItemsData() : null));
        nodes.put(uuid, node);
        int drawerW = Math.max(CraftingGraphConstants.DRAWER_MIN_WIDTH, Math.min(CraftingGraphConstants.DRAWER_MAX_WIDTH, screenW / 3));
        camera.focusWorldPoint(node.getX() + CraftingGraphConstants.NODE_WIDTH * 0.5f, node.getY() + CraftingGraphConstants.NODE_HEIGHT * 0.5f, drawerW, screenH);
        dirty = true;
        return node;
    }

    public void clearGraph() {
        nodes.clear();
        edges.clear();
        undoStack.clear();
        dirty = true;
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
        }
        edges.addAll(command.getDeletedEdges());
        recomputeAnchorLocks();
        dirty = true;
        return true;
    }

    public CraftingGraphSaveData createSaveData() {
        CraftingGraphSaveData data = new CraftingGraphSaveData();
        data.savedAtMillis = System.currentTimeMillis();
        for (RecipePanelNode node : nodes.values()) {
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
            record.fromAnchor = anchorRecord(edge.getFromAnchor());
            record.toAnchor = anchorRecord(edge.getToAnchor());
            data.edges.add(record);
        }
        return data;
    }

    public void restoreSaveData(CraftingGraphSaveData data) {
        nodes.clear();
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
            }
            for (CraftingGraphSaveData.EdgeRecord record : data.edges) {
                UUID fromNode = UUID.fromString(record.fromNode);
                UUID toNode = UUID.fromString(record.toNode);
                if (nodes.containsKey(fromNode) && nodes.containsKey(toNode)) {
                    edges.add(new RecipeGraphEdge(fromNode, toNode, record.itemId, record.direction, anchorData(record.fromAnchor), anchorData(record.toAnchor)));
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
        return record;
    }

    private GraphAnchorData anchorData(CraftingGraphSaveData.AnchorRecord record) {
        return new GraphAnchorData(record.kind, record.slotType, record.x, record.y, record.offsetX);
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
        for (RecipePanelNode node : nodes.values()) {
            node.setInputAnchorsLocked(false);
            node.setOutputAnchorsLocked(false);
        }
        for (RecipeGraphEdge edge : edges) {
            RecipePanelNode from = nodes.get(edge.getFromNode());
            RecipePanelNode to = nodes.get(edge.getToNode());
            lockAnchorSide(from, edge.getFromAnchor());
            lockAnchorSide(to, edge.getToAnchor());
        }
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

        if (direction == GraphExpansionDirection.SOURCE) {
            RecipePanelNode ancestor = findAncestor(parentUuid, itemId, RecipePanelNodeType.SOURCE);
            if (ancestor != null) {
                ancestor.highlight(nowMillis, 1000L);
                return ancestor;
            }
        }

        long siblingCount = edges.stream()
                .filter(edge -> edge.getDirection() == direction && (edge.getFromNode().equals(parentUuid) || edge.getToNode().equals(parentUuid)))
                .count();
        int col = (int) (siblingCount % CraftingGraphConstants.MAX_NODES_PER_ROW);
        int row = (int) (siblingCount / CraftingGraphConstants.MAX_NODES_PER_ROW);
        float horizontalOffset = (col - 2) * (CraftingGraphConstants.NODE_WIDTH + CraftingGraphConstants.NODE_GAP_X);
        float x = parent.getX() + horizontalOffset;
        float verticalStep = CraftingGraphConstants.NODE_HEIGHT + CraftingGraphConstants.NODE_GAP_Y;
        float y = direction == GraphExpansionDirection.USAGE
                ? parent.getY() - verticalStep - row * verticalStep
                : parent.getY() + verticalStep + row * verticalStep;

        UUID uuid = UUID.randomUUID();
        RecipePanelNodeType nodeType = direction == GraphExpansionDirection.USAGE ? RecipePanelNodeType.USAGE : RecipePanelNodeType.SOURCE;
        RecipePanelNode node = new RecipePanelNode(uuid, parentUuid, nodeType, itemId, displayName, tree.getRecipes(), x, y);
        selectBestRecipe(node);
        nodes.put(uuid, node);
        GraphAnchorData parentAnchor = anchorForQueriedObject(parent, slot);
        GraphAnchorData childAnchor = GraphAnchorData.nodeCenter(node);
        if (direction == GraphExpansionDirection.SOURCE) {
            edges.add(new RecipeGraphEdge(uuid, parentUuid, itemId, direction, childAnchor, parentAnchor));
        } else {
            edges.add(new RecipeGraphEdge(parentUuid, uuid, itemId, direction, parentAnchor, childAnchor));
        }
        lockAnchorSide(parent, parentAnchor);
        dirty = true;
        return node;
    }

    private GraphAnchorData anchorForQueriedObject(RecipePanelNode node, SlotViewData slot) {
        if (node == null || slot == null) return GraphAnchorData.nodeCenter(node);
        if (isSubjectSlot(node, slot)) return GraphAnchorData.nodeCenter(node);
        return GraphAnchorData.slotCenter(node, slot, calculateRowAnchorOffset(slot), calculateSlotVerticalOffset(slot));
    }

    private boolean isSubjectSlot(RecipePanelNode node, SlotViewData slot) {
        if (node == null || slot == null || slot.getItem() == null) return false;
        if (node.getNodeType() == RecipePanelNodeType.SOURCE && slot.getType() != SlotViewType.OUTPUT) return false;
        if (node.getNodeType() == RecipePanelNodeType.USAGE && slot.getType() == SlotViewType.OUTPUT) return false;
        return matchesSubjectItem(slot.getItem(), node.getItemId());
    }

    private boolean matchesSubjectItem(IngredientData item, String subjectItemId) {
        if (item == null || subjectItemId == null || subjectItemId.isBlank()) return false;
        String itemId = normalizedItemId(item.getItemId());
        if (subjectItemId.equals(itemId)) return true;
        return item.getTagItems().contains(subjectItemId);
    }

    private boolean shouldHighlightCurrentSubject(RecipePanelNode parent, SlotViewData slot, GraphExpansionDirection direction) {
        return isSubjectSlot(parent, slot)
                && ((parent.getNodeType() == RecipePanelNodeType.SOURCE && direction == GraphExpansionDirection.SOURCE)
                || (parent.getNodeType() == RecipePanelNodeType.USAGE && direction == GraphExpansionDirection.USAGE));
    }

    private float calculateSlotVerticalOffset(SlotViewData slot) {
        if (slot == null) return 0.0f;
        return slot.getType() == SlotViewType.OUTPUT ? -4.0f : 4.0f;
    }

    private float calculateRowAnchorOffset(SlotViewData slot) {
        int row = Math.round(slot.getY() / 20.0f);
        return switch (Math.floorMod(row, 3)) {
            case 0 -> -6.0f;
            case 1 -> 0.0f;
            default -> 6.0f;
        };
    }

    private void lockAnchorSide(RecipePanelNode node, GraphAnchorData anchor) {
        if (node == null || anchor == null || anchor.getKind() == GraphAnchorKind.NODE_CENTER) return;
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
        List<RecipePanelNode> ordered = new ArrayList<>(nodes.values());
        Collections.reverse(ordered);
        for (RecipePanelNode node : ordered) {
            if (worldX >= node.getX() && worldX <= node.getX() + CraftingGraphConstants.NODE_WIDTH
                    && worldY >= node.getY() && worldY <= node.getY() + CraftingGraphConstants.NODE_HEIGHT) {
                return node;
            }
        }
        return null;
    }

    public boolean hitPreviousRecipe(RecipePanelNode node, float worldX, float worldY) {
        if (node == null || !node.canSwitchRecipe() || node.getRecipes().size() <= 1) return false;
        float x = node.getX() + CraftingGraphConstants.NODE_WIDTH - 42.0f;
        float y = node.getY() + CraftingGraphConstants.NODE_HEIGHT - 17.0f;
        return worldX >= x - 3.0f && worldX <= x + 12.0f && worldY >= y - 3.0f && worldY <= y + 10.0f;
    }

    public boolean hitNextRecipe(RecipePanelNode node, float worldX, float worldY) {
        if (node == null || !node.canSwitchRecipe() || node.getRecipes().size() <= 1) return false;
        float x = node.getX() + CraftingGraphConstants.NODE_WIDTH - 22.0f;
        float y = node.getY() + CraftingGraphConstants.NODE_HEIGHT - 17.0f;
        return worldX >= x - 3.0f && worldX <= x + 12.0f && worldY >= y - 3.0f && worldY <= y + 10.0f;
    }

    public boolean hitRecipePickerButton(RecipePanelNode node, float worldX, float worldY) {
        if (node == null || node.getRecipes().size() <= 1) return false;
        float x = recipePickerButtonX(node);
        float y = recipePickerButtonY(node);
        return worldX >= x && worldX <= x + 14.0f && worldY >= y && worldY <= y + 12.0f;
    }

    public int hitRecipePickerEntry(RecipePanelNode node, float worldX, float worldY) {
        if (node == null || !node.isRecipePickerOpen()) return -1;
        float pickerX = recipePickerX(node);
        float pickerY = recipePickerY(node);
        float pickerHeight = recipePickerHeight(node);
        if (worldX < pickerX || worldX > pickerX + CraftingGraphConstants.RECIPE_PICKER_WIDTH || worldY < pickerY || worldY > pickerY + pickerHeight) return -1;
        int columns = recipePickerColumns();
        float contentX = worldX - pickerX - CraftingGraphConstants.RECIPE_PICKER_PADDING;
        float contentY = worldY - pickerY - CraftingGraphConstants.RECIPE_PICKER_PADDING + node.getRecipePickerScroll();
        if (contentX < 0.0f || contentY < 0.0f) return -1;
        int col = (int) (contentX / CraftingGraphConstants.RECIPE_PICKER_CELL);
        int row = (int) (contentY / CraftingGraphConstants.RECIPE_PICKER_CELL);
        if (col < 0 || col >= columns) return -1;
        int index = row * columns + col;
        return index >= 0 && index < node.getRecipes().size() ? index : -1;
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
        List<RecipePanelNode> ordered = new ArrayList<>(nodes.values());
        Collections.reverse(ordered);
        for (RecipePanelNode node : ordered) {
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
    }

    public float recipePickerButtonX(RecipePanelNode node) {
        return node.getX() + 8.0f;
    }

    public float recipePickerButtonY(RecipePanelNode node) {
        return node.getY() + 22.0f;
    }

    public float recipePickerX(RecipePanelNode node) {
        return node.getX() + 28.0f;
    }

    public float recipePickerY(RecipePanelNode node) {
        return node.getY() + 20.0f;
    }

    public float recipePickerHeight(RecipePanelNode node) {
        int rows = (int) Math.ceil(node.getRecipes().size() / (float) recipePickerColumns());
        float contentHeight = rows * CraftingGraphConstants.RECIPE_PICKER_CELL + CraftingGraphConstants.RECIPE_PICKER_PADDING * 2.0f;
        return Math.min(CraftingGraphConstants.RECIPE_PICKER_MAX_HEIGHT, contentHeight);
    }

    public float maxRecipePickerScroll(RecipePanelNode node) {
        int rows = (int) Math.ceil(node.getRecipes().size() / (float) recipePickerColumns());
        float contentHeight = rows * CraftingGraphConstants.RECIPE_PICKER_CELL + CraftingGraphConstants.RECIPE_PICKER_PADDING * 2.0f;
        return Math.max(0.0f, contentHeight - recipePickerHeight(node));
    }

    public int recipePickerColumns() {
        return Math.max(1, (int) ((CraftingGraphConstants.RECIPE_PICKER_WIDTH - CraftingGraphConstants.RECIPE_PICKER_PADDING * 2.0f) / CraftingGraphConstants.RECIPE_PICKER_CELL));
    }

    public EdgeHitResult hitEdge(float worldX, float worldY) {
        EdgeHitResult best = null;
        float bestDistance = Float.MAX_VALUE;
        for (RecipeGraphEdge edge : edges) {
            GraphAnchorData from = edge.getFromAnchor();
            GraphAnchorData to = edge.getToAnchor();
            float midX = (from.getX() + from.getOffsetX() + to.getX() + to.getOffsetX()) * 0.5f;
            float midY = (from.getY() + from.getOffsetY() + to.getY() + to.getOffsetY()) * 0.5f;
            float dx = worldX - midX;
            float dy = worldY - midY;
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance && distance <= 144.0f) {
                bestDistance = distance;
                best = new EdgeHitResult(edge, midX, midY);
            }
        }
        return best;
    }

    public SlotHitResult hitSlot(float worldX, float worldY) {
        List<RecipePanelNode> ordered = new ArrayList<>(nodes.values());
        Collections.reverse(ordered);
        for (RecipePanelNode node : ordered) {
            UniversalRecipeViewModel model = getViewModel(node);
            float originX = node.getX() + 10.0f;
            float originY = node.getY() + 24.0f;
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

    public void toggleLocked(boolean locked) {
        mode = locked ? CraftingGraphInteractionMode.LOCKED : CraftingGraphInteractionMode.PASSIVE;
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
        return mode != CraftingGraphInteractionMode.PASSIVE || !nodes.isEmpty();
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
    public float getDrawerProgress() { return drawerProgress; }

    public void setCustomAlpha(float customAlpha) {
        this.customAlpha = Math.max(0.2f, Math.min(1.0f, customAlpha));
    }
}
