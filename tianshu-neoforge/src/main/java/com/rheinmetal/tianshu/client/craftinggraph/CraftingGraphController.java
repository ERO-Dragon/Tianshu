package com.rheinmetal.tianshu.client.craftinggraph;

import com.rheinmetal.tianshu.function.CraftingGraph.CraftingGraphEngine;
import com.rheinmetal.tianshu.function.CraftingGraph.CraftingGraphInteractionMode;
import com.rheinmetal.tianshu.function.CraftingGraph.CraftingGraphSaveData;
import com.rheinmetal.tianshu.function.CraftingGraph.CraftingGraphStorage;
import com.rheinmetal.tianshu.function.CraftingGraph.CraftingGraphStorage.StoredGraphEntry;
import com.rheinmetal.tianshu.function.CraftingGraph.EdgeHitResult;
import com.rheinmetal.tianshu.function.CraftingGraph.GraphExpansionDirection;
import com.rheinmetal.tianshu.function.CraftingGraph.RecipePanelNode;
import com.rheinmetal.tianshu.function.CraftingGraph.RecipePanelNodeType;
import com.rheinmetal.tianshu.function.CraftingGraph.SlotHitResult;
import com.rheinmetal.tianshu.provider.IInventoryDataProvider;
import com.rheinmetal.tianshu.provider.IRecipeDataProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

public final class CraftingGraphController {

    private final CraftingGraphEngine engine;
    private final CraftingGraphRenderer renderer;
    private final CraftingGraphStorage storage;
    private CraftingGraphSaveData pendingRecovery;
    private boolean recoveryPromptVisible;
    private boolean recoveryChecked;
    private String activeTopPanel;
    private float topPanelProgress;
    private List<StoredGraphEntry> topPanelEntries = new ArrayList<>();
    private float topPanelScroll;
    private boolean currentTreeFavorited;
    private boolean previousDirty;
    private boolean searchOpen;
    private String searchQuery = "";
    private List<SearchResult> searchResults = new ArrayList<>();
    private int searchSelection;
    private double lastMouseX;
    private double lastMouseY;
    private boolean interactionKeyDownState;
    private boolean middleDown;
    private BooleanSupplier interactionKeyDown = () -> GLFW.glfwGetKey(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS;
    private IntPredicate interactionKeyMatches = keyCode -> keyCode == GLFW.GLFW_KEY_TAB;

    public CraftingGraphController(IRecipeDataProvider recipeProvider, IInventoryDataProvider inventoryProvider, CraftingGraphStorage storage) {
        this.engine = new CraftingGraphEngine(recipeProvider, inventoryProvider);
        this.renderer = new CraftingGraphRenderer(engine);
        this.storage = storage;
    }

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        boolean currentInteractionKey = interactionKeyDown.getAsBoolean();
        boolean shiftDown = isShiftDown(window);
        if (currentInteractionKey != interactionKeyDownState) {
            interactionKeyDownState = currentInteractionKey;
            engine.setHeld(interactionKeyDownState || shiftDown);
        } else {
            engine.setHeld(interactionKeyDownState || shiftDown);
        }
        if (engine.shouldLockByMiddleHold(System.currentTimeMillis())) {
            engine.toggleLocked(true);
        }
        checkRecoveryPrompt();
        updateTopPanelProgress();
        updateFavoriteState();
        engine.tick();
        scheduleCrashRecoverySave();
    }

    public CraftingGraphRenderer getRenderer() {
        renderer.setRecoveryPromptVisible(recoveryPromptVisible);
        renderer.setTopPanel(activeTopPanel, topPanelProgress);
        renderer.setTopPanelEntries(topPanelEntries, topPanelScroll);
        renderer.setCurrentTreeFavorited(currentTreeFavorited);
        renderer.setSearchState(searchOpen, searchQuery, searchResults, searchSelection);
        return renderer;
    }

    public CraftingGraphEngine getEngine() {
        return engine;
    }

