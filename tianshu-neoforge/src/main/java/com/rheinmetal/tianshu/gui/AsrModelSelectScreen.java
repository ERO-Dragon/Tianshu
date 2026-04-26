package com.rheinmetal.tianshu.gui;

import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.config.NeoForgeConfig;
import com.rheinmetal.tianshu.constant.VramTier;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.platform.NeoForgeNativeLibBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class AsrModelSelectScreen extends Screen {

    private static final Map<String, String> LANG_NAMES = new LinkedHashMap<>();

    static {
        LANG_NAMES.put("zh", "中文");
        LANG_NAMES.put("en", "英语");
        LANG_NAMES.put("ja", "日语");
        LANG_NAMES.put("ko", "韩语");
        LANG_NAMES.put("fr", "法语");
        LANG_NAMES.put("de", "德语");
        LANG_NAMES.put("es", "西班牙语");
        LANG_NAMES.put("ru", "俄语");
    }

    private enum SortMode {
        QUALITY("质量"),
        PERFORMANCE("性能消耗"),
        VALUE("性价比");

        private final String label;

        SortMode(String label) {
            this.label = label;
        }
    }

    private static final String OTHER_LANG_LABEL = "其他";

    private static final int PAGE_LANG = 0;
    private static final int PAGE_MODELS = 1;

    private final Screen parent;
    private final NeoForgeConfig config;
    private final TianshuCoreManager coreManager;
    private final AudioManager audioManager;
    private final NeoForgeNativeLibBridge nativeLibBridge;
    private EditBox proxyEditBox;

    private List<AsrModelInfo> allModels;
    private Map<String, List<AsrModelInfo>> groupedByLang;
    private List<String> langGroupOrder;

    private int currentPage = PAGE_LANG;
    private String selectedLangGroup;
    private SortMode sortMode = SortMode.VALUE;

    private int scrollOffset = 0;
    private double smoothScrollY = 0;
    private static final int SCROLL_PIXELS_PER_TICK = 20;
    private String downloadingModel = null;
    private volatile int downloadProgress = 0;

    private static final int CARD_HEIGHT = 64;
    private static final int CARD_GAP = 4;
    private static final int SCROLLBAR_WIDTH = 6;

    public AsrModelSelectScreen(Screen parent, NeoForgeConfig config, TianshuCoreManager coreManager,
                                AudioManager audioManager, NeoForgeNativeLibBridge nativeLibBridge) {
        super(Component.literal("ASR 模型选择"));
        this.parent = parent;
        this.config = config;
        this.coreManager = coreManager;
        this.audioManager = audioManager;
        this.nativeLibBridge = nativeLibBridge;
    }

    @Override
    public void init() {
        super.init();
        loadAndGroupModels();
        rebuildPage();
    }

    private void loadAndGroupModels() {
        allModels = AsrModelManager.getAllModels();
        groupedByLang = new LinkedHashMap<>();
        langGroupOrder = new ArrayList<>();

        for (AsrModelInfo info : allModels) {
            List<String> langs = info.lang;
            if (langs == null || langs.isEmpty()) {
                langs = Collections.singletonList(OTHER_LANG_LABEL);
            }
            for (String lang : langs) {
                String groupKey = LANG_NAMES.getOrDefault(lang, OTHER_LANG_LABEL);
                if (!groupedByLang.containsKey(groupKey)) {
                    groupedByLang.put(groupKey, new ArrayList<>());
                    langGroupOrder.add(groupKey);
                }
                if (!groupedByLang.get(groupKey).contains(info)) {
                    groupedByLang.get(groupKey).add(info);
                }
            }
        }

        if (!langGroupOrder.contains(OTHER_LANG_LABEL) && groupedByLang.containsKey(OTHER_LANG_LABEL)) {
            langGroupOrder.add(OTHER_LANG_LABEL);
        }

        String zhGroup = "中文";
        if (langGroupOrder.contains(zhGroup)) {
            langGroupOrder.remove(zhGroup);
            langGroupOrder.add(0, zhGroup);
        }
        String enGroup = "英语";
        if (langGroupOrder.contains(enGroup)) {
            langGroupOrder.remove(enGroup);
            langGroupOrder.add(langGroupOrder.isEmpty() ? 0 : 1, enGroup);
        }
    }

    private List<AsrModelInfo> getCurrentModels() {
        if (selectedLangGroup == null) return Collections.emptyList();
        List<AsrModelInfo> models = new ArrayList<>(groupedByLang.getOrDefault(selectedLangGroup, Collections.emptyList()));
        models.sort(getComparatorForSortMode());
        return models;
    }

    private Comparator<AsrModelInfo> getComparatorForSortMode() {
        return switch (sortMode) {
            case QUALITY -> Comparator
                    .comparingInt(AsrModelInfo::getQualityScore).reversed()
                    .thenComparingInt(AsrModelInfo::getPerformanceScore)
                    .thenComparing(AsrModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            case PERFORMANCE -> Comparator
                    .comparingInt(AsrModelInfo::getPerformanceScore)
                    .thenComparingInt(AsrModelInfo::getQualityScore).reversed()
                    .thenComparing(AsrModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            case VALUE -> Comparator
                    .comparingInt(AsrModelInfo::getValueScore).reversed()
                    .thenComparingInt(AsrModelInfo::getQualityScore).reversed()
                    .thenComparingInt(AsrModelInfo::getPerformanceScore)
                    .thenComparing(AsrModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
        };
    }

    private void navigateTo(int page) {
        currentPage = page;
        scrollOffset = 0;
        smoothScrollY = 0;
        rebuildPage();
    }

    private void rebuildPage() {
        clearWidgets();

        switch (currentPage) {
            case PAGE_LANG -> buildLangPage();
            case PAGE_MODELS -> buildModelsPage();
        }

        if (currentPage > PAGE_LANG) {
            this.addRenderableWidget(TianshuGUI.BrightButton.create(Component.literal("< 返回"), b -> {
                navigateTo(currentPage - 1);
            }).pos(16, this.height - 30).size(80, 20).build());
        }

        this.addRenderableWidget(TianshuGUI.BrightButton.create(Component.literal("关闭"), b -> {
            Minecraft.getInstance().setScreen(parent);
        }).pos(this.width - 96, this.height - 30).size(80, 20).build());
    }

    private void buildLangPage() {
        int listWidth = this.width / 3;
        int startX = (this.width - listWidth) / 2;
        int startY = 50;
        int itemHeight = 28;
        int itemGap = 4;

        for (int i = 0; i < langGroupOrder.size(); i++) {
            String group = langGroupOrder.get(i);
            int cardY = startY + i * (itemHeight + itemGap);
            int modelCount = groupedByLang.getOrDefault(group, Collections.emptyList()).size();

            this.addRenderableWidget(new TianshuGUI.BrightButton(startX, cardY, listWidth, itemHeight,
                    Component.literal(group + " (" + modelCount + ")"), b -> {
                selectedLangGroup = group;
                navigateTo(PAGE_MODELS);
            }) {
                @Override
                public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
                    int x = getX(), y = getY(), w = getWidth(), h = getHeight();
                    boolean hovered = this.isHovered();
                    int bgCol = hovered ? 0xFF3A6BD5 : 0xFF2A4A6A;
                    int borderCol = hovered ? 0xFF5AACFF : 0xFF4A7AAA;

                    g.fill(x, y, x + w, y + h, bgCol);
                    g.fill(x, y, x + w, y + 1, borderCol);
                    g.fill(x, y + h - 1, x + w, y + h, borderCol);
                    g.fill(x, y, x + 1, y + h, borderCol);
                    g.fill(x + w - 1, y, x + w, y + h, borderCol);

                    String text = getMessage().getString();
                    int tw = Minecraft.getInstance().font.width(text);
                    g.drawString(Minecraft.getInstance().font, getMessage(), x + (w - tw) / 2, y + (h - 8) / 2, 0xFFFFFFFF);
                }
            });
        }
    }

    private void buildModelsPage() {
        int listWidth = this.width / 3;
        int cardAreaX = (this.width - listWidth) / 2;
        int cardStartY = 44;

        int sortBtnWidth = 50;
        int sortBtnGap = 3;
        int scrollbarSpace = SCROLLBAR_WIDTH + 2;
        int effectiveListWidth = listWidth - scrollbarSpace;
        String sortHintText = "当前排序: ";
        int sortHintWidth = this.font.width(sortHintText) + 2;
        int totalSortWidth = sortHintWidth + sortBtnWidth * 3 + sortBtnGap * 2;
        int sortStartX = cardAreaX + effectiveListWidth - totalSortWidth;
        int sortRowY = cardStartY;
        for (int i = 0; i < SortMode.values().length; i++) {
            SortMode mode = SortMode.values()[i];
            int btnX = sortStartX + sortHintWidth + i * (sortBtnWidth + sortBtnGap);
            TianshuGUI.BrightButton btn = TianshuGUI.BrightButton.create(Component.literal(mode.label), b -> {
                sortMode = mode;
                scrollOffset = 0;
                smoothScrollY = 0;
                rebuildPage();
            }).pos(btnX, sortRowY).size(sortBtnWidth, 14).build();
            btn.active = sortMode != mode;
            this.addRenderableWidget(btn);
        }
        cardStartY += 22;

        List<AsrModelInfo> models = getCurrentModels();
        boolean hasGithubModel = models.stream().anyMatch(m -> m.downloadUrl != null && !m.downloadUrl.isBlank());

        if (hasGithubModel) {
            int proxyFieldWidth = Math.min(200, listWidth - 80);
            proxyEditBox = new EditBox(Minecraft.getInstance().font, cardAreaX + 60, cardStartY, proxyFieldWidth, 16, Component.literal("proxy"));
            proxyEditBox.setMaxLength(256);
            proxyEditBox.setValue("https://gh-proxy.org/");
            proxyEditBox.setHint(Component.literal("GitHub 代理地址"));
            this.addRenderableWidget(proxyEditBox);
            cardStartY += 22;
        } else {
            proxyEditBox = null;
        }

        int cardAreaHeight = this.height - 90 - (cardStartY - 44);
        int visibleCount = Math.max(1, cardAreaHeight / (CARD_HEIGHT + CARD_GAP));
        int maxScroll = Math.max(0, models.size() - visibleCount);
        int maxScrollPixels = maxScroll * (CARD_HEIGHT + CARD_GAP);

        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        double partialOffset = smoothScrollY % (CARD_HEIGHT + CARD_GAP);

        for (int i = 0; i < visibleCount && (i + scrollOffset) < models.size(); i++) {
            int idx = i + scrollOffset;
            AsrModelInfo info = models.get(idx);
            int cardY = cardStartY + i * (CARD_HEIGHT + CARD_GAP) - (int) partialOffset;

            boolean isDownloaded = isModelDownloaded(info);
            boolean hasContent = hasModelContent(info);
            boolean isDownloading = info.name != null && info.name.equals(downloadingModel);

            int btnW = 40;
            int btnH = 14;
            int btnGap = 3;
            int btnRowX = cardAreaX + effectiveListWidth - btnW - 6;
            int btnRowY = cardY + CARD_HEIGHT - btnH - 3;

            if (isDownloading) {
                TianshuGUI.BrightButton cancelBtn = TianshuGUI.BrightButton.create(
                        Component.literal("取消"), b -> coreManager.cancelDownload()
                ).pos(btnRowX, btnRowY).size(btnW, btnH).build();
                this.addRenderableWidget(cancelBtn);

                int pauseBtnX = btnRowX - btnW - btnGap;
                String pauseLabel = coreManager.isDownloadPaused() ? "继续" : "暂停";
                TianshuGUI.BrightButton pauseBtn = TianshuGUI.BrightButton.create(
                        Component.literal(pauseLabel), b -> {
                            if (coreManager.isDownloadPaused()) {
                                coreManager.resumeDownload();
                            } else {
                                coreManager.pauseDownload();
                            }
                            rebuildPage();
                        }
                ).pos(pauseBtnX, btnRowY).size(btnW, btnH).build();
                this.addRenderableWidget(pauseBtn);
            } else {
                TianshuGUI.BrightButton downloadBtn = TianshuGUI.BrightButton.create(
                        Component.literal(isDownloaded ? "已下载" : "下载"),
                        b -> {
                            if (!isDownloaded) {
                                startDownload(info);
                            }
                        }
                ).pos(btnRowX, btnRowY).size(btnW, btnH).build();
                downloadBtn.active = !isDownloaded;
                this.addRenderableWidget(downloadBtn);

                int deleteBtnX = btnRowX - btnW - btnGap;
                TianshuGUI.BrightButton deleteBtn = TianshuGUI.BrightButton.create(
                        Component.literal("删除"), b -> deleteAsrModel(info)
                ).pos(deleteBtnX, btnRowY).size(btnW, btnH).build();
                deleteBtn.active = hasContent;
                this.addRenderableWidget(deleteBtn);

                int selectBtnX = deleteBtnX - btnW - btnGap;
                TianshuGUI.BrightButton selectBtn = TianshuGUI.BrightButton.create(
                        Component.literal("选择"), b -> selectAsrModel(info)
                ).pos(selectBtnX, btnRowY).size(btnW, btnH).build();
                selectBtn.active = isDownloaded;
                this.addRenderableWidget(selectBtn);
            }
        }
    }

    private boolean isModelDownloaded(AsrModelInfo info) {
        Path baseDir = config.getAsrBasePath();
        return AsrModelManager.isModelDownloaded(info, baseDir);
    }

    private boolean hasModelContent(AsrModelInfo info) {
        return coreManager.hasAsrModelContent(info);
    }

    private void startDownload(AsrModelInfo info) {
        downloadingModel = info.name;
        downloadProgress = 0;
        rebuildPage();

        String proxyUrl = (proxyEditBox != null) ? proxyEditBox.getValue() : null;
        coreManager.downloadAsrModel(info, proxyUrl, new TianshuCoreManager.DownloadProgressCallback() {
            @Override
            public void onProgress(String label, int percent) {
                downloadProgress = percent;
                Minecraft.getInstance().execute(() -> rebuildPage());
            }

            @Override
            public void onComplete() {
                Minecraft.getInstance().execute(() -> {
                    downloadingModel = null;
                    downloadProgress = 0;
                    rebuildPage();
                });
            }

            @Override
            public void onError(String message) {
                Minecraft.getInstance().execute(() -> {
                    downloadingModel = null;
                    downloadProgress = 0;
                    rebuildPage();
                });
            }
        });
    }

    private void deleteAsrModel(AsrModelInfo info) {
        coreManager.deleteAsrModel(info);
        scrollOffset = 0;
        smoothScrollY = 0;
        rebuildPage();
    }

    private void selectAsrModel(AsrModelInfo info) {
        config.setVramTier(VramTier.CUSTOM);
        config.setCustomAsrName(info.name);
        config.save();
        Minecraft.getInstance().setScreen(new TianshuGUI(coreManager, config, audioManager, nativeLibBridge));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalScroll, double verticalScroll) {
        if (currentPage == PAGE_MODELS) {
            List<AsrModelInfo> models = getCurrentModels();
            int cardAreaHeight = this.height - 112;
            int visibleCount = Math.max(1, cardAreaHeight / (CARD_HEIGHT + CARD_GAP));
            int maxScroll = Math.max(0, models.size() - visibleCount);
            int maxScrollPixels = maxScroll * (CARD_HEIGHT + CARD_GAP);

            smoothScrollY = Math.max(0, Math.min(maxScrollPixels, smoothScrollY - verticalScroll * SCROLL_PIXELS_PER_TICK));
            scrollOffset = (int) (smoothScrollY / (CARD_HEIGHT + CARD_GAP));
            rebuildPage();
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalScroll, verticalScroll);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        switch (currentPage) {
            case PAGE_LANG -> renderLangPage(guiGraphics);
            case PAGE_MODELS -> renderModelsPage(guiGraphics);
        }
    }

    private void renderLangPage(GuiGraphics g) {
        String title = "选择语言";
        int tw = this.font.width(title);
        g.drawString(this.font, title, (this.width - tw) / 2, 24, 0xFFFFFFFF);
    }

    private void renderModelsPage(GuiGraphics g) {
        String title = selectedLangGroup + " — ASR 模型";
        int tw = this.font.width(title);
        g.drawString(this.font, title, (this.width - tw) / 2, 12, 0xFFFFFFFF);

        String desc = "语音识别模型，支持按质量/性能/性价比排序";
        int dw = this.font.width(desc);
        g.drawString(this.font, desc, (this.width - dw) / 2, 28, 0xFFAAAAAA);

        int listWidth = this.width / 3;
        int cardAreaX = (this.width - listWidth) / 2;
        int cardStartY = 66;

        List<AsrModelInfo> models = getCurrentModels();
        boolean hasGithubModel = models.stream().anyMatch(m -> m.downloadUrl != null && !m.downloadUrl.isBlank());

        int scrollbarSpace = SCROLLBAR_WIDTH + 2;
        int effectiveListWidth = listWidth - scrollbarSpace;

        String sortHintText = "当前排序: ";
        int sortHintWidth = this.font.width(sortHintText);
        int sortBtnWidth = 50;
        int sortBtnGap = 3;
        int totalSortWidth = sortHintWidth + sortBtnWidth * 3 + sortBtnGap * 2;
        int sortStartX = cardAreaX + effectiveListWidth - totalSortWidth;
        g.drawString(this.font, sortHintText, sortStartX, 46, 0xFFD8E6F0);

        if (hasGithubModel) {
            g.drawString(this.font, "代理:", cardAreaX + 4, cardStartY + 4, 0xFFCCCCCC);
            String proxyHint = "仅在 GitHub 官方不可达时才会使用";
            int proxyFieldWidth = Math.min(200, listWidth - 80);
            g.drawString(this.font, proxyHint, cardAreaX + 60 + proxyFieldWidth + 6, cardStartY + 4, 0xFF888888);
            cardStartY += 22;
        }

        int cardAreaHeight = this.height - 90 - (cardStartY - 44);
        int visibleCount = Math.max(1, cardAreaHeight / (CARD_HEIGHT + CARD_GAP));
        int maxScrollR = Math.max(0, models.size() - visibleCount);
        int maxScrollPixelsR = maxScrollR * (CARD_HEIGHT + CARD_GAP);

        if (models.isEmpty()) {
            String hint = "该语言暂无可用模型";
            int hw = this.font.width(hint);
            g.drawString(this.font, hint, (this.width - hw) / 2, cardStartY + 40, 0xFFAAAAAA);
            return;
        }

        double partialOffset = smoothScrollY % (CARD_HEIGHT + CARD_GAP);

        g.enableScissor(cardAreaX, cardStartY, cardAreaX + effectiveListWidth, cardStartY + cardAreaHeight);
        g.pose().pushPose();
        g.pose().translate(0, -partialOffset, 0);

        for (int i = 0; i < visibleCount && (i + scrollOffset) < models.size(); i++) {
            int idx = i + scrollOffset;
            AsrModelInfo info = models.get(idx);
            int cardY = cardStartY + i * (CARD_HEIGHT + CARD_GAP);
            int cardRight = cardAreaX + effectiveListWidth;

            g.fill(cardAreaX, cardY, cardRight, cardY + CARD_HEIGHT, 0xCC31475E);
            g.fill(cardAreaX, cardY, cardRight, cardY + 1, 0xFFA1C7ED);
            g.fill(cardAreaX, cardY + CARD_HEIGHT - 1, cardRight, cardY + CARD_HEIGHT, 0xFFA1C7ED);
            g.fill(cardAreaX, cardY, cardAreaX + 1, cardY + CARD_HEIGHT, 0xFFA1C7ED);
            g.fill(cardRight - 1, cardY, cardRight, cardY + CARD_HEIGHT, 0xFFA1C7ED);

            g.drawString(this.font, info.getDisplayName(), cardAreaX + 6, cardY + 4, 0xFFFFFF);

            String sizeStr = formatSize(info.size);
            String typeLabel = info.getModelType();
            String streamLabel = info.isStreaming ? "流式" : "非流式";
            String hotwordLabel = info.supportHotwords ? " | 热词" : "";
            g.drawString(this.font, typeLabel + " | " + streamLabel + hotwordLabel + " | ~" + sizeStr, cardAreaX + 6, cardY + 18, 0xD8E6F0);

            int qualityColor = getTierColor(info.getQualityTier());
            int perfColor = getTierColor(info.getPerformanceClass());
            String qualityText = getTierChinese(info.getQualityTier());
            String perfText = getTierChinese(info.getPerformanceClass());
            int cx = cardAreaX + 6;
            g.drawString(this.font, "质量 ", cx, cardY + 32, 0xFFB8D8B8);
            cx += this.font.width("质量 ");
            g.drawString(this.font, qualityText, cx, cardY + 32, qualityColor);
            cx += this.font.width(qualityText);
            g.drawString(this.font, " | 性能 ", cx, cardY + 32, 0xFFB8D8B8);
            cx += this.font.width(" | 性能 ");
            g.drawString(this.font, perfText, cx, cardY + 32, perfColor);
            cx += this.font.width(perfText);
            g.drawString(this.font, " | 性价比 " + info.getValueScore(), cx, cardY + 32, 0xFFB8D8B8);

            if (!info.isEngineSupported()) {
                g.drawString(this.font, "[尚未适配]", cardAreaX + 6, cardY + 48, 0xFFFFAA44);
            } else if (downloadingModel != null && downloadingModel.equals(info.name)) {
                g.drawString(this.font, "下载中 " + downloadProgress + "%", cardAreaX + 6, cardY + 48, 0xFFFFCC44);
            } else if (isModelDownloaded(info)) {
                g.drawString(this.font, "已下载", cardAreaX + 6, cardY + 48, 0xFF66FF66);
            }
        }

        g.pose().popPose();
        g.disableScissor();

        int totalContentHeight = models.size() * (CARD_HEIGHT + CARD_GAP);
        if (totalContentHeight > cardAreaHeight) {
            int scrollbarX = cardAreaX + effectiveListWidth + 1;
            int scrollbarHeight = cardAreaHeight;
            g.fill(scrollbarX, cardStartY, scrollbarX + SCROLLBAR_WIDTH, cardStartY + scrollbarHeight, 0x40303030);

            int thumbHeight = Math.max(20, (int) ((double) cardAreaHeight / totalContentHeight * scrollbarHeight));
            int maxThumbY = scrollbarHeight - thumbHeight;
            double scrollRatio = maxScrollPixelsR > 0 ? smoothScrollY / maxScrollPixelsR : 0;
            int thumbY = cardStartY + (int) (scrollRatio * maxThumbY);
            g.fill(scrollbarX, thumbY, scrollbarX + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0x80A1C7ED);
        }

        if (models.size() > visibleCount) {
            String scrollHint = (scrollOffset + 1) + "-" + Math.min(scrollOffset + visibleCount, models.size()) + "/" + models.size();
            int sw = this.font.width(scrollHint);
            g.drawString(this.font, scrollHint, (this.width - sw) / 2, this.height - 48, 0xAAAAAA);
        }
    }

    private static int getTierColor(String tier) {
        if (tier == null) return 0xFFB8D8B8;
        return switch (tier.toUpperCase()) {
            case "HIGH" -> 0xFF66FF66;
            case "MID" -> 0xFFFFAA44;
            case "LOW" -> 0xFFFF6666;
            default -> 0xFFB8D8B8;
        };
    }

    private static String getTierChinese(String tier) {
        if (tier == null) return "中";
        return switch (tier.toUpperCase()) {
            case "HIGH" -> "高";
            case "MID" -> "中";
            case "LOW" -> "低";
            default -> "中";
        };
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "未知";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
