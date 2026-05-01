package com.rheinmetal.tianshu.client.craftinggraph;

import com.rheinmetal.tianshu.function.CraftingGraph.CraftingGraphConstants;
import com.rheinmetal.tianshu.function.CraftingGraph.CraftingGraphEngine;
import com.rheinmetal.tianshu.function.CraftingGraph.CraftingGraphStorage.StoredGraphEntry;
import com.rheinmetal.tianshu.function.CraftingGraph.EdgeHitResult;
import com.rheinmetal.tianshu.function.CraftingGraph.GraphAnchorData;
import com.rheinmetal.tianshu.function.CraftingGraph.RecipeGraphEdge;
import com.rheinmetal.tianshu.function.CraftingGraph.RecipePanelNode;
import com.rheinmetal.tianshu.function.CraftingGraph.RecipePanelNodeType;
import com.rheinmetal.tianshu.function.CraftingGraph.SlotViewData;
import com.rheinmetal.tianshu.function.CraftingGraph.SlotViewType;
import com.rheinmetal.tianshu.function.CraftingGraph.UniversalRecipeViewModel;
import com.rheinmetal.tianshu.snapshot.IngredientData;
import com.rheinmetal.tianshu.snapshot.RecipeData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CraftingGraphRenderer {

    private final CraftingGraphEngine engine;
    private final Map<String, ItemStack> itemCache = new HashMap<>();
    private boolean recoveryPromptVisible;
    private String topPanelType;
    private float topPanelProgress;
    private boolean currentTreeFavorited;
    private boolean searchOpen;
    private String searchQuery = "";
    private List<CraftingGraphController.SearchResult> searchResults = List.of();
    private int searchSelection;
    private List<StoredGraphEntry> topPanelEntries = List.of();
    private float topPanelScroll;

    public CraftingGraphRenderer(CraftingGraphEngine engine) {
        this.engine = engine;
    }

    public void render(GuiGraphics g, float partialTick) {
        if (engine == null || !engine.isVisible()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float alpha = engine.getAlpha();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int drawerW = drawerWidth(screenW);
        if (drawerW <= 0) return;
        int drawerH = screenH - CraftingGraphConstants.DRAWER_MARGIN * 2;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN;
        int topPanelOffset = topPanelOffset();
        int contentX = drawerX + CraftingGraphConstants.DRAWER_MARGIN;
        int contentY = drawerY + CraftingGraphConstants.DRAWER_MARGIN + 18 + topPanelOffset;

        drawDrawer(g, font, drawerX, drawerY, drawerW, drawerH, alpha);
        drawTopPanelToggleButtons(g, font, drawerX, drawerY, alpha);
        drawTopPanel(g, font, drawerX, drawerY, drawerW, alpha);
        drawCurrentTreeActionButtons(g, drawerX, drawerY + topPanelOffset, drawerW, alpha);
        g.enableScissor(drawerX, drawerY + topPanelOffset, drawerX + drawerW, drawerY + drawerH);

        g.pose().pushPose();
        g.pose().translate(contentX + engine.getCamera().getOffsetX(), contentY + engine.getCamera().getOffsetY(), 0.0f);
        g.pose().scale(engine.getCamera().getZoom(), engine.getCamera().getZoom(), 1.0f);

        for (RecipeGraphEdge edge : engine.getEdges()) {
            RecipePanelNode from = engine.getNode(edge.getFromNode());
            RecipePanelNode to = engine.getNode(edge.getToNode());
            if (from != null && to != null) drawEdge(g, edge, from, to, alpha);
        }

        long now = System.currentTimeMillis();
        for (RecipePanelNode node : engine.getNodes()) {
            drawNode(g, font, node, alpha, now);
        }
        for (RecipePanelNode node : engine.getNodes()) {
            if (node.isRecipePickerOpen()) drawRecipePicker(g, font, node, alpha, now);
        }
        if (isShiftDown()) drawDestructiveHints(g, alpha);

        g.pose().popPose();
        g.disableScissor();

        if (engine.isInteractive()) {
            int a = (int) (alpha * 180.0f) & 0xFF;
            g.drawString(font, "Tab 交互 | 中键拖拽 | 滚轮缩放 | 中键长按锁定 | Esc 解锁 | Ctrl+F 搜索", drawerX + 10, drawerY + drawerH - 14, (a << 24) | 0xFFFFFF, true);
        }
        if (recoveryPromptVisible) drawRecoveryPrompt(g, font, drawerX, drawerY, drawerW, drawerH, alpha);
    }

    public void setRecoveryPromptVisible(boolean recoveryPromptVisible) {
        this.recoveryPromptVisible = recoveryPromptVisible;
    }

    public void setTopPanel(String topPanelType, float topPanelProgress) {
        this.topPanelType = topPanelType;
        this.topPanelProgress = Math.max(0.0f, Math.min(1.0f, topPanelProgress));
    }

    public void setTopPanelEntries(List<StoredGraphEntry> topPanelEntries, float topPanelScroll) {
        this.topPanelEntries = topPanelEntries != null ? topPanelEntries : List.of();
        this.topPanelScroll = Math.max(0.0f, topPanelScroll);
    }

    public void setCurrentTreeFavorited(boolean currentTreeFavorited) {
        this.currentTreeFavorited = currentTreeFavorited;
    }

    public void setSearchState(boolean searchOpen, String searchQuery, List<CraftingGraphController.SearchResult> searchResults, int searchSelection) {
        this.searchOpen = searchOpen;
        this.searchQuery = searchQuery != null ? searchQuery : "";
        this.searchResults = searchResults != null ? searchResults : List.of();
        this.searchSelection = Math.max(0, searchSelection);
    }

    public int graphViewportWidth() {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        return Math.max(1, drawerW - CraftingGraphConstants.DRAWER_MARGIN * 2);
    }

    public int graphViewportHeight() {
        Minecraft mc = Minecraft.getInstance();
        int drawerH = mc.getWindow().getGuiScaledHeight() - CraftingGraphConstants.DRAWER_MARGIN * 2;
        return Math.max(1, drawerH - CraftingGraphConstants.DRAWER_MARGIN * 2 - 18 - topPanelOffset());
    }

    public boolean containsScreenPoint(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN;
        int drawerH = mc.getWindow().getGuiScaledHeight() - CraftingGraphConstants.DRAWER_MARGIN * 2;
        return mouseX >= drawerX && mouseX <= drawerX + drawerW && mouseY >= drawerY && mouseY <= drawerY + drawerH;
    }

    public float screenToGraphWorldX(double mouseX) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return 0.0f;
        int drawerX = drawerX(drawerW);
        int contentX = drawerX + CraftingGraphConstants.DRAWER_MARGIN;
        return engine.getCamera().screenToWorldX((float) mouseX - contentX);
    }

    public float screenToGraphWorldY(double mouseY) {
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN;
        int contentY = drawerY + CraftingGraphConstants.DRAWER_MARGIN + 18 + topPanelOffset();
        return engine.getCamera().screenToWorldY((float) mouseY - contentY);
    }

    public void zoomAtScreenPoint(double mouseX, double mouseY, double scrollDelta) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return;
        int drawerX = drawerX(drawerW);
        int contentX = drawerX + CraftingGraphConstants.DRAWER_MARGIN;
        int contentY = CraftingGraphConstants.DRAWER_MARGIN * 2 + 18 + topPanelOffset();
        engine.getCamera().zoomAt((float) mouseX - contentX, (float) mouseY - contentY, scrollDelta);
    }

    public boolean hitClearGraphButton(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0 || engine.isEmpty()) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN + topPanelOffset();
        int x = clearGraphButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        return mouseX >= x && mouseX <= x + 14 && mouseY >= y && mouseY <= y + 14;
    }

    public boolean hitFavoriteButton(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0 || engine.isEmpty()) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN + topPanelOffset();
        int x = currentFavoriteButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        return mouseX >= x && mouseX <= x + 14 && mouseY >= y && mouseY <= y + 14;
    }

    public boolean hitDuplicateButton(double mouseX, double mouseY) {
        if (!currentTreeFavorited) return false;
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0 || engine.isEmpty()) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN + topPanelOffset();
        int x = duplicateButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        return mouseX >= x && mouseX <= x + 14 && mouseY >= y && mouseY <= y + 14;
    }

    public boolean hitFavoriteListToggle(double mouseX, double mouseY) {
        return hitTopPanelToggle(mouseX, mouseY, 0);
    }

    public boolean hitHistoryListToggle(double mouseX, double mouseY) {
        return hitTopPanelToggle(mouseX, mouseY, 1);
    }

    public int hitTopPanelEntry(double mouseX, double mouseY) {
        if (topPanelProgress <= 0.75f || topPanelEntries.isEmpty()) return -1;
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return -1;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN;
        int x = drawerX + 18;
        int y = topPanelContentY(drawerY);
        int w = drawerW - 36;
        int h = topPanelListHeight();
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return -1;
        int index = (int) ((mouseY - y + topPanelScroll) / topPanelEntryHeight());
        return index >= 0 && index < topPanelEntries.size() ? index : -1;
    }

    public boolean containsTopPanelList(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0 || topPanelProgress <= 0.0f) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN;
        int x = drawerX + 18;
        int y = topPanelContentY(drawerY);
        int w = drawerW - 36;
        int h = topPanelListHeight();
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    public boolean hitSearchEntryButton(double mouseX, double mouseY) {
        if (searchOpen) return false;
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN;
        int x = searchEntryButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY + topPanelOffset());
        return mouseX >= x && mouseX <= x + 14 && mouseY >= y && mouseY <= y + 14;
    }

    public boolean hitSearchBox(double mouseX, double mouseY) {
        if (!searchOpen) return false;
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN;
        int x = searchPanelX(drawerX, drawerW);
        int y = searchPanelY(drawerY);
        return mouseX >= x && mouseX <= x + searchPanelW(drawerW) && mouseY >= y && mouseY <= y + searchPanelH();
    }

    public SearchHit hitSearchResult(double mouseX, double mouseY) {
        if (!searchOpen || searchResults.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return null;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN;
        int x = searchPanelX(drawerX, drawerW);
        int y = searchResultsY(drawerY);
        int w = searchPanelW(drawerW);
        int h = Math.min(4, searchResults.size()) * searchResultH();
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return null;
        int index = (int) ((mouseY - y) / searchResultH());
        if (index < 0 || index >= searchResults.size()) return null;
        CraftingGraphController.SearchResult result = searchResults.get(index);
        if (!result.isRootEntry()) return new SearchHit(index, RecipePanelNodeType.SOURCE);
        RecipePanelNodeType nodeType = hitRootTypeButton(result, mouseX, mouseY, x, y + index * searchResultH(), w);
        return nodeType != null ? new SearchHit(index, nodeType) : null;
    }

    private boolean hitTopPanelToggle(double mouseX, double mouseY, int index) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN;
        int x = topPanelToggleX(drawerX);
        int y = topPanelToggleY(drawerY, index);
        return mouseX >= x && mouseX <= x + 16 && mouseY >= y && mouseY <= y + 16;
    }

    public boolean hitRecoveryAcceptButton(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return false;
        int x = recoveryPromptX(drawerX(drawerW), drawerW) + 12;
        int y = recoveryPromptY(CraftingGraphConstants.DRAWER_MARGIN, mc.getWindow().getGuiScaledHeight() - CraftingGraphConstants.DRAWER_MARGIN * 2) + 58;
        return mouseX >= x && mouseX <= x + 52 && mouseY >= y && mouseY <= y + 18;
    }

    public boolean hitRecoveryDismissButton(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return false;
        int x = recoveryPromptX(drawerX(drawerW), drawerW) + 72;
        int y = recoveryPromptY(CraftingGraphConstants.DRAWER_MARGIN, mc.getWindow().getGuiScaledHeight() - CraftingGraphConstants.DRAWER_MARGIN * 2) + 58;
        return mouseX >= x && mouseX <= x + 52 && mouseY >= y && mouseY <= y + 18;
    }

    private boolean isShiftDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    private void drawDrawer(GuiGraphics g, Font font, int x, int y, int w, int h, float alpha) {
        int bg = color(alpha * 0.72f, 4, 9, 14);
        int edge = color(alpha, 74, 210, 255);
        drawCutPanel(g, x, y, w, h, bg, edge);
        g.drawString(font, "合成图谱", x + 12, y + 9, color(alpha, 119, 217, 255), true);
        g.fill(x + w - 2, y + 12, x + w, y + h - 12, color(alpha * 0.8f, 104, 230, 255));
    }

    private void drawClearGraphButton(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        if (engine.isEmpty()) return;
        int x = clearGraphButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        int bg = color(alpha * 0.46f, 70, 18, 20);
        int border = color(alpha, 255, 92, 92);
        g.fill(x, y, x + 14, y + 14, bg);
        drawRectBorder(g, x, y, 14, 14, border);
        drawLine(g, x + 4, y + 4, x + 10, y + 10, border);
        drawLine(g, x + 10, y + 4, x + 4, y + 10, border);
    }

    private void drawCurrentTreeActionButtons(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        if (searchOpen) {
            drawSearchPanel(g, Minecraft.getInstance().font, drawerX, drawerY, drawerW, alpha);
        } else {
            drawSearchEntryButton(g, drawerX, drawerY, drawerW, alpha);
        }
        if (engine.isEmpty()) return;
        drawCurrentFavoriteButton(g, drawerX, drawerY, drawerW, alpha);
        if (currentTreeFavorited) drawDuplicateButton(g, drawerX, drawerY, drawerW, alpha);
        drawClearGraphButton(g, drawerX, drawerY, drawerW, alpha);
    }

    private void drawSearchEntryButton(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        int x = searchEntryButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        int bg = color(alpha * 0.42f, 12, 30, 38);
        int border = color(alpha, 74, 210, 255);
        g.fill(x, y, x + 14, y + 14, bg);
        drawRectBorder(g, x, y, 14, 14, border);
        drawLine(g, x + 5, y + 5, x + 8, y + 5, border);
        drawLine(g, x + 4, y + 6, x + 4, y + 8, border);
        drawLine(g, x + 5, y + 9, x + 8, y + 9, border);
        drawLine(g, x + 9, y + 10, x + 12, y + 13, border);
    }

    private void drawCurrentFavoriteButton(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        int x = currentFavoriteButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        int bg = currentTreeFavorited ? color(alpha * 0.58f, 72, 58, 16) : color(alpha * 0.42f, 54, 46, 14);
        int border = color(alpha, 255, 226, 104);
        g.fill(x, y, x + 14, y + 14, bg);
        drawRectBorder(g, x, y, 14, 14, border);
        drawStar(g, x, y, border);
        if (currentTreeFavorited) g.fill(x + 5, y + 5, x + 9, y + 9, border);
    }

    private void drawDuplicateButton(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        int x = duplicateButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        int bg = color(alpha * 0.34f, 54, 46, 14);
        int border = color(alpha, 255, 226, 104);
        g.fill(x, y, x + 14, y + 14, bg);
        drawRectBorder(g, x, y, 14, 14, border);
        drawStar(g, x, y, border);
        drawLine(g, x + 10, y + 2, x + 10, y + 7, border);
        drawLine(g, x + 8, y + 4, x + 13, y + 4, border);
    }

    private void drawTopPanelToggleButtons(GuiGraphics g, Font font, int drawerX, int drawerY, float alpha) {
        drawTopPanelToggle(g, font, drawerX, drawerY, 0, "★", "favorites".equals(topPanelType), alpha);
        drawTopPanelToggle(g, font, drawerX, drawerY, 1, "H", "history".equals(topPanelType), alpha);
    }

    private void drawTopPanelToggle(GuiGraphics g, Font font, int drawerX, int drawerY, int index, String text, boolean active, float alpha) {
        int x = topPanelToggleX(drawerX);
        int y = topPanelToggleY(drawerY, index);
        int bg = active ? color(alpha * 0.68f, 42, 72, 84) : color(alpha * 0.42f, 12, 30, 38);
        int border = active ? color(alpha, 255, 226, 104) : color(alpha, 74, 210, 255);
        g.fill(x, y, x + 16, y + 16, bg);
        drawRectBorder(g, x, y, 16, 16, border);
        g.drawString(font, text, x + (16 - font.width(text)) / 2, y + 4, border, false);
    }

    private void drawTopPanel(GuiGraphics g, Font font, int drawerX, int drawerY, int drawerW, float alpha) {
        if (topPanelProgress <= 0.0f) return;
        int h = topPanelOffset();
        int y = drawerY + 26 - Math.round((1.0f - topPanelProgress) * topPanelMaxHeight());
        int panelH = Math.max(0, h - 26);
        if (panelH <= 0) return;
        int bg = color(alpha * 0.82f, 5, 13, 20);
        int border = color(alpha, 74, 210, 255);
        drawCutPanel(g, drawerX + 8, y, drawerW - 16, panelH, bg, border);
        String title = "history".equals(topPanelType) ? "历史记录" : "收藏列表";
        g.drawString(font, title, drawerX + 22, y + 9, color(alpha, 238, 252, 255), true);
        drawTopPanelEntries(g, font, drawerX, drawerY, drawerW, alpha);
    }

    private void drawTopPanelEntries(GuiGraphics g, Font font, int drawerX, int drawerY, int drawerW, float alpha) {
        int x = drawerX + 18;
        int y = topPanelContentY(drawerY);
        int w = drawerW - 36;
        int h = topPanelListHeight();
        if (h <= 0) return;
        g.enableScissor(x, y, x + w, y + h);
        if (topPanelEntries.isEmpty()) {
            g.drawString(font, "暂无条目", x + 4, y + 8, color(alpha * 0.72f, 156, 180, 190), false);
        } else {
            int entryH = topPanelEntryHeight();
            int start = Math.max(0, (int) (topPanelScroll / entryH));
            int end = Math.min(topPanelEntries.size(), start + h / entryH + 2);
            for (int i = start; i < end; i++) {
                StoredGraphEntry entry = topPanelEntries.get(i);
                int entryY = y + i * entryH - Math.round(topPanelScroll);
                drawTopPanelEntry(g, font, entry, x, entryY, w, entryH, alpha);
            }
        }
        g.disableScissor();
        drawTopPanelScrollbar(g, x, y, w, h, alpha);
    }

    private void drawTopPanelEntry(GuiGraphics g, Font font, StoredGraphEntry entry, int x, int y, int w, int h, float alpha) {
        int bg = color(alpha * 0.34f, 12, 30, 38);
        int border = color(alpha * 0.64f, 74, 210, 255);
        g.fill(x, y, x + w, y + h - 2, bg);
        drawRectBorder(g, x, y, w, h - 2, border);
        String name = trimToWidth(font, entry.getDisplayName(), w - 58);
        g.drawString(font, name, x + 5, y + 4, color(alpha, 238, 252, 255), false);
        String meta = entry.getNodeCount() + " 节点 / " + entry.getEdgeCount() + " 线";
        g.drawString(font, meta, x + 5, y + 15, color(alpha * 0.72f, 156, 180, 190), false);
    }

    private void drawSearchPanel(GuiGraphics g, Font font, int drawerX, int drawerY, int drawerW, float alpha) {
        int x = searchPanelX(drawerX, drawerW);
        int y = searchPanelY(drawerY);
        int w = searchPanelW(drawerW);
        int bg = color(alpha * 0.88f, 5, 13, 20);
        int border = color(alpha, 255, 226, 104);
        drawCutPanel(g, x, y, w, searchPanelH(), bg, border);
        String value = searchQuery.isEmpty() ? "搜索 itemId / 名称" : searchQuery;
        int textColor = searchQuery.isEmpty() ? color(alpha * 0.62f, 156, 180, 190) : color(alpha, 238, 252, 255);
        g.drawString(font, trimToWidth(font, value, w - 16), x + 8, y + 5, textColor, false);
        drawSearchResults(g, font, x, searchResultsY(drawerY), w, alpha);
    }

    private void drawSearchResults(GuiGraphics g, Font font, int x, int y, int w, float alpha) {
        int visible = Math.min(4, searchResults.size());
        if (visible == 0) {
            if (!searchQuery.isBlank()) g.drawString(font, "无匹配节点；输入完整 itemId 后选择来源/用途生根", x + 8, y + 4, color(alpha * 0.72f, 156, 180, 190), false);
            return;
        }
        for (int i = 0; i < visible; i++) {
            CraftingGraphController.SearchResult result = searchResults.get(i);
            int rowY = y + i * searchResultH();
            int rowBg = i == searchSelection ? color(alpha * 0.46f, 78, 58, 18) : color(alpha * 0.36f, 12, 30, 38);
            int rowBorder = i == searchSelection ? color(alpha, 255, 226, 104) : color(alpha * 0.62f, 74, 210, 255);
            g.fill(x, rowY, x + w, rowY + searchResultH() - 2, rowBg);
            drawRectBorder(g, x, rowY, w, searchResultH() - 2, rowBorder);
            if (result.isRootEntry()) {
                drawRootSearchEntry(g, font, result, x, rowY, w, alpha);
            } else {
                drawNodeSearchEntry(g, font, result.getNode(), x, rowY, w, alpha);
            }
        }
    }

    private void drawNodeSearchEntry(GuiGraphics g, Font font, RecipePanelNode node, int x, int y, int w, float alpha) {
        boolean usage = node.getNodeType() == RecipePanelNodeType.USAGE;
        String badge = usage ? "用途" : "来源";
        int badgeW = 28;
        int badgeBg = usage ? color(alpha * 0.48f, 26, 54, 34) : color(alpha * 0.48f, 38, 42, 72);
        int badgeBorder = usage ? color(alpha, 112, 230, 142) : color(alpha, 124, 170, 255);
        g.fill(x + 5, y + 5, x + 5 + badgeW, y + 17, badgeBg);
        drawRectBorder(g, x + 5, y + 5, badgeW, 12, badgeBorder);
        g.drawString(font, badge, x + 5 + (badgeW - font.width(badge)) / 2, y + 7, badgeBorder, false);
        String title = node.getDisplayName();
        String meta = node.getItemId();
        g.drawString(font, trimToWidth(font, title, w - badgeW - 22), x + 10 + badgeW, y + 4, color(alpha, 238, 252, 255), false);
        g.drawString(font, trimToWidth(font, meta, w - badgeW - 22), x + 10 + badgeW, y + 15, color(alpha * 0.74f, 156, 180, 190), false);
    }

    private void drawRootSearchEntry(GuiGraphics g, Font font, CraftingGraphController.SearchResult result, int x, int y, int w, float alpha) {
        g.drawString(font, trimToWidth(font, "+ " + result.getItemId(), w - 98), x + 6, y + 5, color(alpha, 238, 252, 255), false);
        drawRootTypeButton(g, font, x + w - 86, y + 5, 38, 16, "来源", result.isSourceAvailable(), color(alpha, 124, 170, 255), alpha);
        drawRootTypeButton(g, font, x + w - 44, y + 5, 38, 16, "用途", result.isUsageAvailable(), color(alpha, 112, 230, 142), alpha);
    }

    private void drawRootTypeButton(GuiGraphics g, Font font, int x, int y, int w, int h, String text, boolean enabled, int border, float alpha) {
        int bg = enabled ? color(alpha * 0.42f, 12, 30, 38) : color(alpha * 0.18f, 36, 36, 36);
        int edge = enabled ? border : color(alpha * 0.34f, 104, 104, 104);
        int textColor = enabled ? border : color(alpha * 0.44f, 140, 140, 140);
        g.fill(x, y, x + w, y + h, bg);
        drawRectBorder(g, x, y, w, h, edge);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + 5, textColor, false);
    }

    private RecipePanelNodeType hitRootTypeButton(CraftingGraphController.SearchResult result, double mouseX, double mouseY, int x, int y, int w) {
        if (mouseY < y + 5 || mouseY > y + 21) return null;
        int sourceX = x + w - 86;
        int usageX = x + w - 44;
        if (result.isSourceAvailable() && mouseX >= sourceX && mouseX <= sourceX + 38) return RecipePanelNodeType.SOURCE;
        if (result.isUsageAvailable() && mouseX >= usageX && mouseX <= usageX + 38) return RecipePanelNodeType.USAGE;
        return null;
    }

    private int searchEntryButtonX(int drawerX, int drawerW) {
        return searchPanelX(drawerX, drawerW);
    }

    private int searchPanelX(int drawerX, int drawerW) {
        return drawerX + Math.min(130, Math.max(66, drawerW / 3));
    }

    private int searchPanelY(int drawerY) {
        return currentTreeActionButtonY(drawerY);
    }

    private int searchResultsY(int drawerY) {
        return searchPanelY(drawerY) + 20;
    }

    private int searchPanelW(int drawerW) {
        int actionLeft = currentFavoriteButtonX(0, drawerW);
        int searchX = searchPanelX(0, drawerW);
        return Math.max(96, actionLeft - searchX - 10);
    }

    private int searchPanelH() {
        return 16;
    }

    private int searchResultH() {
        return 28;
    }

    public record SearchHit(int index, RecipePanelNodeType nodeType) {
    }

    private void drawTopPanelScrollbar(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        int contentH = topPanelEntries.size() * topPanelEntryHeight();
        if (contentH <= h) return;
        int barH = Math.max(12, h * h / contentH);
        int maxScroll = Math.max(1, contentH - h);
        int barY = y + Math.round((h - barH) * (topPanelScroll / maxScroll));
        int barX = x + w - 4;
        g.fill(barX, y, barX + 2, y + h, color(alpha * 0.28f, 74, 210, 255));
        g.fill(barX - 1, barY, barX + 3, barY + barH, color(alpha, 74, 210, 255));
    }

    private String trimToWidth(Font font, String text, int width) {
        if (text == null) return "";
        if (font.width(text) <= width) return text;
        String suffix = "...";
        int suffixWidth = font.width(suffix);
        String result = text;
        while (!result.isEmpty() && font.width(result) + suffixWidth > width) {
            result = result.substring(0, result.length() - 1);
        }
        return result + suffix;
    }

    private int topPanelContentY(int drawerY) {
        return drawerY + 58;
    }

    private int topPanelListHeight() {
        return 42;
    }

    private int topPanelEntryHeight() {
        return 28;
    }

    private void drawStar(GuiGraphics g, int x, int y, int color) {
        drawLine(g, x + 7, y + 2, x + 9, y + 6, color);
        drawLine(g, x + 9, y + 6, x + 13, y + 6, color);
        drawLine(g, x + 13, y + 6, x + 10, y + 9, color);
        drawLine(g, x + 10, y + 9, x + 11, y + 13, color);
        drawLine(g, x + 11, y + 13, x + 7, y + 10, color);
        drawLine(g, x + 7, y + 10, x + 3, y + 13, color);
        drawLine(g, x + 3, y + 13, x + 4, y + 9, color);
        drawLine(g, x + 4, y + 9, x + 1, y + 6, color);
        drawLine(g, x + 1, y + 6, x + 5, y + 6, color);
        drawLine(g, x + 5, y + 6, x + 7, y + 2, color);
    }

    private int currentFavoriteButtonX(int drawerX, int drawerW) {
        return drawerX + drawerW - 60;
    }

    private int duplicateButtonX(int drawerX, int drawerW) {
        return drawerX + drawerW - 42;
    }

    private int clearGraphButtonX(int drawerX, int drawerW) {
        return drawerX + drawerW - 24;
    }

    private int currentTreeActionButtonY(int drawerY) {
        return drawerY + 7;
    }

    private int topPanelToggleX(int drawerX) {
        return drawerX + 10;
    }

    private int topPanelToggleY(int drawerY, int index) {
        return drawerY + 28 + index * 20;
    }

    private int topPanelOffset() {
        return Math.round(topPanelMaxHeight() * topPanelProgress);
    }

    private int topPanelMaxHeight() {
        return 104;
    }

    private void drawRecoveryPrompt(GuiGraphics g, Font font, int drawerX, int drawerY, int drawerW, int drawerH, float alpha) {
        int x = recoveryPromptX(drawerX, drawerW);
        int y = recoveryPromptY(drawerY, drawerH);
        int w = Math.min(184, Math.max(132, drawerW - 24));
        int h = 88;
        g.fill(drawerX, drawerY, drawerX + drawerW, drawerY + drawerH, color(alpha * 0.38f, 0, 0, 0));
        drawCutPanel(g, x, y, w, h, color(alpha * 0.9f, 5, 13, 20), color(alpha, 255, 210, 80));
        g.drawString(font, "发现未恢复图谱", x + 12, y + 10, color(alpha, 255, 226, 104), true);
        g.drawString(font, "是否恢复上次自动保存？", x + 12, y + 28, color(alpha, 238, 252, 255), false);
        drawPromptButton(g, font, x + 12, y + 58, 52, 18, "恢复", color(alpha, 38, 84, 58), color(alpha, 96, 230, 142));
        drawPromptButton(g, font, x + 72, y + 58, 52, 18, "丢弃", color(alpha, 70, 28, 28), color(alpha, 255, 112, 112));
    }

    private void drawPromptButton(GuiGraphics g, Font font, int x, int y, int w, int h, String text, int bg, int border) {
        g.fill(x, y, x + w, y + h, bg);
        drawRectBorder(g, x, y, w, h, border);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + 5, border, false);
    }

    private int recoveryPromptX(int drawerX, int drawerW) {
        int w = Math.min(184, Math.max(132, drawerW - 24));
        return drawerX + Math.max(8, (drawerW - w) / 2);
    }

    private int recoveryPromptY(int drawerY, int drawerH) {
        return drawerY + Math.max(24, (drawerH - 88) / 2);
    }

    private int drawerWidth(int screenW) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AbstractContainerScreen<?> screen) {
            int leftSpace = screen.getGuiLeft() - CraftingGraphConstants.DRAWER_MARGIN - CraftingGraphConstants.DRAWER_CONTAINER_GAP;
            return Math.max(0, Math.min(CraftingGraphConstants.DRAWER_MAX_WIDTH, leftSpace));
        }
        int fallbackWidth = screenW / 3;
        return Math.max(CraftingGraphConstants.DRAWER_MIN_WIDTH, Math.min(CraftingGraphConstants.DRAWER_MAX_WIDTH, fallbackWidth));
    }

    private int drawerX(int drawerW) {
        float closedX = -drawerW + CraftingGraphConstants.DRAWER_CLOSED_PEEK;
        float openX = CraftingGraphConstants.DRAWER_MARGIN;
        return (int) (closedX + (openX - closedX) * engine.getDrawerProgress());
    }

    private void drawNode(GuiGraphics g, Font font, RecipePanelNode node, float alpha, long now) {
        int bg = color(alpha * 0.68f, 6, 12, 18);
        int border = node.isHighlighted(now) ? color(alpha, 255, 210, 80) : color(alpha, 74, 210, 255);
        int text = color(alpha, 238, 252, 255);
        int muted = color(alpha * 0.7f, 156, 180, 190);
        int x = (int) node.getX();
        int y = (int) node.getY();
        int w = (int) CraftingGraphConstants.NODE_WIDTH;
        int h = (int) CraftingGraphConstants.NODE_HEIGHT;

        drawCutPanel(g, x, y, w, h, bg, border);
        drawRecipePickerButton(g, node, alpha);
        drawNodeTypeIcon(g, node, x + w - 18, y + 10, color(alpha, 74, 210, 255));
        g.drawString(font, trim(font, node.getDisplayName(), w - 34), x + 9, y + 8, text, false);
        g.drawString(font, node.getRecipes().size() + " 配方", x + 9, y + h - 14, muted, false);
        drawRecipePager(g, font, node, x, y, w, h, alpha);

        UniversalRecipeViewModel model = engine.getViewModel(node);
        int ox = x + 10;
        int oy = y + 24;
        for (SlotViewData slot : model.getSlots()) {
            drawSlot(g, font, node, slot, ox, oy, alpha);
        }
    }

    private void drawDestructiveHints(GuiGraphics g, float alpha) {
        int color = color(alpha, 255, 80, 80);
        for (RecipeGraphEdge edge : engine.getEdges()) {
            EdgeHitResult hit = edgeMidpoint(edge);
            drawScissor(g, Math.round(hit.getX()), Math.round(hit.getY()), color);
        }
        for (RecipePanelNode node : engine.getNodes()) {
            if (node.getParentUuid() == null) continue;
            int x = Math.round(node.getX() + CraftingGraphConstants.NODE_WIDTH - 12.0f);
            int y = Math.round(node.getY() + 8.0f);
            drawDeleteX(g, x, y, color);
        }
    }

    private EdgeHitResult edgeMidpoint(RecipeGraphEdge edge) {
        GraphAnchorData from = edge.getFromAnchor();
        GraphAnchorData to = edge.getToAnchor();
        float midX = (from.getX() + from.getOffsetX() + to.getX() + to.getOffsetX()) * 0.5f;
        float midY = (from.getY() + from.getOffsetY() + to.getY() + to.getOffsetY()) * 0.5f;
        return new EdgeHitResult(edge, midX, midY);
    }

    private void drawScissor(GuiGraphics g, int x, int y, int color) {
        drawLine(g, x - 5, y - 3, x + 5, y + 3, color);
        drawLine(g, x - 5, y + 3, x + 5, y - 3, color);
        g.fill(x - 7, y - 5, x - 3, y - 1, color);
        g.fill(x - 7, y + 1, x - 3, y + 5, color);
    }

    private void drawDeleteX(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 7, y - 7, x + 7, y + 7, color(0.44f, 80, 8, 8));
        drawRectBorder(g, x - 7, y - 7, 14, 14, color);
        drawLine(g, x - 4, y - 4, x + 4, y + 4, color);
        drawLine(g, x + 4, y - 4, x - 4, y + 4, color);
    }

    private void drawRecipePickerButton(GuiGraphics g, RecipePanelNode node, float alpha) {
        if (node.getRecipes().size() <= 1) return;
        int x = Math.round(engine.recipePickerButtonX(node));
        int y = Math.round(engine.recipePickerButtonY(node));
        int bg = node.isRecipePickerOpen() ? color(alpha * 0.7f, 28, 70, 84) : color(alpha * 0.42f, 20, 44, 52);
        int border = node.canSwitchRecipe() ? color(alpha, 94, 224, 255) : color(alpha * 0.45f, 92, 104, 112);
        g.fill(x, y, x + 14, y + 12, bg);
        drawRectBorder(g, x, y, 14, 12, border);
        g.fill(x + 3, y + 3, x + 11, y + 5, border);
        g.fill(x + 3, y + 7, x + 9, y + 9, border);
    }

    private void drawNodeTypeIcon(GuiGraphics g, RecipePanelNode node, int x, int y, int color) {
        if (node.getNodeType() == RecipePanelNodeType.SOURCE) {
            drawLine(g, x - 8, y - 5, x - 2, y, color);
            drawLine(g, x - 8, y, x - 2, y, color);
            drawLine(g, x - 8, y + 5, x - 2, y, color);
            drawLine(g, x - 2, y, x + 5, y, color);
            g.fill(x + 3, y - 2, x + 7, y + 3, color);
        } else {
            drawLine(g, x - 6, y, x + 1, y, color);
            drawLine(g, x + 1, y, x + 7, y - 5, color);
            drawLine(g, x + 1, y, x + 7, y, color);
            drawLine(g, x + 1, y, x + 7, y + 5, color);
            g.fill(x + 5, y - 7, x + 8, y - 4, color);
            g.fill(x + 5, y - 1, x + 8, y + 2, color);
            g.fill(x + 5, y + 4, x + 8, y + 7, color);
        }
    }

    private void drawRecipePicker(GuiGraphics g, Font font, RecipePanelNode node, float alpha, long now) {
        int x = Math.round(engine.recipePickerX(node));
        int y = Math.round(engine.recipePickerY(node));
        int w = Math.round(CraftingGraphConstants.RECIPE_PICKER_WIDTH);
        int h = Math.round(engine.recipePickerHeight(node));
        int bg = color(alpha * 0.82f, 5, 13, 20);
        int border = node.canSwitchRecipe() ? color(alpha, 94, 224, 255) : color(alpha * 0.45f, 92, 104, 112);
        drawCutPanel(g, x, y, w, h, bg, border);
        g.enableScissor(x, y, x + w, y + h);
        g.pose().pushPose();
        g.pose().translate(0.0f, -node.getRecipePickerScroll(), 0.0f);
        int columns = engine.recipePickerColumns();
        int selected = node.getSelectedRecipeIndex();
        int cell = Math.round(CraftingGraphConstants.RECIPE_PICKER_CELL);
        int pad = Math.round(CraftingGraphConstants.RECIPE_PICKER_PADDING);
        for (int i = 0; i < node.getRecipes().size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int cellX = x + pad + col * cell;
            int cellY = y + pad + row * cell;
            drawRecipePickerEntry(g, font, node, node.getRecipes().get(i), i, selected, cellX, cellY, alpha, now);
        }
        g.pose().popPose();
        g.disableScissor();
        if (engine.maxRecipePickerScroll(node) > 0.0f) {
            int trackX = x + w - 5;
            int trackH = h - 10;
            float ratio = node.getRecipePickerScroll() / engine.maxRecipePickerScroll(node);
            int thumbY = y + 5 + Math.round((trackH - 14) * ratio);
            g.fill(trackX, y + 5, trackX + 2, y + h - 5, color(alpha * 0.28f, 120, 210, 230));
            g.fill(trackX - 1, thumbY, trackX + 3, thumbY + 14, color(alpha, 94, 224, 255));
        }
    }

    private void drawRecipePickerEntry(GuiGraphics g, Font font, RecipePanelNode node, RecipeData recipe, int index, int selected, int x, int y, float alpha, long now) {
        int border = index == selected ? color(alpha, 255, 210, 80) : node.canSwitchRecipe() ? color(alpha, 74, 210, 255) : color(alpha * 0.38f, 92, 104, 112);
        int bg = index == selected ? color(alpha * 0.42f, 78, 58, 18) : color(alpha * 0.42f, 12, 30, 38);
        g.fill(x, y, x + 20, y + 20, bg);
        drawRectBorder(g, x, y, 20, 20, border);
        IngredientData icon = recipeIcon(node, recipe, index, now);
        ItemStack stack = resolveItemStack(icon);
        if (stack != null) g.renderItem(stack, x + 2, y + 2);
        if (!node.canSwitchRecipe()) g.fill(x + 1, y + 1, x + 19, y + 19, color(alpha * 0.42f, 12, 12, 12));
        if (index == selected) g.drawString(font, "•", x + 14, y + 10, color(alpha, 255, 226, 104), false);
    }

    private IngredientData recipeIcon(RecipePanelNode node, RecipeData recipe, int index, long now) {
        if (recipe == null) return null;
        if (node.getNodeType() == RecipePanelNodeType.USAGE && recipe.getResult() != null) return recipe.getResult();
        if (recipe.getIngredients().isEmpty()) return recipe.getResult();
        int cycle = Math.max(1, recipe.getIngredients().size());
        int offset = (int) ((now / 700L + index) % cycle);
        return recipe.getIngredients().get(offset);
    }

    private void drawRecipePager(GuiGraphics g, Font font, RecipePanelNode node, int x, int y, int w, int h, float alpha) {
        if (node.getRecipes().size() <= 1) return;
        int active = node.canSwitchRecipe() ? color(alpha, 238, 252, 255) : color(alpha * 0.45f, 92, 104, 112);
        int bg = node.canSwitchRecipe() ? color(alpha * 0.42f, 20, 44, 52) : color(alpha * 0.24f, 24, 28, 32);
        int cy = y + h - 14;
        int leftX = x + w - 42;
        int rightX = x + w - 22;
        g.fill(leftX - 3, cy - 3, leftX + 12, cy + 10, bg);
        g.fill(rightX - 3, cy - 3, rightX + 12, cy + 10, bg);
        g.drawString(font, "<", leftX, cy, active, false);
        g.drawString(font, ">", rightX, cy, active, false);
        String index = (node.getSelectedRecipeIndex() + 1) + "/" + node.getRecipes().size();
        g.drawString(font, index, x + w - 78, cy, active, false);
    }

    private void drawSlot(GuiGraphics g, Font font, RecipePanelNode node, SlotViewData slot, int originX, int originY, float alpha) {
        IngredientData item = slot.getItem();
        int x = originX + (int) slot.getX();
        int y = originY + (int) slot.getY();
        boolean output = slot.getType() == SlotViewType.OUTPUT;
        boolean satisfied = output || engine.isIngredientSatisfied(item);
        int bg = output
                ? color(alpha * 0.84f, 32, 72, 82)
                : satisfied ? color(alpha * 0.72f, 24, 52, 34) : color(alpha * 0.72f, 72, 46, 18);
        int border = output
                ? color(alpha, 94, 224, 255)
                : satisfied ? color(alpha, 72, 204, 112) : color(alpha, 238, 162, 74);
        g.fill(x - 2, y - 2, x + 18, y + 18, bg);
        drawRectBorder(g, x - 2, y - 2, 20, 20, border);

        ItemStack stack = resolveItemStack(item);
        if (stack != null) {
            g.renderItem(stack, x, y);
            if (item != null && item.getCount() > 1) {
                g.renderItemDecorations(font, stack, x, y, String.valueOf(item.getCount()));
            }
        }

        if (!output && item != null) {
            int available = engine.getAvailableCount(item);
            int required = engine.getRequiredCount(item);
            if (required > 1 || available < required) {
                String amount = Math.min(available, 99) + "/" + Math.min(required, 99);
                int amountColor = satisfied ? color(alpha, 158, 255, 176) : color(alpha, 255, 190, 96);
                g.drawString(font, amount, x + 1, y + 18, amountColor, true);
            }
        }

        if (isSubjectSlot(node, slot)) {
            drawSubjectBadge(g, x, y, alpha);
        }
    }

    private boolean isSubjectSlot(RecipePanelNode node, SlotViewData slot) {
        if (node == null || slot == null || slot.getItem() == null) return false;
        if (node.getNodeType() == RecipePanelNodeType.SOURCE && slot.getType() != SlotViewType.OUTPUT) return false;
        if (node.getNodeType() == RecipePanelNodeType.USAGE && slot.getType() == SlotViewType.OUTPUT) return false;
        return matchesSubjectItem(slot.getItem(), node.getItemId());
    }

    private boolean matchesSubjectItem(IngredientData item, String subjectItemId) {
        if (item == null || subjectItemId == null || subjectItemId.isBlank()) return false;
        String normalized = item.getItemId();
        if (normalized != null && normalized.contains("/")) normalized = normalized.substring(0, normalized.indexOf('/'));
        if (subjectItemId.equals(normalized)) return true;
        return item.getTagItems().contains(subjectItemId);
    }

    private void drawSubjectBadge(GuiGraphics g, int x, int y, float alpha) {
        int outer = color(alpha, 74, 210, 255);
        int inner = color(alpha * 0.88f, 8, 18, 26);
        g.fill(x + 10, y + 10, x + 16, y + 16, outer);
        g.fill(x + 12, y + 12, x + 16, y + 16, inner);
        g.fill(x + 14, y + 14, x + 16, y + 16, outer);
    }

    private void drawEdge(GuiGraphics g, RecipeGraphEdge edge, RecipePanelNode from, RecipePanelNode to, float alpha) {
        int color = color(alpha, 74, 210, 255);
        GraphAnchorData fromAnchor = edge.getFromAnchor();
        GraphAnchorData toAnchor = edge.getToAnchor();
        int rawOriginX = Math.round(fromAnchor.getX() + fromAnchor.getOffsetX());
        int rawOriginY = Math.round(fromAnchor.getY() + fromAnchor.getOffsetY());
        int rawTargetX = Math.round(toAnchor.getX() + toAnchor.getOffsetX());
        int rawTargetY = Math.round(toAnchor.getY() + toAnchor.getOffsetY());
        float sign = rawTargetY >= rawOriginY ? 1.0f : -1.0f;
        int originX = rawOriginX;
        int originY = Math.round(rawOriginY + sign * 7.0f);
        int targetX = rawTargetX;
        int targetY = Math.round(rawTargetY - sign * 7.0f);
        int firstVerticalY = Math.round(originY + sign * 18.0f);
        int targetVerticalY = Math.round(targetY - sign * 18.0f);
        int cut = 12;
        int cutSign = targetX >= originX ? 1 : -1;
        int horizontalStartX = originX + cutSign * cut;
        int horizontalEndX = targetX - cutSign * cut;
        int horizontalY = firstVerticalY + Math.round(sign * cut);

        drawRing(g, originX, originY, color(alpha * 0.9f, 8, 20, 28), color);
        drawLine(g, originX, originY, originX, firstVerticalY, color);
        drawLine(g, originX, firstVerticalY, horizontalStartX, horizontalY, color);
        drawLine(g, horizontalStartX, horizontalY, horizontalEndX, horizontalY, color);
        drawLine(g, horizontalEndX, horizontalY, targetX, targetVerticalY, color);
        drawLine(g, targetX, targetVerticalY, targetX, targetY, color);
        drawRing(g, targetX, targetY, color(alpha * 0.9f, 8, 20, 28), color);
    }

    private void drawRing(GuiGraphics g, int cx, int cy, int inner, int outer) {
        g.fill(cx - 4, cy - 2, cx + 5, cy + 3, outer);
        g.fill(cx - 2, cy - 4, cx + 3, cy + 5, outer);
        g.fill(cx - 2, cy - 1, cx + 3, cy + 2, inner);
        g.fill(cx - 1, cy - 2, cx + 2, cy + 3, inner);
        g.fill(cx, cy, cx + 1, cy + 1, outer);
    }

    private ItemStack resolveItemStack(IngredientData item) {
        if (item == null || item.getItemId() == null || item.getItemId().isBlank()) return null;
        String itemId = item.getItemId();
        if (itemId.contains("/")) itemId = itemId.substring(0, itemId.indexOf('/'));
        if (itemId.startsWith("#")) {
            String availableItemId = engine.getBestAvailableItemId(item);
            if (availableItemId != null && !availableItemId.isBlank()) {
                itemId = availableItemId;
            } else {
                if (item.getTagItems().isEmpty()) return null;
                itemId = item.getTagItems().get(0);
            }
        }
        ItemStack cached = itemCache.get(itemId);
        if (cached != null) return cached;
        try {
            Item resolved = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(null);
            if (resolved == null) return null;
            ItemStack stack = new ItemStack(resolved);
            itemCache.put(itemId, stack);
            return stack;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void drawCutPanel(GuiGraphics g, int x, int y, int w, int h, int bg, int border) {
        int c = 8;
        g.fill(x + c, y, x + w - c, y + h, bg);
        g.fill(x, y + c, x + w, y + h - c, bg);
        drawLine(g, x + c, y, x + w - c, y, border);
        drawLine(g, x + w, y + c, x + w, y + h - c, border);
        drawLine(g, x + c, y + h, x + w - c, y + h, border);
        drawLine(g, x, y + c, x, y + h - c, border);
        drawLine(g, x, y + c, x + c, y, border);
        drawLine(g, x + w - c, y, x + w, y + c, border);
        drawLine(g, x + w, y + h - c, x + w - c, y + h, border);
        drawLine(g, x + c, y + h, x, y + h - c, border);
    }

    private void drawRectBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        drawLine(g, x, y, x + w, y, color);
        drawLine(g, x + w, y, x + w, y + h, color);
        drawLine(g, x + w, y + h, x, y + h, color);
        drawLine(g, x, y + h, x, y, color);
    }

    private void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        if (x1 == x2) {
            int min = Math.min(y1, y2);
            int max = Math.max(y1, y2);
            g.fill(x1, min, x1 + 1, max + 1, color);
        } else if (y1 == y2) {
            int min = Math.min(x1, x2);
            int max = Math.max(x1, x2);
            g.fill(min, y1, max + 1, y1 + 1, color);
        } else {
            int dx = Math.abs(x2 - x1);
            int dy = Math.abs(y2 - y1);
            int steps = Math.max(dx, dy);
            if (steps == 0) {
                g.fill(x1, y1, x1 + 1, y1 + 1, color);
                return;
            }
            for (int i = 0; i <= steps; i++) {
                int x = Math.round(x1 + (x2 - x1) * (i / (float) steps));
                int y = Math.round(y1 + (y2 - y1) * (i / (float) steps));
                g.fill(x, y, x + 1, y + 1, color);
            }
        }
    }

    private String trim(Font font, String text, int maxWidth) {
        if (text == null) return "未知";
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }

    private int color(float alpha, int r, int g, int b) {
        int a = (int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