    public void setAlphaMultiplier(float alphaMultiplier) {
        engine.setCustomAlpha(alphaMultiplier);
    }

    public void setInteractionKey(BooleanSupplier interactionKeyDown, IntPredicate interactionKeyMatches) {
        if (interactionKeyDown != null) this.interactionKeyDown = interactionKeyDown;
        if (interactionKeyMatches != null) this.interactionKeyMatches = interactionKeyMatches;
    }

    public void shutdown() {
        if (storage != null) storage.shutdown();
    }

    private void scheduleCrashRecoverySave() {
        if (storage == null || !engine.isDirty()) return;
        if (storage.scheduleCrashRecoverySave(engine.createSaveData(), System.currentTimeMillis())) {
            engine.markClean();
        }
    }

    private void checkRecoveryPrompt() {
        if (recoveryChecked || storage == null || !engine.isEmpty()) return;
        recoveryChecked = true;
        try {
            CraftingGraphSaveData data = storage.loadCrashRecovery();
            if (data != null && data.nodes != null && !data.nodes.isEmpty()) {
                pendingRecovery = data;
                recoveryPromptVisible = true;
                engine.setHeld(true);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateTopPanelProgress() {
        float target = activeTopPanel != null ? 1.0f : 0.0f;
        topPanelProgress += (target - topPanelProgress) * 0.22f;
        if (Math.abs(target - topPanelProgress) < 0.01f) topPanelProgress = target;
    }

    private void toggleTopPanel(String panel) {
        activeTopPanel = panel != null && panel.equals(activeTopPanel) ? null : panel;
        topPanelScroll = 0.0f;
        reloadTopPanelEntries();
    }

    private void reloadTopPanelEntries() {
        topPanelEntries = new ArrayList<>();
        if (storage == null || activeTopPanel == null) return;
        try {
            topPanelEntries = new ArrayList<>("history".equals(activeTopPanel) ? storage.listHistory() : storage.listFavorites());
        } catch (Exception ignored) {
        }
    }

    private void scrollTopPanel(double scrollDelta) {
        if (activeTopPanel == null || topPanelEntries.isEmpty()) return;
        int contentHeight = topPanelEntries.size() * 28;
        int maxScroll = Math.max(0, contentHeight - 42);
        topPanelScroll = Math.max(0.0f, Math.min(maxScroll, topPanelScroll - (float) scrollDelta * 18.0f));
    }

    private void restoreTopPanelEntry(int index) {
        if (storage == null || index < 0 || index >= topPanelEntries.size()) return;
        try {
            CraftingGraphSaveData data = storage.loadEntry(topPanelEntries.get(index));
            if (data == null || data.nodes == null || data.nodes.isEmpty()) return;
            engine.restoreSaveData(data);
            currentTreeFavorited = "favorites".equals(activeTopPanel);
            previousDirty = engine.isDirty();
            activeTopPanel = null;
            topPanelScroll = 0.0f;
        } catch (Exception ignored) {
        }
    }

    private void updateFavoriteState() {
        boolean dirty = engine.isDirty();
        if (currentTreeFavorited && !previousDirty && dirty) currentTreeFavorited = false;
        if (engine.isEmpty()) currentTreeFavorited = false;
        previousDirty = dirty;
    }

    private void acceptRecovery() {
        if (pendingRecovery == null) return;
        engine.restoreSaveData(pendingRecovery);
        engine.markDirty();
        pendingRecovery = null;
        recoveryPromptVisible = false;
    }

    private void dismissRecovery() {
        pendingRecovery = null;
        recoveryPromptVisible = false;
        try {
            if (storage != null) storage.deleteCrashRecovery();
        } catch (Exception ignored) {
        }
    }

    private void saveFavorite() {
        if (storage == null || engine.isEmpty()) return;
        try {
            storage.saveFavorite("graph_" + System.currentTimeMillis(), engine.createSaveData());
            currentTreeFavorited = true;
            previousDirty = engine.isDirty();
        } catch (Exception ignored) {
        }
    }

    private void saveDuplicate() {
        if (storage == null || engine.isEmpty()) return;
        try {
            storage.saveFavorite("copy_" + System.currentTimeMillis(), engine.createSaveData());
            currentTreeFavorited = true;
            previousDirty = engine.isDirty();
        } catch (Exception ignored) {
        }
    }

    private void saveHistory() {
        if (storage == null || engine.isEmpty()) return;
        try {
            storage.saveHistory("history_" + System.currentTimeMillis(), engine.createSaveData());
        } catch (Exception ignored) {
        }
    }

    private void markTreeChanged() {
        currentTreeFavorited = false;
        previousDirty = engine.isDirty();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!engine.isInteractive() || !renderer.containsScreenPoint(mouseX, mouseY)) return false;
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            middleDown = true;
            engine.beginMiddlePress(System.currentTimeMillis());
            return true;
        }

        if (recoveryPromptVisible && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (renderer.hitRecoveryAcceptButton(mouseX, mouseY)) {
                acceptRecovery();
                return true;
            }
            if (renderer.hitRecoveryDismissButton(mouseX, mouseY)) {
                dismissRecovery();
                return true;
            }
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (searchOpen) {
                CraftingGraphRenderer.SearchHit searchHit = renderer.hitSearchResult(mouseX, mouseY);
                if (searchHit != null) {
                    activateSearchResult(searchHit.index(), searchHit.nodeType());
                    return true;
                }
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && renderer.hitSearchBox(mouseX, mouseY)) return true;
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && renderer.hitSearchEntryButton(mouseX, mouseY)) {
                openSearch();
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && renderer.hitFavoriteListToggle(mouseX, mouseY)) {
                toggleTopPanel("favorites");
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && renderer.hitHistoryListToggle(mouseX, mouseY)) {
                toggleTopPanel("history");
                return true;
            }
            int entryIndex = renderer.hitTopPanelEntry(mouseX, mouseY);
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && entryIndex >= 0) {
                restoreTopPanelEntry(entryIndex);
                return true;
            }

            float worldX = renderer.screenToGraphWorldX(mouseX);
            float worldY = renderer.screenToGraphWorldY(mouseY);
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && isShiftDown(Minecraft.getInstance().getWindow().getWindow())) {
                RecipePanelNode node = engine.hitNode(worldX, worldY);
                if (node != null && node.getParentUuid() != null) {
                    engine.deleteBranch(node.getUuid());
                    markTreeChanged();
                    return true;
                }
                EdgeHitResult edge = engine.hitEdge(worldX, worldY);
                if (edge != null) {
                    engine.deleteBranchFromEdge(edge.getEdge());
                    markTreeChanged();
                    return true;
                }
                return true;
            }

            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && renderer.hitFavoriteButton(mouseX, mouseY)) {
                saveFavorite();
                return true;
            }

            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && renderer.hitDuplicateButton(mouseX, mouseY)) {
                saveDuplicate();
                return true;
            }

            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && renderer.hitClearGraphButton(mouseX, mouseY)) {
                engine.clearGraph();
                markTreeChanged();
                return true;
            }

            if (engine.isEmpty()) {
                ItemStack hovered = getHoveredItemStack();
                if (!hovered.isEmpty()) {
                    String itemId = hovered.getItemHolder().getRegisteredName();
                    String displayName = hovered.getHoverName().getString();
                    Minecraft mc = Minecraft.getInstance();
                    RecipePanelNodeType nodeType = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? RecipePanelNodeType.USAGE : RecipePanelNodeType.SOURCE;
                    engine.createRoot(itemId, displayName, nodeType, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                    markTreeChanged();
                    saveHistory();
                    return true;
                }
            }

            RecipePanelNode pickerNode = engine.hitOpenRecipePicker(worldX, worldY);
            if (pickerNode != null) {
                int recipeIndex = engine.hitRecipePickerEntry(pickerNode, worldX, worldY);
                if (recipeIndex >= 0) {
                    engine.selectRecipeFromPicker(pickerNode, recipeIndex);
                    markTreeChanged();
                }
                return true;
            }
            SlotHitResult slot = engine.hitSlot(worldX, worldY);
            if (slot != null && slot.getSlot().getItem() != null) {
                String itemId = slot.getSlot().getItem().getItemId();
                String displayName = slot.getSlot().getItem().getDisplayName();
                if (itemId == null || itemId.isBlank() || itemId.startsWith("#")) return true;
                GraphExpansionDirection direction = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? GraphExpansionDirection.USAGE : GraphExpansionDirection.SOURCE;
                engine.expandFromSlot(slot.getNodeUuid(), slot.getSlot(), itemId, displayName, direction, System.currentTimeMillis());
                markTreeChanged();
                return true;
            }
            RecipePanelNode node = engine.hitNode(worldX, worldY);
            if (node != null) {
                if (engine.hitRecipePickerButton(node, worldX, worldY)) {
                    engine.toggleRecipePicker(node);
                    return true;
                }
                if (engine.hitPreviousRecipe(node, worldX, worldY)) {
                    node.previousRecipe();
                    markTreeChanged();
                    return true;
                }
                if (engine.hitNextRecipe(node, worldX, worldY)) {
                    node.nextRecipe();
                    markTreeChanged();
                    return true;
                }
                return true;
            }
        }

        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!renderer.containsScreenPoint(mouseX, mouseY) && button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && middleDown) {
            middleDown = false;
            engine.endMiddlePress();
            return engine.isInteractive();
        }
        return engine.isInteractive();
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!engine.isInteractive() || (!renderer.containsScreenPoint(mouseX, mouseY) && !middleDown)) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE || middleDown) {
            engine.getCamera().pan((float) dragX, (float) dragY);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        if (!engine.isInteractive() || !renderer.containsScreenPoint(mouseX, mouseY)) return false;
        if (renderer.containsTopPanelList(mouseX, mouseY)) {
            scrollTopPanel(scrollDelta);
            return true;
        }
        float worldX = renderer.screenToGraphWorldX(mouseX);
        float worldY = renderer.screenToGraphWorldY(mouseY);
        if (engine.scrollRecipePicker(worldX, worldY, scrollDelta)) return true;
        renderer.zoomAtScreenPoint(mouseX, mouseY, scrollDelta);
        return true;
    }

    public boolean keyPressed(int keyCode) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (keyCode == GLFW.GLFW_KEY_F && isControlDown(window)) {
            openSearch();
            return true;
        }
        if (searchOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeSearch();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchQuery.isEmpty()) {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                refreshSearchResults();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                RecipePanelNodeType nodeType = searchSelection >= 0 && searchSelection < searchResults.size() && searchResults.get(searchSelection).isRootEntry()
                        ? null
                        : RecipePanelNodeType.SOURCE;
                activateSearchResult(searchSelection, nodeType);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP && !searchResults.isEmpty()) {
                searchSelection = Math.max(0, searchSelection - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN && !searchResults.isEmpty()) {
                searchSelection = Math.min(searchResults.size() - 1, searchSelection + 1);
                return true;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && engine.getMode() == CraftingGraphInteractionMode.LOCKED) {
            engine.toggleLocked(false);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_Z && isControlDown(window) && engine.canUndoDelete()) {
            boolean undone = engine.undoDeleteBranch();
            if (undone) markTreeChanged();
            return undone;
        }
        return engine.isInteractive() && interactionKeyMatches.test(keyCode);
    }

    public boolean charTyped(char codePoint) {
        if (!searchOpen) return false;
        if (Character.isISOControl(codePoint)) return true;
        searchQuery += codePoint;
        refreshSearchResults();
        return true;
    }

    private void openSearch() {
        searchOpen = true;
        engine.setHeld(true);
        refreshSearchResults();
    }

    private void closeSearch() {
        searchOpen = false;
        searchQuery = "";
        searchResults = new ArrayList<>();
        searchSelection = 0;
    }

    private void refreshSearchResults() {
        searchResults = new ArrayList<>();
        String normalizedQuery = normalize(searchQuery);
        if (normalizedQuery.isBlank()) {
            searchSelection = 0;
            return;
        }
        for (RecipePanelNode node : engine.getNodes()) {
            String displayName = node.getDisplayName() != null ? node.getDisplayName() : "";
            String itemId = node.getItemId() != null ? node.getItemId() : "";
            String nodeType = node.getNodeType() == RecipePanelNodeType.USAGE ? "usage 用途" : "source 来源";
            String haystack = normalize(displayName + " " + itemId + " " + nodeType);
            if (haystack.contains(normalizedQuery)) {
                searchResults.add(SearchResult.node(node));
            }
        }
        if (searchResults.isEmpty()) {
            String itemId = searchQuery.trim();
            boolean sourceAvailable = engine.hasRecipes(itemId, RecipePanelNodeType.SOURCE);
            boolean usageAvailable = engine.hasRecipes(itemId, RecipePanelNodeType.USAGE);
            if (sourceAvailable || usageAvailable) searchResults.add(SearchResult.root(itemId, sourceAvailable, usageAvailable));
        }
        searchSelection = Math.max(0, Math.min(searchSelection, searchResults.size() - 1));
    }

    private void activateSearchResult(int index, RecipePanelNodeType requestedType) {
        if (index < 0 || index >= searchResults.size()) return;
        SearchResult result = searchResults.get(index);
        Minecraft mc = Minecraft.getInstance();
        if (result.node != null) {
            engine.focusNode(result.node, renderer.graphViewportWidth(), renderer.graphViewportHeight(), System.currentTimeMillis());
            searchOpen = false;
            return;
        }
        if (requestedType == null) return;
        RecipePanelNodeType nodeType = result.supports(requestedType) ? requestedType : null;
        if (nodeType == null) return;
        RecipePanelNode node = engine.createRoot(result.itemId, result.itemId, nodeType, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        engine.focusNode(node, renderer.graphViewportWidth(), renderer.graphViewportHeight(), System.currentTimeMillis());
        markTreeChanged();
        saveHistory();
        searchOpen = false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().trim();
    }

    public static final class SearchResult {
        private final RecipePanelNode node;
        private final String itemId;
        private final boolean sourceAvailable;
        private final boolean usageAvailable;

        private SearchResult(RecipePanelNode node, String itemId, boolean sourceAvailable, boolean usageAvailable) {
            this.node = node;
            this.itemId = itemId;
            this.sourceAvailable = sourceAvailable;
            this.usageAvailable = usageAvailable;
        }

        public static SearchResult node(RecipePanelNode node) {
            return new SearchResult(node, null, false, false);
        }

        public static SearchResult root(String itemId, boolean sourceAvailable, boolean usageAvailable) {
            return new SearchResult(null, itemId, sourceAvailable, usageAvailable);
        }

        public boolean isRootEntry() { return node == null; }
        public RecipePanelNode getNode() { return node; }
        public String getItemId() { return itemId; }
        public boolean isSourceAvailable() { return sourceAvailable; }
        public boolean isUsageAvailable() { return usageAvailable; }

        private boolean supports(RecipePanelNodeType nodeType) {
            return nodeType == RecipePanelNodeType.SOURCE ? sourceAvailable : usageAvailable;
        }
    }

    private boolean isShiftDown(long window) {
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private boolean isControlDown(long window) {
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private ItemStack getHoveredItemStack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> containerScreen) {
            try {
                var slot = containerScreen.getSlotUnderMouse();
                if (slot != null && slot.hasItem()) return slot.getItem();
            } catch (Exception ignored) {
            }
        }
        return ItemStack.EMPTY;
    }
}
