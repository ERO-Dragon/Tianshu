package com.rheinmetal.tianshu.client.craftinggraph;

import com.rheinmetal.tianshu.client.GuiGeometryBatch;
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
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CraftingGraphRenderer {

    private static final float GRAPH_BASE_Z = 0.0f;
    private static final float GRAPH_EDGE_Z = 260.0f;
    private static final float GRAPH_PICKER_Z = 320.0f;
    private static final float FIXED_OVERLAY_Z = 420.0f;

    private final CraftingGraphEngine engine;
    private final GuiGeometryBatch geometryBatch = new GuiGeometryBatch();
    private final GraphRenderFrame graphFrame = new GraphRenderFrame();
    private final Map<IngredientKey, Integer> availableCountByKeyCache = new HashMap<>();
    private final Map<IngredientKey, Integer> consumedIngredientCache = new HashMap<>();
    private final Map<IngredientData, String> bestAvailableItemIdCache = new HashMap<>();
    private final Map<IngredientData, List<String>> ingredientCandidateCache = new HashMap<>();
    private final Map<String, ItemStack> itemCache = new HashMap<>();
    private final Set<String> missingItemIds = new HashSet<>();
    private String topPanelType;
    private float topPanelProgress;
    private boolean currentTreeFavorited;
    private boolean searchOpen;
    private String searchQuery = "";
    private List<CraftingGraphController.SearchResult> searchResults = List.of();
    private int searchSelection;
    private List<StoredGraphEntry> topPanelEntries = List.of();
    private float topPanelScroll;
    private boolean renderItemsForProfiling = true;
    private boolean renderTextForProfiling = true;
    private boolean renderEdgesForProfiling = true;
    private boolean renderGeometryForProfiling = true;
    private boolean renderShellForProfiling = true;
    private boolean renderFrameForProfiling = true;

    public CraftingGraphRenderer(CraftingGraphEngine engine) {
        this.engine = engine;
    }

    public void render(GuiGraphics g, float partialTick) {
        if (engine == null) return;
        float drawerProgress = engine.renderDrawerProgress(partialTick);
        if (drawerProgress <= 0.003f && !engine.isVisible()) drawCollapsedHandle(g);
        if (drawerProgress <= 0.003f && !engine.isVisible()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float alpha = engine.getAlpha();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int drawerW = drawerWidth(screenW);
        if (drawerW <= 0) return;
        int drawerH = screenH;
        int drawerX = drawerX(drawerW, drawerProgress);
        int drawerY = 0;
        int topPanelOffset = topPanelOffset();
        int contentX = drawerX + CraftingGraphConstants.DRAWER_MARGIN;
        int contentY = drawerY + CraftingGraphConstants.DRAWER_MARGIN + 18 + topPanelOffset;
        engine.getCamera().renderFrame();

        if (renderShellForProfiling) {
            drawDrawer(g, drawerX, drawerY, drawerW, drawerH, alpha, drawerProgress);
            geometryBatch.flush(g, GRAPH_BASE_Z);
            g.flush();
        }
        if (!renderFrameForProfiling) {
            if (renderShellForProfiling) renderFixedOverlay(g, font, drawerX, drawerY, drawerW, drawerH, topPanelOffset, alpha);
            return;
        }
        g.enableScissor(drawerX, drawerY + topPanelOffset, drawerX + drawerW, drawerY + drawerH);

        g.pose().pushPose();
        g.pose().translate(contentX + engine.getCamera().getOffsetX(), contentY + engine.getCamera().getOffsetY(), 0.0f);
        g.pose().scale(engine.getCamera().getZoom(), engine.getCamera().getZoom(), 1.0f);

        GraphRenderFrame frame = graphRenderFrame(contentX, contentY, drawerW, drawerH, topPanelOffset);
        long now = System.currentTimeMillis();
        renderGraphGeometry(g, alpha, now, frame);
        renderGraphItems(g, font, alpha, now, frame);
        g.flush();
        renderGraphText(g, font, alpha, now, frame);
        g.flush();
        renderGraphEdges(g, alpha, frame);
        renderGraphPickers(g, font, alpha, now, frame);
        g.flush();

        g.pose().popPose();
        g.disableScissor();
        g.flush();

        if (renderShellForProfiling) renderFixedOverlay(g, font, drawerX, drawerY, drawerW, drawerH, topPanelOffset, alpha);
    }

    private void renderFixedOverlay(GuiGraphics g, Font font, int drawerX, int drawerY, int drawerW, int drawerH, int topPanelOffset, float alpha) {
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, FIXED_OVERLAY_Z);
        drawDrawerHeader(g, font, drawerX, drawerY, drawerW, alpha);
        drawTopPanel(g, font, drawerX, drawerY, drawerW, alpha);
        drawTopPanelToggleButtons(g, font, drawerX, drawerY, alpha);
        drawCurrentTreeActionButtons(g, drawerX, drawerY + topPanelOffset, drawerW, alpha);
        if (shouldDrawHintText()) drawHintText(g, font, drawerX, drawerY, drawerW, drawerH, alpha);
        g.pose().popPose();
        g.flush();
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

    public void toggleItemRenderingForProfiling() {
        renderItemsForProfiling = !renderItemsForProfiling;
    }

    public void toggleTextRenderingForProfiling() {
        renderTextForProfiling = !renderTextForProfiling;
    }

    public void toggleEdgeRenderingForProfiling() {
        renderEdgesForProfiling = !renderEdgesForProfiling;
    }

    public void toggleGeometryRenderingForProfiling() {
        renderGeometryForProfiling = !renderGeometryForProfiling;
    }

    public void toggleShellRenderingForProfiling() {
        renderShellForProfiling = !renderShellForProfiling;
    }

    public void toggleFrameRenderingForProfiling() {
        renderFrameForProfiling = !renderFrameForProfiling;
    }

    public boolean isRenderingItemsForProfiling() {
        return renderItemsForProfiling;
    }

    public int graphViewportWidth() {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        return Math.max(1, drawerW - CraftingGraphConstants.DRAWER_MARGIN * 2);
    }

    public int graphViewportHeight() {
        Minecraft mc = Minecraft.getInstance();
        int drawerH = mc.getWindow().getGuiScaledHeight();
        return Math.max(1, drawerH - CraftingGraphConstants.DRAWER_MARGIN * 2 - 18 - topPanelOffset());
    }

    public boolean containsScreenPoint(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = 0;
        int drawerH = mc.getWindow().getGuiScaledHeight();
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
        int drawerY = 0;
        int contentY = drawerY + CraftingGraphConstants.DRAWER_MARGIN + 18 + topPanelOffset();
        return engine.getCamera().screenToWorldY((float) mouseY - contentY);
    }

    public void zoomAtScreenPoint(double mouseX, double mouseY, double scrollDelta) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return;
        int drawerX = drawerX(drawerW);
        int contentX = drawerX + CraftingGraphConstants.DRAWER_MARGIN;
        int contentY = CraftingGraphConstants.DRAWER_MARGIN + 18 + topPanelOffset();
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

    public boolean hitItemRenderProfilerButton(double mouseX, double mouseY) {
        return hitProfilerButton(mouseX, mouseY, 0);
    }

    public boolean hitTextRenderProfilerButton(double mouseX, double mouseY) {
        return hitProfilerButton(mouseX, mouseY, 1);
    }

    public boolean hitEdgeRenderProfilerButton(double mouseX, double mouseY) {
        return hitProfilerButton(mouseX, mouseY, 2);
    }

    public boolean hitGeometryRenderProfilerButton(double mouseX, double mouseY) {
        return hitProfilerButton(mouseX, mouseY, 3);
    }

    public boolean hitShellRenderProfilerButton(double mouseX, double mouseY) {
        return hitProfilerButton(mouseX, mouseY, 4);
    }

    public boolean hitFrameRenderProfilerButton(double mouseX, double mouseY) {
        return hitProfilerButton(mouseX, mouseY, 5);
    }

    private boolean hitProfilerButton(double mouseX, double mouseY, int index) {
        Minecraft mc = Minecraft.getInstance();
        int drawerW = drawerWidth(mc.getWindow().getGuiScaledWidth());
        if (drawerW <= 0) return false;
        int drawerX = drawerX(drawerW);
        int drawerY = CraftingGraphConstants.DRAWER_MARGIN + topPanelOffset();
        int x = profilerButtonX(drawerX, drawerW, index);
        int y = currentTreeActionButtonY(drawerY);
        return mouseX >= x && mouseX <= x + 14 && mouseY >= y && mouseY <= y + 14;
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

    private boolean isShiftDown() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        return org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    private boolean shouldDrawHintText() {
        return engine != null && engine.isEmpty() && !searchOpen && topPanelProgress <= 0.0f;
    }

    private void drawCollapsedHandle(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int h = mc.getWindow().getGuiScaledHeight();
        int peek = Math.round(CraftingGraphConstants.DRAWER_CLOSED_PEEK);
        int lineX = Math.max(2, peek - 5);
        int lineY0 = Math.max(14, h / 2 - Math.max(80, h / 5));
        int lineY1 = Math.min(h - 14, h / 2 + Math.max(80, h / 5));
        g.fill(0, 0, peek, h, color(0.62f, 24, 24, 24));
        g.fill(lineX - 1, lineY0, lineX + 2, lineY1, color(0.9f, 34, 34, 34));
        g.fill(lineX, lineY0 + 1, lineX + 1, lineY1 - 1, color(0.78f, 146, 146, 146));
    }

    private void drawHintText(GuiGraphics g, Font font, int drawerX, int drawerY, int drawerW, int drawerH, float alpha) {
        String[] lines = Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> ? new String[]{
                "按Tab打开/关闭合成图谱",
                "右键拖拽图谱",
                "左键点击可查看来源",
                "右键点击可查看用途",
                "按住Shift进入编辑模式",
                "Ctrl+F进入搜索模式"
        } : new String[]{
                "按Tab打开/关闭合成图谱",
                "长按Tab可进入编辑态",
                "长按Tab+鼠标中键可锁定编辑态",
                "右键拖拽图谱",
                "左键点击可查看来源",
                "右键点击可查看用途",
                "按住Shift进入编辑模式",
                "Ctrl+F进入搜索模式"
        };
        int textColor = vanillaMutedText(alpha * 0.86f);
        int x = drawerX + 12;
        int y = drawerY + drawerH - 12 - lines.length * 11;
        g.fill(drawerX + 8, y - 6, drawerX + Math.min(drawerW - 8, 230), drawerY + drawerH - 8, color(alpha * 0.36f, 16, 16, 16));
        drawRectBorder(g, drawerX + 8, y - 6, Math.min(drawerW - 16, 222), lines.length * 11 + 9, vanillaDarkBorder(alpha * 0.72f));
        for (String line : lines) {
            g.drawString(font, line, x, y, textColor, false);
            y += 11;
        }
    }

    private void drawDrawer(GuiGraphics g, int x, int y, int w, int h, float alpha, float drawerProgress) {
        int left = Math.min(-32, x);
        g.fill(left, y, x + w, y + h, vanillaBackground(alpha));
        drawDrawerBorder(g, left, y, x + w, h, alpha);
        float handleAlpha = alpha * Math.max(0.0f, Math.min(1.0f, 1.0f - drawerProgress));
        if (handleAlpha > 0.01f) {
            int handleX = x + w - Math.round(CraftingGraphConstants.DRAWER_CLOSED_PEEK) + 4;
            int lineY0 = y + Math.max(14, h / 2 - Math.max(80, h / 5));
            int lineY1 = y + Math.min(h - 14, h / 2 + Math.max(80, h / 5));
            g.fill(handleX - 1, lineY0, handleX + 2, lineY1, color(handleAlpha * 0.9f, 34, 34, 34));
            g.fill(handleX, lineY0 + 1, handleX + 1, lineY1 - 1, color(handleAlpha * 0.78f, 146, 146, 146));
        }
    }

    private void drawDrawerHeader(GuiGraphics g, Font font, int x, int y, int w, float alpha) {
        int left = Math.min(-32, x);
        g.fill(left, y, x + w, y + 24, vanillaPanelBg(alpha));
        g.drawString(font, "合成图谱", x + 12, y + 9, vanillaText(alpha), false);
    }

    private void drawClearGraphButton(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        if (engine.isEmpty()) return;
        int x = clearGraphButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        int border = color(alpha, 255, 86, 86);
        drawVanillaButton(g, x, y, 14, 14, alpha, false, true);
        drawLine(g, x + 4, y + 4, x + 10, y + 10, border);
        drawLine(g, x + 10, y + 4, x + 4, y + 10, border);
    }

    private void drawCurrentTreeActionButtons(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        if (searchOpen) {
            drawSearchPanel(g, Minecraft.getInstance().font, drawerX, drawerY, drawerW, alpha);
        } else {
            drawSearchEntryButton(g, drawerX, drawerY, drawerW, alpha);
        }
        drawProfilerButtons(g, drawerX, drawerY, drawerW, alpha);
        if (engine.isEmpty()) return;
        drawCurrentFavoriteButton(g, drawerX, drawerY, drawerW, alpha);
        if (currentTreeFavorited) drawDuplicateButton(g, drawerX, drawerY, drawerW, alpha);
        drawClearGraphButton(g, drawerX, drawerY, drawerW, alpha);
    }

    private void drawSearchEntryButton(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        int x = searchEntryButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        int icon = vanillaMutedText(alpha);
        drawVanillaButton(g, x, y, 14, 14, alpha, false, false);
        drawLine(g, x + 5, y + 5, x + 8, y + 5, icon);
        drawLine(g, x + 4, y + 6, x + 4, y + 8, icon);
        drawLine(g, x + 5, y + 9, x + 8, y + 9, icon);
        drawLine(g, x + 9, y + 10, x + 12, y + 13, icon);
    }

    private void drawProfilerButtons(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        drawProfilerButton(g, drawerX, drawerY, drawerW, alpha, 0, "I", renderItemsForProfiling);
        drawProfilerButton(g, drawerX, drawerY, drawerW, alpha, 1, "T", renderTextForProfiling);
        drawProfilerButton(g, drawerX, drawerY, drawerW, alpha, 2, "E", renderEdgesForProfiling);
        drawProfilerButton(g, drawerX, drawerY, drawerW, alpha, 3, "G", renderGeometryForProfiling);
        drawProfilerButton(g, drawerX, drawerY, drawerW, alpha, 4, "S", renderShellForProfiling);
        drawProfilerButton(g, drawerX, drawerY, drawerW, alpha, 5, "F", renderFrameForProfiling);
    }

    private void drawProfilerButton(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha, int index, String label, boolean enabled) {
        int x = profilerButtonX(drawerX, drawerW, index);
        int y = currentTreeActionButtonY(drawerY);
        int icon = enabled ? color(alpha, 112, 232, 126) : color(alpha, 255, 96, 96);
        drawVanillaButton(g, x, y, 14, 14, alpha, enabled, false);
        g.drawString(Minecraft.getInstance().font, label, x + 4, y + 3, icon, false);
    }

    private void drawCurrentFavoriteButton(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        int x = currentFavoriteButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        int icon = currentTreeFavorited ? color(alpha, 255, 226, 104) : vanillaMutedText(alpha);
        drawVanillaButton(g, x, y, 14, 14, alpha, currentTreeFavorited, false);
        drawStar(g, x, y, icon);
        if (currentTreeFavorited) g.fill(x + 5, y + 5, x + 9, y + 9, icon);
    }

    private void drawDuplicateButton(GuiGraphics g, int drawerX, int drawerY, int drawerW, float alpha) {
        int x = duplicateButtonX(drawerX, drawerW);
        int y = currentTreeActionButtonY(drawerY);
        int icon = color(alpha, 255, 226, 104);
        drawVanillaButton(g, x, y, 14, 14, alpha, false, false);
        drawStar(g, x, y, icon);
        drawLine(g, x + 10, y + 2, x + 10, y + 7, icon);
        drawLine(g, x + 8, y + 4, x + 13, y + 4, icon);
    }

    private void drawTopPanelToggleButtons(GuiGraphics g, Font font, int drawerX, int drawerY, float alpha) {
        drawTopPanelToggle(g, font, drawerX, drawerY, 0, "★", "favorites".equals(topPanelType), alpha);
        drawTopPanelToggle(g, font, drawerX, drawerY, 1, "H", "history".equals(topPanelType), alpha);
    }

    private void drawTopPanelToggle(GuiGraphics g, Font font, int drawerX, int drawerY, int index, String text, boolean active, float alpha) {
        int x = topPanelToggleX(drawerX);
        int y = topPanelToggleY(drawerY, index);
        drawVanillaButton(g, x, y, 16, 16, alpha, active, false);
        int textColor = active ? color(alpha, 255, 226, 104) : vanillaMutedText(alpha);
        g.drawString(font, text, x + (16 - font.width(text)) / 2, y + 4, textColor, false);
    }

    private void drawTopPanel(GuiGraphics g, Font font, int drawerX, int drawerY, int drawerW, float alpha) {
        if (topPanelProgress <= 0.0f) return;
        int h = topPanelOffset();
        int y = drawerY + 26 - Math.round((1.0f - topPanelProgress) * topPanelMaxHeight());
        int panelH = Math.max(0, h - 26);
        if (panelH <= 0) return;
        drawVanillaPanel(g, drawerX + 8, y, drawerW - 16, panelH, alpha);
        String title = "history".equals(topPanelType) ? "历史记录" : "收藏列表";
        g.drawString(font, title, drawerX + 22, y + 9, vanillaText(alpha), false);
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
        g.fill(x, y, x + w, y + h - 2, color(alpha * 0.46f, 36, 36, 36));
        drawRectBorder(g, x, y, w, h - 2, vanillaDarkBorder(alpha * 0.82f));
        String name = trimToWidth(font, entry.getDisplayName(), w - 58);
        g.drawString(font, name, x + 5, y + 4, vanillaText(alpha), false);
        String meta = entry.getNodeCount() + " 节点 / " + entry.getEdgeCount() + " 线";
        g.drawString(font, meta, x + 5, y + 15, vanillaMutedText(alpha * 0.82f), false);
    }

    private void drawSearchPanel(GuiGraphics g, Font font, int drawerX, int drawerY, int drawerW, float alpha) {
        int x = searchPanelX(drawerX, drawerW);
        int y = searchPanelY(drawerY);
        int w = searchPanelW(drawerW);
        drawVanillaPanel(g, x, y, w, searchPanelH(), alpha);
        String value = searchQuery.isEmpty() ? "搜索 itemId / 名称" : searchQuery;
        int textColor = searchQuery.isEmpty() ? vanillaMutedText(alpha * 0.7f) : vanillaText(alpha);
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
            int rowBg = i == searchSelection ? color(alpha * 0.5f, 78, 64, 32) : color(alpha * 0.42f, 42, 42, 42);
            int rowBorder = i == searchSelection ? color(alpha, 255, 226, 104) : vanillaLightBorder(alpha * 0.72f);
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
        int badgeBg = usage ? color(alpha * 0.48f, 54, 54, 54) : color(alpha * 0.48f, 46, 46, 46);
        int badgeBorder = usage ? color(alpha, 182, 182, 182) : color(alpha, 154, 154, 154);
        g.fill(x + 5, y + 5, x + 5 + badgeW, y + 17, badgeBg);
        drawRectBorder(g, x + 5, y + 5, badgeW, 12, badgeBorder);
        g.drawString(font, badge, x + 5 + (badgeW - font.width(badge)) / 2, y + 7, badgeBorder, false);
        String title = node.getDisplayName();
        String meta = node.getItemId();
        g.drawString(font, trimToWidth(font, title, w - badgeW - 22), x + 10 + badgeW, y + 4, vanillaText(alpha), false);
        g.drawString(font, trimToWidth(font, meta, w - badgeW - 22), x + 10 + badgeW, y + 15, vanillaMutedText(alpha * 0.82f), false);
    }

    private void drawRootSearchEntry(GuiGraphics g, Font font, CraftingGraphController.SearchResult result, int x, int y, int w, float alpha) {
        g.drawString(font, trimToWidth(font, "+ " + result.getItemId(), w - 98), x + 6, y + 5, vanillaText(alpha), false);
        drawRootTypeButton(g, font, x + w - 86, y + 5, 38, 16, "来源", result.isSourceAvailable(), color(alpha, 154, 154, 154), alpha);
        drawRootTypeButton(g, font, x + w - 44, y + 5, 38, 16, "用途", result.isUsageAvailable(), color(alpha, 182, 182, 182), alpha);
    }

    private void drawRootTypeButton(GuiGraphics g, Font font, int x, int y, int w, int h, String text, boolean enabled, int border, float alpha) {
        int bg = enabled ? color(alpha * 0.46f, 48, 48, 48) : color(alpha * 0.18f, 36, 36, 36);
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

    private int profilerButtonX(int drawerX, int drawerW, int index) {
        return drawerX + drawerW - 120 - index * 18;
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
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(suffix))) + suffix;
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

    private int drawerWidth(int screenW) {
        return Math.min(CraftingGraphConstants.DRAWER_MAX_WIDTH, Math.max(CraftingGraphConstants.DRAWER_MIN_WIDTH, screenW / 3));
    }

    private int drawerX(int drawerW) {
        return drawerX(drawerW, engine.getDrawerProgress());
    }

    private int drawerX(int drawerW, float drawerProgress) {
        float closedX = -drawerW + CraftingGraphConstants.DRAWER_CLOSED_PEEK;
        return Math.round(closedX + (0.0f - closedX) * drawerProgress);
    }

    private GraphViewBounds graphViewBounds(int contentX, int contentY, int drawerW, int drawerH, int topPanelOffset) {
        float margin = 48.0f;
        float viewportW = Math.max(1.0f, drawerW - CraftingGraphConstants.DRAWER_MARGIN * 2.0f);
        float viewportH = Math.max(1.0f, drawerH - CraftingGraphConstants.DRAWER_MARGIN * 2.0f - 18.0f - topPanelOffset);
        float minX = engine.getCamera().screenToWorldX(-contentX - margin);
        float minY = engine.getCamera().screenToWorldY(-contentY - margin);
        float maxX = engine.getCamera().screenToWorldX(viewportW + margin);
        float maxY = engine.getCamera().screenToWorldY(viewportH + margin);
        return new GraphViewBounds(Math.min(minX, maxX), Math.min(minY, maxY), Math.max(minX, maxX), Math.max(minY, maxY));
    }

    private GraphRenderFrame graphRenderFrame(int contentX, int contentY, int drawerW, int drawerH, int topPanelOffset) {
        graphFrame.clear(graphViewBounds(contentX, contentY, drawerW, drawerH, topPanelOffset));
        availableCountByKeyCache.clear();
        consumedIngredientCache.clear();
        bestAvailableItemIdCache.clear();
        for (RecipePanelNode node : engine.getNodes()) {
            boolean visible = nodeVisible(node, graphFrame.bounds);
            boolean pickerVisible = node.isRecipePickerOpen() && recipePickerVisible(node, graphFrame.bounds);
            if (!visible && !pickerVisible) continue;
            UniversalRecipeViewModel model = engine.getViewModel(node);
            VisibleNode visibleNode = graphFrame.visibleNode(node, model);
            if (visible) graphFrame.visibleNodes.add(visibleNode);
            if (pickerVisible) {
                graphFrame.visiblePickers.add(visibleNode);
                graphFrame.pickerOccluders.add(recipePickerOccluder(node));
            }
        }
        for (RecipeGraphEdge edge : engine.getEdges()) {
            RecipePanelNode from = engine.getNode(edge.getFromNode());
            RecipePanelNode to = engine.getNode(edge.getToNode());
            if (from != null && to != null && edgeVisible(edge, graphFrame.bounds)) graphFrame.visibleEdges.add(graphFrame.visibleEdge(edge, from, to));
        }
        return graphFrame;
    }

    private void renderGraphGeometry(GuiGraphics g, float alpha, long now, GraphRenderFrame frame) {
        if (!renderGeometryForProfiling) return;
        geometryBatch.begin();
        if (engine.isEmpty()) drawEmptyCanvasReferenceGeometry(geometryBatch, alpha);
        for (VisibleNode visibleNode : frame.visibleNodes) {
            drawNodeGeometry(geometryBatch, visibleNode, alpha, now, frame);
        }
        if (isShiftDown()) drawDestructiveHintsGeometry(geometryBatch, alpha, frame);
        geometryBatch.flush(g, GRAPH_BASE_Z);
    }

    private void renderGraphEdges(GuiGraphics g, float alpha, GraphRenderFrame frame) {
        if (!renderEdgesForProfiling) return;
        geometryBatch.begin();
        for (VisibleEdge visibleEdge : frame.visibleEdges) {
            drawEdgeGeometry(geometryBatch, visibleEdge.edge, visibleEdge.from, visibleEdge.to, alpha, frame);
        }
        geometryBatch.flush(g, GRAPH_EDGE_Z);
    }

    private void renderGraphItems(GuiGraphics g, Font font, float alpha, long now, GraphRenderFrame frame) {
        if (!renderItemsForProfiling) return;
        for (VisibleNode visibleNode : frame.visibleNodes) {
            drawNodeItems(g, font, visibleNode, alpha, now, frame);
        }
    }

    private void renderGraphText(GuiGraphics g, Font font, float alpha, long now, GraphRenderFrame frame) {
        if (!renderTextForProfiling) return;
        if (engine.isEmpty()) drawEmptyCanvasReferenceText(g, font, alpha);
        for (VisibleNode visibleNode : frame.visibleNodes) {
            drawNodeText(g, font, visibleNode, alpha, frame);
        }
    }

    private void renderGraphPickers(GuiGraphics g, Font font, float alpha, long now, GraphRenderFrame frame) {
        for (VisibleNode visibleNode : frame.visiblePickers) {
            drawRecipePickerOverlay(g, visibleNode.node, font, alpha, now);
        }
    }

    private boolean nodeVisible(RecipePanelNode node, GraphViewBounds bounds) {
        return rectVisible(node.getX(), node.getY(), CraftingGraphConstants.NODE_WIDTH, CraftingGraphConstants.NODE_HEIGHT, bounds);
    }

    private boolean recipePickerVisible(RecipePanelNode node, GraphViewBounds bounds) {
        return rectVisible(engine.recipePickerX(node), engine.recipePickerY(node), CraftingGraphConstants.RECIPE_PICKER_WIDTH, engine.recipePickerHeight(node), bounds);
    }

    private GraphOccluder recipePickerOccluder(RecipePanelNode node) {
        return new GraphOccluder(engine.recipePickerX(node), engine.recipePickerY(node), CraftingGraphConstants.RECIPE_PICKER_WIDTH, engine.recipePickerHeight(node));
    }

    private boolean edgeVisible(RecipeGraphEdge edge, GraphViewBounds bounds) {
        GraphAnchorData from = edge.getFromAnchor();
        GraphAnchorData to = edge.getToAnchor();
        float x1 = from.getX() + from.getOffsetX();
        float y1 = from.getY() + from.getOffsetY();
        float x2 = to.getX() + to.getOffsetX();
        float y2 = to.getY() + to.getOffsetY();
        float left = Math.min(x1, x2) - 32.0f;
        float top = Math.min(y1, y2) - 32.0f;
        float right = Math.max(x1, x2) + 32.0f;
        float bottom = Math.max(y1, y2) + 32.0f;
        return right >= bounds.minX && left <= bounds.maxX && bottom >= bounds.minY && top <= bounds.maxY;
    }

    private boolean rectVisible(float x, float y, float w, float h, GraphViewBounds bounds) {
        return x + w >= bounds.minX && x <= bounds.maxX && y + h >= bounds.minY && y <= bounds.maxY;
    }

    private boolean slotOccluded(GraphRenderFrame frame, RecipePanelNode node, SlotViewData slot) {
        return frame.occludes(
                node.getX() + CraftingGraphConstants.NODE_CONTENT_X + slot.getX(),
                node.getY() + CraftingGraphConstants.NODE_CONTENT_Y + slot.getY(),
                18.0f,
                18.0f
        );
    }

    private boolean pointOccluded(GraphRenderFrame frame, float x, float y, float radius) {
        return frame.occludes(x - radius, y - radius, radius * 2.0f, radius * 2.0f);
    }

    private void drawNodeGeometry(GuiGeometryBatch batch, VisibleNode visibleNode, float alpha, long now, GraphRenderFrame frame) {
        RecipePanelNode node = visibleNode.node;
        int border = node.isHighlighted(now) ? color(alpha, 255, 210, 80) : vanillaLightBorder(alpha);
        int x = (int) node.getX();
        int y = (int) node.getY();
        int w = (int) CraftingGraphConstants.NODE_WIDTH;
        int h = (int) CraftingGraphConstants.NODE_HEIGHT;
        drawAdvancementNodePanel(batch, x, y, w, h, alpha, border);
        drawRecipeCategoryList(batch, node, x, y, h, alpha);
        drawRecipePickerButton(batch, node, alpha);
        drawRecipePagerGeometry(batch, node, x, y, w, h, alpha);
        int ox = x + Math.round(CraftingGraphConstants.NODE_CONTENT_X);
        int oy = y + Math.round(CraftingGraphConstants.NODE_CONTENT_Y);
        for (SlotViewData slot : visibleNode.model.getSlots()) {
            if (slotOccluded(frame, visibleNode.node, slot)) continue;
            drawSlotGeometry(batch, node, slot, ox, oy, alpha, isSlotSatisfiedForDisplay(slot));
        }
    }

    private void drawRecipeCategoryList(GuiGeometryBatch batch, RecipePanelNode node, int x, int y, int h, float alpha) {
        int listX = x + 7;
        int listY = y + 21;
        int listW = Math.round(CraftingGraphConstants.NODE_CATEGORY_WIDTH);
        int listH = h - 42;
        batch.fill(listX, listY, listX + listW, listY + listH, color(alpha * 0.36f, 22, 22, 22));
        drawRectBorder(batch, listX, listY, listW, listH, vanillaDarkBorder(alpha * 0.82f));
        batch.fill(listX + 6, listY + 4, listX + 11, listY + 6, vanillaMutedText(alpha * 0.86f));
        batch.fill(listX + 5, listY + 6, listX + 12, listY + 8, vanillaMutedText(alpha * 0.86f));
        batch.fill(listX + 5, listY + listH - 8, listX + 12, listY + listH - 6, vanillaMutedText(alpha * 0.86f));
        batch.fill(listX + 6, listY + listH - 6, listX + 11, listY + listH - 4, vanillaMutedText(alpha * 0.86f));
        List<String> categories = recipeCategories(node);
        int selected = selectedRecipeCategoryIndex(node, categories);
        int startY = listY + 12;
        int max = Math.min(categories.size(), Math.max(1, (listH - 24) / 18));
        for (int i = 0; i < max; i++) {
            int entryY = startY + i * 18;
            int bg = i == selected ? color(alpha * 0.56f, 78, 64, 32) : color(alpha * 0.42f, 42, 42, 42);
            int border = i == selected ? color(alpha, 255, 210, 80) : vanillaDarkBorder(alpha * 0.74f);
            batch.fill(listX + 2, entryY, listX + listW - 2, entryY + 16, bg);
            drawRectBorder(batch, listX + 2, entryY, listW - 4, 16, border);
        }
    }

    private void drawRecipeCategoryText(GuiGraphics g, Font font, RecipePanelNode node, int x, int y, int h, float alpha, GraphRenderFrame frame) {
        List<String> categories = recipeCategories(node);
        int listY = y + 21;
        int listH = h - 42;
        int max = Math.min(categories.size(), Math.max(1, (listH - 24) / 18));
        for (int i = 0; i < max; i++) {
            ItemStack stack = categoryIconStack(node, categories.get(i));
            int iconX = x + 10;
            int iconY = listY + 12 + i * 18;
            if (!stack.isEmpty() && !frame.occludes(iconX, iconY, 16.0f, 16.0f)) g.renderItem(stack, iconX, iconY);
        }
    }

    private List<String> recipeCategories(RecipePanelNode node) {
        return node != null ? node.recipeCategories() : List.of();
    }

    private int selectedRecipeCategoryIndex(RecipePanelNode node, List<String> categories) {
        if (node == null || categories.isEmpty()) return -1;
        RecipeData recipe = node.getSelectedRecipe();
        String type = recipe != null ? recipe.getRecipeType() : null;
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).equals(type)) return i;
        }
        return -1;
    }

    private ItemStack categoryIconStack(RecipePanelNode node, String recipeType) {
        Item item = categoryIconItem(recipeType);
        if (item != Items.AIR) return new ItemStack(item);
        RecipeData recipe = firstRecipeOfCategory(node, recipeType);
        IngredientData result = recipe != null ? recipe.getResult() : null;
        ItemStack stack = resolveDisplayItemStack(result, System.currentTimeMillis());
        return stack != null ? stack : ItemStack.EMPTY;
    }

    private Item categoryIconItem(String recipeType) {
        String type = recipeType != null ? recipeType.toLowerCase() : "";
        if (type.contains("crafting")) return Items.CRAFTING_TABLE;
        if (type.contains("smoking")) return Items.SMOKER;
        if (type.contains("blasting")) return Items.BLAST_FURNACE;
        if (type.contains("campfire")) return Items.CAMPFIRE;
        if (type.contains("smelting")) return Items.FURNACE;
        if (type.contains("stonecutting")) return Items.STONECUTTER;
        if (type.contains("smithing")) return Items.SMITHING_TABLE;
        if (type.contains("brewing")) return Items.BREWING_STAND;
        return Items.AIR;
    }

    private RecipeData firstRecipeOfCategory(RecipePanelNode node, String recipeType) {
        if (node == null) return null;
        for (RecipeData recipe : node.getRecipes()) {
            if (recipe != null && recipeType != null && recipeType.equals(recipe.getRecipeType())) return recipe;
        }
        return null;
    }

    private void drawNodeItems(GuiGraphics g, Font font, VisibleNode visibleNode, float alpha, long now, GraphRenderFrame frame) {
        RecipePanelNode node = visibleNode.node;
        int ox = (int) node.getX() + Math.round(CraftingGraphConstants.NODE_CONTENT_X);
        int oy = (int) node.getY() + Math.round(CraftingGraphConstants.NODE_CONTENT_Y);
        for (SlotViewData slot : visibleNode.model.getSlots()) {
            IngredientData item = slot.getItem();
            ItemStack stack = resolveDisplayItemStack(item, now);
            if (stack == null) continue;
            int x = ox + (int) slot.getX();
            int y = oy + (int) slot.getY();
            if (slotOccluded(frame, visibleNode.node, slot)) continue;
            g.renderItem(stack, x, y);
        }
    }

    private void drawNodeText(GuiGraphics g, Font font, VisibleNode visibleNode, float alpha, GraphRenderFrame frame) {
        RecipePanelNode node = visibleNode.node;
        int text = vanillaText(alpha);
        int muted = vanillaMutedText(alpha * 0.78f);
        int x = (int) node.getX();
        int y = (int) node.getY();
        int w = (int) CraftingGraphConstants.NODE_WIDTH;
        int h = (int) CraftingGraphConstants.NODE_HEIGHT;
        g.drawString(font, trim(font, nodeTitle(visibleNode), w - 56), x + 9, y + 8, text, false);
        String typeText = node.getNodeType() == RecipePanelNodeType.USAGE ? "用途" : "来源";
        g.drawString(font, typeText, x + w - 29, y + 8, muted, false);
        drawRecipeCategoryText(g, font, node, x, y, h, alpha, frame);
        drawRecipePagerText(g, font, node, x, y, w, h, alpha);
    }

    private String nodeTitle(VisibleNode visibleNode) {
        if (visibleNode == null || visibleNode.node == null) return "未知";
        RecipePanelNode node = visibleNode.node;
        IngredientData result = selectedResult(node);
        if (result != null && result.getDisplayName() != null && !result.getDisplayName().isBlank()) return result.getDisplayName();
        return node.getDisplayName();
    }

    private IngredientData selectedResult(RecipePanelNode node) {
        if (node == null || node.getRecipes().isEmpty()) return null;
        int index = Math.max(0, Math.min(node.getSelectedRecipeIndex(), node.getRecipes().size() - 1));
        RecipeData recipe = node.getRecipes().get(index);
        return recipe != null ? recipe.getResult() : null;
    }

    private void drawEmptyCanvasReferenceGeometry(GuiGeometryBatch batch, float alpha) {
        int x = 42;
        int y = 42;
        int w = 176;
        int h = 92;
        drawAdvancementNodePanel(batch, x, y, w, h, alpha * 0.82f, vanillaLightBorder(alpha * 0.78f));
        int cx = x + w / 2;
        int cy = y + h - 22;
        drawLine(batch, cx - 28, cy, cx + 28, cy, vanillaDarkBorder(alpha));
        drawLine(batch, cx, cy - 16, cx, cy + 16, vanillaDarkBorder(alpha));
        batch.fill(cx - 2, cy - 2, cx + 3, cy + 3, color(alpha, 178, 178, 178));
    }

    private void drawEmptyCanvasReferenceText(GuiGraphics g, Font font, float alpha) {
        int x = 42;
        int y = 42;
        g.drawString(font, "空白图谱画布", x + 12, y + 12, vanillaText(alpha * 0.9f), false);
        g.drawString(font, "拖拽或滚轮缩放时", x + 12, y + 34, vanillaMutedText(alpha * 0.86f), false);
        g.drawString(font, "此参照卡片会随相机移动", x + 12, y + 46, vanillaMutedText(alpha * 0.86f), false);
    }

    private void drawDestructiveHintsGeometry(GuiGeometryBatch batch, float alpha, GraphRenderFrame frame) {
        int color = color(alpha, 255, 80, 80);
        for (VisibleEdge visibleEdge : frame.visibleEdges) {
            EdgeHitResult hit = edgeMidpoint(visibleEdge.edge);
            drawScissor(batch, Math.round(hit.getX()), Math.round(hit.getY()), color);
        }
        for (VisibleNode visibleNode : frame.visibleNodes) {
            RecipePanelNode node = visibleNode.node;
            if (node.getParentUuid() == null) continue;
            int x = Math.round(node.getX() + CraftingGraphConstants.NODE_WIDTH - 12.0f);
            int y = Math.round(node.getY() + 8.0f);
            drawDeleteX(batch, x, y, color);
        }
    }

    private void drawScissor(GuiGeometryBatch batch, int x, int y, int color) {
        drawPixelLine(batch, x - 5, y - 3, x + 5, y + 3, color);
        drawPixelLine(batch, x - 5, y + 3, x + 5, y - 3, color);
        batch.fill(x - 7, y - 5, x - 3, y - 1, color);
        batch.fill(x - 7, y + 1, x - 3, y + 5, color);
    }

    private void drawDeleteX(GuiGeometryBatch batch, int x, int y, int color) {
        batch.fill(x - 7, y - 7, x + 7, y + 7, color(0.44f, 80, 8, 8));
        drawRectBorder(batch, x - 7, y - 7, 14, 14, color);
        drawPixelLine(batch, x - 4, y - 4, x + 4, y + 4, color);
        drawPixelLine(batch, x + 4, y - 4, x - 4, y + 4, color);
    }

    private EdgeHitResult edgeMidpoint(RecipeGraphEdge edge) {
        GraphAnchorData from = edge.getFromAnchor();
        GraphAnchorData to = edge.getToAnchor();
        int x1 = Math.round(from.getX() + from.getOffsetX());
        int y1 = Math.round(from.getY() + from.getOffsetY());
        int x2 = Math.round(to.getX() + to.getOffsetX());
        int y2 = Math.round(to.getY() + to.getOffsetY());
        int midY = Math.round((y1 + y2) * 0.5f);
        return new EdgeHitResult(edge, (x1 + x2) * 0.5f, midY);
    }

    private void drawRecipePickerButton(GuiGeometryBatch batch, RecipePanelNode node, float alpha) {
        if (node.getRecipes().size() <= 1) return;
        int x = Math.round(engine.recipePickerButtonX(node));
        int y = Math.round(engine.recipePickerButtonY(node));
        int bg = node.isRecipePickerOpen() ? color(alpha * 0.72f, 72, 72, 72) : color(alpha * 0.5f, 48, 48, 48);
        int border = node.canSwitchRecipe() ? vanillaLightBorder(alpha) : vanillaDarkBorder(alpha * 0.9f);
        batch.fill(x, y, x + 14, y + 12, bg);
        drawRectBorder(batch, x, y, 14, 12, border);
        batch.fill(x + 3, y + 3, x + 11, y + 5, vanillaMutedText(alpha));
        batch.fill(x + 3, y + 7, x + 9, y + 9, vanillaMutedText(alpha));
    }

    private void drawRecipePickerOverlay(GuiGraphics g, RecipePanelNode node, Font font, float alpha, long now) {
        RecipePickerLayout layout = recipePickerLayout(node);
        geometryBatch.begin();
        drawRecipePickerGeometry(geometryBatch, node, layout, alpha, now);
        geometryBatch.flush(g, GRAPH_PICKER_Z);
        drawRecipePickerItems(g, node, layout, now);
    }

    private void drawRecipePickerGeometry(GuiGeometryBatch batch, RecipePanelNode node, RecipePickerLayout layout, float alpha, long now) {
        int bg = vanillaPanelBg(alpha);
        int border = node.canSwitchRecipe() ? vanillaLightBorder(alpha) : vanillaDarkBorder(alpha * 0.9f);
        drawVanillaPanel(batch, layout.x, layout.y, layout.w, layout.h, bg, border);
        int selected = node.getSelectedRecipeIndex();
        for (RecipePickerCell cell : layout.cells) {
            drawRecipePickerEntryGeometry(batch, node, cell.recipeIndex, selected, cell.x, cell.y, alpha);
        }
        if (layout.scrollable) {
            int trackX = layout.x + layout.w - 5;
            int trackH = layout.h - 10;
            float ratio = node.getRecipePickerScroll() / engine.maxRecipePickerScroll(node);
            int thumbY = layout.y + 5 + Math.round((trackH - 14) * ratio);
            batch.fill(trackX, layout.y + 5, trackX + 2, layout.y + layout.h - 5, color(alpha * 0.36f, 20, 20, 20));
            batch.fill(trackX - 1, thumbY, trackX + 3, thumbY + 14, vanillaLightBorder(alpha));
        }
    }

    private void drawRecipePickerItems(GuiGraphics g, RecipePanelNode node, RecipePickerLayout layout, long now) {
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, GRAPH_PICKER_Z + 20.0f);
        for (RecipePickerCell cell : layout.cells) {
            IngredientData icon = recipeIcon(node, node.getRecipes().get(cell.recipeIndex), cell.recipeIndex, now);
            ItemStack stack = resolveDisplayItemStack(icon, now);
            if (stack != null) g.renderItem(stack, cell.x + 2, cell.y + 2);
        }
        g.pose().popPose();
        g.flush();
    }

    private void drawRecipePickerEntryGeometry(GuiGeometryBatch batch, RecipePanelNode node, int index, int selected, int x, int y, float alpha) {
        int border = index == selected ? color(alpha, 255, 210, 80) : node.canSwitchRecipe() ? vanillaLightBorder(alpha * 0.8f) : vanillaDarkBorder(alpha * 0.7f);
        int bg = index == selected ? color(alpha * 0.56f, 78, 64, 32) : color(alpha * 0.5f, 42, 42, 42);
        batch.fill(x, y, x + 20, y + 20, bg);
        drawRectBorder(batch, x, y, 20, 20, border);
        if (!node.canSwitchRecipe()) batch.fill(x + 1, y + 1, x + 19, y + 19, color(alpha * 0.42f, 12, 12, 12));
    }

    private RecipePickerLayout recipePickerLayout(RecipePanelNode node) {
        int x = Math.round(engine.recipePickerX(node));
        int y = Math.round(engine.recipePickerY(node));
        int w = Math.round(CraftingGraphConstants.RECIPE_PICKER_WIDTH);
        int h = Math.round(engine.recipePickerHeight(node));
        int pad = Math.round(CraftingGraphConstants.RECIPE_PICKER_PADDING);
        int cellSize = Math.round(CraftingGraphConstants.RECIPE_PICKER_CELL);
        boolean scrollable = engine.maxRecipePickerScroll(node) > 0.0f;
        int scrollbarReserve = scrollable ? 7 : 0;
        int contentLeft = x + pad;
        int contentTop = y + pad;
        int contentRight = x + w - pad - scrollbarReserve;
        int contentBottom = y + h - pad;
        int columns = Math.max(1, (contentRight - contentLeft) / cellSize);
        int scroll = Math.round(node.getRecipePickerScroll());
        List<Integer> indices = node.filteredRecipeIndices();
        List<RecipePickerCell> cells = new ArrayList<>();
        if (contentRight - contentLeft >= 20 && contentBottom - contentTop >= 20) {
            int firstRow = Math.max(0, scroll / Math.max(1, cellSize));
            int lastRow = Math.max(firstRow, (scroll + Math.max(0, contentBottom - contentTop - 1)) / Math.max(1, cellSize));
            int first = Math.max(0, firstRow * columns);
            int last = Math.min(indices.size(), (lastRow + 1) * columns);
            for (int visibleIndex = first; visibleIndex < last; visibleIndex++) {
                int col = visibleIndex % columns;
                int row = visibleIndex / columns;
                int cellX = contentLeft + col * cellSize;
                int cellY = contentTop + row * cellSize - scroll;
                if (cellX < contentLeft || cellX + 20 > contentRight || cellY < contentTop || cellY + 20 > contentBottom) continue;
                cells.add(new RecipePickerCell(visibleIndex, indices.get(visibleIndex), cellX, cellY));
            }
        }
        return new RecipePickerLayout(x, y, w, h, contentLeft, contentTop, contentRight, contentBottom, columns, scrollable, cells);
    }

    private IngredientData recipeIcon(RecipePanelNode node, RecipeData recipe, int index, long now) {
        if (recipe == null) return null;
        if (node.getNodeType() == RecipePanelNodeType.USAGE && recipe.getResult() != null) return recipe.getResult();
        if (recipe.getIngredients().isEmpty()) return recipe.getResult();
        int cycle = Math.max(1, recipe.getIngredients().size());
        int offset = (int) ((now / 700L + index) % cycle);
        return recipe.getIngredients().get(offset);
    }

    private void drawRecipePagerGeometry(GuiGeometryBatch batch, RecipePanelNode node, int x, int y, int w, int h, float alpha) {
        if (!node.hasMultipleRecipesInSelectedCategory()) return;
        int bg = node.canSwitchRecipe() ? color(alpha * 0.58f, 58, 58, 58) : color(alpha * 0.24f, 24, 24, 24);
        int cy = y + h - 14;
        int leftX = x + w - 42;
        int rightX = x + w - 22;
        batch.fill(leftX - 3, cy - 3, leftX + 12, cy + 10, bg);
        batch.fill(rightX - 3, cy - 3, rightX + 12, cy + 10, bg);
    }

    private void drawRecipePagerText(GuiGraphics g, Font font, RecipePanelNode node, int x, int y, int w, int h, float alpha) {
        if (!node.hasMultipleRecipesInSelectedCategory()) return;
        int active = node.canSwitchRecipe() ? color(alpha, 238, 252, 255) : color(alpha * 0.45f, 92, 104, 112);
        int cy = y + h - 14;
        int leftX = x + w - 42;
        int rightX = x + w - 22;
        g.drawString(font, "<", leftX, cy, active, false);
        g.drawString(font, ">", rightX, cy, active, false);
        List<Integer> indices = node.filteredRecipeIndices();
        int selected = Math.max(0, Math.min(node.getSelectedRecipeIndex(), node.getRecipes().size() - 1));
        int current = indices.indexOf(selected);
        if (current < 0) current = 0;
        String index = (current + 1) + "/" + indices.size();
        g.drawString(font, index, x + w - 78, cy, active, false);
    }

    private void drawSlotGeometry(GuiGeometryBatch batch, RecipePanelNode node, SlotViewData slot, int originX, int originY, float alpha, boolean satisfied) {
        int x = originX + (int) slot.getX();
        int y = originY + (int) slot.getY();
        boolean output = slot.getType() == SlotViewType.OUTPUT;
        int bg = output
                ? color(alpha * 0.78f, 64, 64, 64)
                : satisfied ? color(alpha * 0.78f, 48, 76, 44) : color(alpha * 0.78f, 86, 42, 38);
        int border = output
                ? vanillaLightBorder(alpha)
                : satisfied ? color(alpha, 88, 150, 76) : color(alpha, 176, 82, 74);
        batch.fill(x - 2, y - 2, x + 18, y + 18, color(alpha * 0.62f, 20, 20, 20));
        batch.fill(x - 1, y - 1, x + 17, y + 17, bg);
        drawRectBorder(batch, x - 2, y - 2, 20, 20, border);
        if (isSubjectSlot(node, slot)) drawSubjectBadge(batch, x, y, alpha);
    }

    private boolean isSlotSatisfiedForDisplay(SlotViewData slot) {
        if (slot == null || slot.getType() == SlotViewType.OUTPUT) return true;
        IngredientData item = slot.getItem();
        if (item == null) return false;
        IngredientKey key = IngredientKey.of(item);
        int required = requiredCount(item);
        int consumed = consumedIngredientCache.getOrDefault(key, 0);
        boolean satisfied = getAvailableCount(key, item) >= consumed + required;
        consumedIngredientCache.put(key, consumed + required);
        return satisfied;
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

    private void drawSubjectBadge(GuiGeometryBatch batch, int x, int y, float alpha) {
        int c = color(alpha, 255, 226, 104);
        batch.fill(x + 13, y - 3, x + 18, y + 2, color(alpha * 0.8f, 58, 42, 8));
        batch.fill(x + 15, y - 1, x + 16, y, c);
        drawPixelLine(batch, x + 14, y + 1, x + 17, y - 2, c);
        drawPixelLine(batch, x + 14, y - 2, x + 17, y + 1, c);
    }

    private void drawAdvancementNodePanel(GuiGeometryBatch batch, int x, int y, int w, int h, float alpha, int border) {
        batch.fill(x, y, x + w, y + h, vanillaPanelBg(alpha));
        batch.fill(x + 2, y + 2, x + w - 2, y + h - 2, color(alpha * 0.82f, 28, 28, 28));
        batch.fill(x + 4, y + 4, x + w - 4, y + 18, color(alpha * 0.78f, 50, 50, 50));
        drawRectBorder(batch, x, y, w, h, vanillaDarkBorder(alpha));
        drawRectBorder(batch, x + 1, y + 1, w - 2, h - 2, border);
    }

    private void drawEdgeGeometry(GuiGeometryBatch batch, RecipeGraphEdge edge, RecipePanelNode from, RecipePanelNode to, float alpha, GraphRenderFrame frame) {
        GraphAnchorData fromAnchor = edge.getFromAnchor();
        GraphAnchorData toAnchor = edge.getToAnchor();
        int x1 = Math.round(fromAnchor.getX() + fromAnchor.getOffsetX());
        int y1 = Math.round(fromAnchor.getY() + fromAnchor.getOffsetY());
        int x2 = Math.round(toAnchor.getX() + toAnchor.getOffsetX());
        int y2 = Math.round(toAnchor.getY() + toAnchor.getOffsetY());
        int midY = Math.round((y1 + y2) * 0.5f);
        int base = vanillaLightBorder(alpha * 0.86f);
        int shadow = color(alpha * 0.34f, 0, 0, 0);
        drawClippedOrthogonalEdge(batch, frame, x1 + 1, y1 + 1, x2 + 1, y2 + 1, midY + 1, shadow);
        drawClippedOrthogonalEdge(batch, frame, x1, y1, x2, y2, midY, base);
        drawTerminalArrow(batch, frame, x1, y1, x2, y2, midY, base);
        if (!pointOccluded(frame, x1, y1, 5.0f)) drawAnchorDot(batch, x1, y1, alpha);
    }

    private void drawClippedOrthogonalEdge(GuiGeometryBatch batch, GraphRenderFrame frame, int x1, int y1, int x2, int y2, int midY, int color) {
        drawClippedLine(batch, frame, x1, y1, x1, midY, color);
        drawClippedLine(batch, frame, x1, midY, x2, midY, color);
        drawClippedLine(batch, frame, x2, midY, x2, y2, color);
    }

    private void drawClippedLine(GuiGeometryBatch batch, GraphRenderFrame frame, int x1, int y1, int x2, int y2, int color) {
        if (x1 == x2) {
            drawClippedVerticalLine(batch, frame, x1, y1, y2, color);
        } else if (y1 == y2) {
            drawClippedHorizontalLine(batch, frame, y1, x1, x2, color);
        } else {
            drawLine(batch, x1, y1, x2, y2, color);
        }
    }

    private void drawClippedVerticalLine(GuiGeometryBatch batch, GraphRenderFrame frame, int x, int y1, int y2, int color) {
        int start = Math.min(y1, y2);
        int end = Math.max(y1, y2);
        int cursor = start;
        for (GraphOccluder occluder : frame.pickerOccluders) {
            if (x < occluder.x || x > occluder.x + occluder.w) continue;
            int cutStart = Math.max(start, (int) Math.floor(occluder.y));
            int cutEnd = Math.min(end, (int) Math.ceil(occluder.y + occluder.h));
            if (cutEnd <= cursor || cutStart >= end) continue;
            if (cursor < cutStart) drawLine(batch, x, cursor, x, cutStart, color);
            cursor = Math.max(cursor, cutEnd);
        }
        if (cursor < end) drawLine(batch, x, cursor, x, end, color);
    }

    private void drawClippedHorizontalLine(GuiGeometryBatch batch, GraphRenderFrame frame, int y, int x1, int x2, int color) {
        int start = Math.min(x1, x2);
        int end = Math.max(x1, x2);
        int cursor = start;
        for (GraphOccluder occluder : frame.pickerOccluders) {
            if (y < occluder.y || y > occluder.y + occluder.h) continue;
            int cutStart = Math.max(start, (int) Math.floor(occluder.x));
            int cutEnd = Math.min(end, (int) Math.ceil(occluder.x + occluder.w));
            if (cutEnd <= cursor || cutStart >= end) continue;
            if (cursor < cutStart) drawLine(batch, cursor, y, cutStart, y, color);
            cursor = Math.max(cursor, cutEnd);
        }
        if (cursor < end) drawLine(batch, cursor, y, end, y, color);
    }

    private void drawOrthogonalEdge(GuiGeometryBatch batch, int x1, int y1, int x2, int y2, int midY, int color) {
        drawLine(batch, x1, y1, x1, midY, color);
        drawLine(batch, x1, midY, x2, midY, color);
        drawLine(batch, x2, midY, x2, y2, color);
    }

    private void drawTerminalArrow(GuiGeometryBatch batch, GraphRenderFrame frame, int x1, int y1, int x2, int y2, int midY, int color) {
        if (pointOccluded(frame, x2, y2, 8.0f)) return;
        int directionY = Integer.compare(y2, midY);
        if (directionY == 0) directionY = Integer.compare(y2, y1);
        if (directionY == 0) directionY = 1;
        int baseY = y2 - directionY * 6;
        drawPixelLine(batch, x2, y2, x2 - 4, baseY, color);
        drawPixelLine(batch, x2, y2, x2 + 4, baseY, color);
        drawPixelLine(batch, x2 - 1, y2, x2 - 4, baseY, color);
        drawPixelLine(batch, x2 + 1, y2, x2 + 4, baseY, color);
    }

    private void drawAnchorDot(GuiGeometryBatch batch, int cx, int cy, float alpha) {
        int border = vanillaDarkBorder(alpha);
        int fill = color(alpha, 172, 172, 172);
        batch.fill(cx - 2, cy - 2, cx + 3, cy + 3, border);
        batch.fill(cx - 1, cy - 1, cx + 2, cy + 2, fill);
    }

    private ItemStack resolveDisplayItemStack(IngredientData item, long now) {
        if (item == null) return null;
        List<String> candidates = ingredientCandidateItemIds(item);
        if (!candidates.isEmpty()) {
            int index = candidates.size() == 1 ? 0 : (int) ((Math.max(0L, now) / 1000L) % candidates.size());
            ItemStack stack = resolveItemStack(candidates.get(index));
            return stack != null ? stack : resolveItemStack(item);
        }
        return resolveItemStack(item);
    }

    private List<String> ingredientCandidateItemIds(IngredientData item) {
        if (item == null) return List.of();
        List<String> cached = ingredientCandidateCache.get(item);
        if (cached != null) return cached;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (item.getTagItems() != null) {
            for (String id : item.getTagItems()) addCandidateItemId(ids, id);
        }
        String itemId = item.getItemId();
        if (itemId != null) {
            String[] split = itemId.split("/");
            for (String id : split) addCandidateItemId(ids, id);
        }
        ids.removeIf(id -> id == null || id.isBlank() || id.startsWith("#"));
        List<String> candidates = List.copyOf(ids);
        ingredientCandidateCache.put(item, candidates);
        return candidates;
    }

    private void addCandidateItemId(Set<String> ids, String itemId) {
        if (ids == null || itemId == null) return;
        String normalized = itemId.trim();
        if (!normalized.isBlank()) ids.add(normalized);
    }

    private ItemStack resolveItemStack(IngredientData item) {
        if (item == null) return null;
        List<String> candidates = ingredientCandidateItemIds(item);
        if (!candidates.isEmpty()) return resolveItemStack(candidates.get(0));
        String itemId = item.getItemId();
        if (itemId == null || itemId.isBlank()) return null;
        return resolveItemStack(itemId);
    }

    private ItemStack resolveItemStack(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        String normalized = itemId.trim();
        if (normalized.contains("/")) normalized = normalized.substring(0, normalized.indexOf('/'));
        if (normalized.startsWith("#")) return null;
        ItemStack cached = itemCache.get(normalized);
        if (cached != null) return cached;
        if (missingItemIds.contains(normalized)) return null;
        try {
            Item resolved = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(normalized)).orElse(null);
            if (resolved == null) {
                missingItemIds.add(normalized);
                return null;
            }
            ItemStack stack = new ItemStack(resolved);
            itemCache.put(normalized, stack);
            return stack;
        } catch (Exception ignored) {
            missingItemIds.add(normalized);
            return null;
        }
    }

    private void drawDrawerBorder(GuiGraphics g, int left, int y, int right, int h, float alpha) {
        int dark = vanillaDarkBorder(alpha);
        int light = color(alpha * 0.42f, 90, 90, 90);
        g.fill(left, y, right, y + 1, dark);
        g.fill(left, y + h - 1, right, y + h, dark);
        g.fill(right - 1, y, right, y + h, dark);
        g.fill(left, y + 1, right - 1, y + 2, light);
        g.fill(left, y + h - 2, right - 1, y + h - 1, light);
        g.fill(right - 2, y + 1, right - 1, y + h - 1, light);
    }

    private void drawAdvancementNodePanel(GuiGraphics g, int x, int y, int w, int h, float alpha, int border) {
        g.fill(x, y, x + w, y + h, color(alpha * 0.86f, 38, 38, 38));
        g.fill(x + 2, y + 2, x + w - 2, y + 22, color(alpha * 0.72f, 58, 58, 58));
        g.fill(x + 2, y + 23, x + w - 2, y + h - 2, color(alpha * 0.72f, 26, 26, 26));
        drawRectBorder(g, x, y, w, h, border);
        drawRectBorder(g, x + 1, y + 1, w - 2, h - 2, vanillaDarkBorder(alpha * 0.7f));
    }

    private void drawVanillaPanel(GuiGraphics g, int x, int y, int w, int h, float alpha) {
        g.fill(x, y, x + w, y + h, vanillaPanelBg(alpha));
        drawRectBorder(g, x, y, w, h, vanillaLightBorder(alpha));
        drawRectBorder(g, x + 1, y + 1, w - 2, h - 2, vanillaDarkBorder(alpha * 0.82f));
    }

    private void drawVanillaButton(GuiGraphics g, int x, int y, int w, int h, float alpha, boolean active, boolean danger) {
        int bg = active ? color(alpha * 0.76f, 86, 74, 42) : danger ? color(alpha * 0.68f, 70, 42, 42) : color(alpha * 0.68f, 58, 58, 58);
        g.fill(x, y, x + w, y + h, bg);
        drawRectBorder(g, x, y, w, h, vanillaLightBorder(alpha));
        drawLine(g, x + 1, y + h - 1, x + w - 1, y + h - 1, vanillaDarkBorder(alpha));
        drawLine(g, x + w - 1, y + 1, x + w - 1, y + h - 1, vanillaDarkBorder(alpha));
    }

    private int vanillaBackground(float alpha) {
        return color(alpha * 0.82f, 24, 24, 24);
    }

    private int vanillaPanelBg(float alpha) {
        return color(alpha * 0.86f, 44, 44, 44);
    }

    private int vanillaText(float alpha) {
        return color(alpha, 238, 238, 238);
    }

    private int vanillaMutedText(float alpha) {
        return color(alpha, 178, 178, 178);
    }

    private int vanillaLightBorder(float alpha) {
        return color(alpha, 112, 112, 112);
    }

    private int vanillaDarkBorder(float alpha) {
        return color(alpha, 18, 18, 18);
    }

    private void drawVanillaPanel(GuiGeometryBatch batch, int x, int y, int w, int h, int bg, int border) {
        batch.fill(x, y, x + w, y + h, bg);
        drawRectBorder(batch, x, y, w, h, border);
        drawLine(batch, x + 1, y + 1, x + w - 1, y + 1, color(((border >>> 24) & 0xFF) / 255.0f * 0.35f, 180, 180, 180));
        drawLine(batch, x + 1, y + h - 1, x + w - 1, y + h - 1, vanillaDarkBorder(((border >>> 24) & 0xFF) / 255.0f));
    }

    private void drawRectBorder(GuiGeometryBatch batch, int x, int y, int w, int h, int color) {
        drawLine(batch, x, y, x + w, y, color);
        drawLine(batch, x + w, y, x + w, y + h, color);
        drawLine(batch, x + w, y + h, x, y + h, color);
        drawLine(batch, x, y + h, x, y, color);
    }

    private void drawLine(GuiGeometryBatch batch, int x1, int y1, int x2, int y2, int color) {
        batch.line(x1, y1, x2, y2, 1, color);
    }

    private void drawPixelLine(GuiGeometryBatch batch, int x1, int y1, int x2, int y2, int color) {
        batch.pixelLine(x1, y1, x2, y2, color);
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
            drawLine(g, x1, y1, x2, y1, color);
            drawLine(g, x2, y1, x2, y2, color);
        }
    }

    private int requiredCount(IngredientData item) {
        if (item == null) return 1;
        return Math.max(1, item.getCount());
    }

    private int getAvailableCount(IngredientKey key, IngredientData item) {
        if (item == null) return 0;
        Integer cached = availableCountByKeyCache.get(key);
        if (cached != null) return cached;
        int available = engine.getAvailableCount(item);
        availableCountByKeyCache.put(key, available);
        return available;
    }

    private String getBestAvailableItemId(IngredientData item) {
        if (item == null) return null;
        if (bestAvailableItemIdCache.containsKey(item)) return bestAvailableItemIdCache.get(item);
        String itemId = engine.getBestAvailableItemId(item);
        bestAvailableItemIdCache.put(item, itemId);
        return itemId;
    }

    private static final class GraphRenderFrame {
        private GraphViewBounds bounds;
        private final List<VisibleNode> visibleNodes = new ArrayList<>();
        private final List<VisibleNode> visiblePickers = new ArrayList<>();
        private final List<VisibleEdge> visibleEdges = new ArrayList<>();
        private final List<GraphOccluder> pickerOccluders = new ArrayList<>();
        private final List<VisibleNode> nodePool = new ArrayList<>();
        private final List<VisibleEdge> edgePool = new ArrayList<>();
        private int nodePoolIndex;
        private int edgePoolIndex;

        private void clear(GraphViewBounds bounds) {
            this.bounds = bounds;
            visibleNodes.clear();
            visiblePickers.clear();
            visibleEdges.clear();
            pickerOccluders.clear();
            nodePoolIndex = 0;
            edgePoolIndex = 0;
        }

        private VisibleNode visibleNode(RecipePanelNode node, UniversalRecipeViewModel model) {
            if (nodePoolIndex >= nodePool.size()) nodePool.add(new VisibleNode());
            VisibleNode visibleNode = nodePool.get(nodePoolIndex++);
            visibleNode.node = node;
            visibleNode.model = model;
            return visibleNode;
        }

        private VisibleEdge visibleEdge(RecipeGraphEdge edge, RecipePanelNode from, RecipePanelNode to) {
            if (edgePoolIndex >= edgePool.size()) edgePool.add(new VisibleEdge());
            VisibleEdge visibleEdge = edgePool.get(edgePoolIndex++);
            visibleEdge.edge = edge;
            visibleEdge.from = from;
            visibleEdge.to = to;
            return visibleEdge;
        }

        private boolean occludes(float x, float y, float w, float h) {
            for (GraphOccluder occluder : pickerOccluders) {
                if (occluder.intersects(x, y, w, h)) return true;
            }
            return false;
        }
    }

    private static final class VisibleNode {
        private RecipePanelNode node;
        private UniversalRecipeViewModel model;
    }

    private static final class VisibleEdge {
        private RecipeGraphEdge edge;
        private RecipePanelNode from;
        private RecipePanelNode to;
    }

    private record GraphViewBounds(float minX, float minY, float maxX, float maxY) {
    }

    private record GraphOccluder(float x, float y, float w, float h) {
        private boolean intersects(float rx, float ry, float rw, float rh) {
            return x + w > rx && x < rx + rw && y + h > ry && y < ry + rh;
        }
    }

    private record RecipePickerLayout(int x, int y, int w, int h, int contentLeft, int contentTop, int contentRight, int contentBottom, int columns, boolean scrollable, List<RecipePickerCell> cells) {
    }

    private record RecipePickerCell(int visibleIndex, int recipeIndex, int x, int y) {
    }

    private record IngredientKey(String itemId, String tagKey) {
        private static IngredientKey of(IngredientData item) {
            if (item == null) return new IngredientKey("", "");
            String itemId = normalizeItemId(item.getItemId());
            if (itemId.startsWith("#")) return new IngredientKey("", itemId);
            return new IngredientKey(itemId, "");
        }

        private static String normalizeItemId(String itemId) {
            if (itemId == null) return "";
            String normalized = itemId;
            if (normalized.contains("/")) normalized = normalized.substring(0, normalized.indexOf('/'));
            return normalized;
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
