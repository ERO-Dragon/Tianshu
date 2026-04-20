package com.rheinmetal.tianshu.gui;

import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.config.NeoForgeConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.model.ModelManager;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.platform.NeoForgeNativeLibBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class TtsModelSelectScreen extends Screen {

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
        LANG_NAMES.put("ar", "阿拉伯语");
        LANG_NAMES.put("pt", "葡萄牙语");
        LANG_NAMES.put("it", "意大利语");
        LANG_NAMES.put("nl", "荷兰语");
        LANG_NAMES.put("pl", "波兰语");
        LANG_NAMES.put("tr", "土耳其语");
        LANG_NAMES.put("vi", "越南语");
        LANG_NAMES.put("th", "泰语");
        LANG_NAMES.put("id", "印尼语");
        LANG_NAMES.put("fa", "波斯语");
        LANG_NAMES.put("uk", "乌克兰语");
        LANG_NAMES.put("cs", "捷克语");
        LANG_NAMES.put("sv", "瑞典语");
        LANG_NAMES.put("fi", "芬兰语");
        LANG_NAMES.put("da", "丹麦语");
        LANG_NAMES.put("el", "希腊语");
        LANG_NAMES.put("hu", "匈牙利语");
        LANG_NAMES.put("ro", "罗马尼亚语");
        LANG_NAMES.put("sk", "斯洛伐克语");
        LANG_NAMES.put("sl", "斯洛文尼亚语");
        LANG_NAMES.put("hr", "克罗地亚语");
        LANG_NAMES.put("lt", "立陶宛语");
        LANG_NAMES.put("lv", "拉脱维亚语");
        LANG_NAMES.put("et", "爱沙尼亚语");
        LANG_NAMES.put("bg", "保加利亚语");
        LANG_NAMES.put("bn", "孟加拉语");
        LANG_NAMES.put("ga", "爱尔兰语");
        LANG_NAMES.put("mt", "马耳他语");
        LANG_NAMES.put("af", "南非语");
        LANG_NAMES.put("tn", "茨瓦纳语");
        LANG_NAMES.put("gu", "古吉拉特语");
        LANG_NAMES.put("ne", "尼泊尔语");
    }

    private static final String OTHER_LANG_LABEL = "其他";

    private final Screen parent;
    private final NeoForgeConfig config;
    private final TianshuCoreManager coreManager;
    private final AudioManager audioManager;
    private final NeoForgeNativeLibBridge nativeLibBridge;

    private List<TtsModelInfo> allModels;
    private List<String> groupOrder;
    private Map<String, List<TtsModelInfo>> groupedModels;

    private int scrollOffset = 0;
    private String selectedGroup;
    private String downloadingModel = null;
    private volatile int downloadProgress = 0;

    private static final int CARD_HEIGHT = 44;
    private static final int CARD_GAP = 4;
    private static final int GROUP_TAB_HEIGHT = 22;
    private static final int GROUP_TAB_GAP = 4;
    private static final int GROUP_TAB_WIDTH = 56;

    public TtsModelSelectScreen(Screen parent, NeoForgeConfig config, TianshuCoreManager coreManager,
                                AudioManager audioManager, NeoForgeNativeLibBridge nativeLibBridge) {
        super(Component.literal("TTS 模型选择"));
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
        if (selectedGroup == null && !groupOrder.isEmpty()) {
            selectedGroup = groupOrder.get(0);
        }
        rebuildCards();
    }

    private void loadAndGroupModels() {
        allModels = ModelManager.loadTtsModelCatalog();
        groupedModels = new LinkedHashMap<>();
        groupOrder = new ArrayList<>();

        for (TtsModelInfo info : allModels) {
            List<String> langs = info.lang;
            if (langs == null || langs.isEmpty()) {
                langs = Collections.singletonList(OTHER_LANG_LABEL);
            }
            for (String lang : langs) {
                String groupKey = LANG_NAMES.getOrDefault(lang, OTHER_LANG_LABEL);
                if (!groupedModels.containsKey(groupKey)) {
                    groupedModels.put(groupKey, new ArrayList<>());
                    groupOrder.add(groupKey);
                }
                if (!groupedModels.get(groupKey).contains(info)) {
                    groupedModels.get(groupKey).add(info);
                }
            }
        }

        if (!groupOrder.contains(OTHER_LANG_LABEL) && groupedModels.containsKey(OTHER_LANG_LABEL)) {
            groupOrder.add(OTHER_LANG_LABEL);
        }

        String zhGroup = "中文";
        if (groupOrder.contains(zhGroup)) {
            groupOrder.remove(zhGroup);
            groupOrder.add(0, zhGroup);
        }
        String enGroup = "英语";
        if (groupOrder.contains(enGroup)) {
            groupOrder.remove(enGroup);
            groupOrder.add(groupOrder.isEmpty() ? 0 : 1, enGroup);
        }
    }

    private List<TtsModelInfo> getCurrentGroupModels() {
        if (selectedGroup == null) return Collections.emptyList();
        return groupedModels.getOrDefault(selectedGroup, Collections.emptyList());
    }

    private void rebuildCards() {
        clearWidgets();

        int tabAreaX = 16;
        int tabStartY = 40;
        int tabAreaWidth = GROUP_TAB_WIDTH + 8;

        int tabsPerCol = Math.max(1, (this.height - 120) / (GROUP_TAB_HEIGHT + GROUP_TAB_GAP));
        int tabIdx = 0;
        for (String group : groupOrder) {
            int col = tabIdx % tabsPerCol;
            int tabX = tabAreaX;
            int tabY = tabStartY + col * (GROUP_TAB_HEIGHT + GROUP_TAB_GAP);
            boolean isSelected = group.equals(selectedGroup);

            int bgCol = isSelected ? 0xFF3A7BD5 : 0xFF2A3A4A;
            int borderCol = isSelected ? 0xFF5AACFF : 0xFF3A4A5A;
            int textCol = isSelected ? 0xFFFFFFFF : 0xFF8899AA;

            this.addRenderableWidget(new TianshuGUI.BrightButton(tabX, tabY, GROUP_TAB_WIDTH, GROUP_TAB_HEIGHT,
                    Component.literal(group), b -> {
                selectedGroup = group;
                scrollOffset = 0;
                rebuildCards();
            }) {
                @Override
                public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
                    int x = getX(), y = getY(), w = getWidth(), h = getHeight();
                    g.fill(x, y, x + w, y + h, bgCol);
                    g.fill(x, y, x + w, y + 1, borderCol);
                    g.fill(x, y + h - 1, x + w, y + h, borderCol);
                    g.fill(x, y, x + 1, y + h, borderCol);
                    g.fill(x + w - 1, y, x + w, y + h, borderCol);
                    String text = getMessage().getString();
                    int tw = Minecraft.getInstance().font.width(text);
                    g.drawString(Minecraft.getInstance().font, getMessage(), x + (w - tw) / 2, y + (h - 8) / 2, textCol);
                }
            });
            tabIdx++;
        }

        int cardAreaX = tabAreaX + tabAreaWidth + 12;
        int cardAreaWidth = this.width - cardAreaX - 16;
        int cardStartY = 40;
        int cardAreaHeight = this.height - 100;
        int visibleCount = Math.max(1, cardAreaHeight / (CARD_HEIGHT + CARD_GAP));

        List<TtsModelInfo> models = getCurrentGroupModels();
        int maxScroll = Math.max(0, models.size() - visibleCount);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        for (int i = 0; i < visibleCount && (i + scrollOffset) < models.size(); i++) {
            int idx = i + scrollOffset;
            TtsModelInfo info = models.get(idx);
            int cardY = cardStartY + i * (CARD_HEIGHT + CARD_GAP);

            boolean isDownloaded = isModelDownloaded(info);
            boolean hasContent = hasModelContent(info);
            boolean isDownloading = info.name != null && info.name.equals(downloadingModel);

            int btnW = 56;
            int btnH = 16;
            int btnGap = 6;
            int btnRowX = cardAreaX + cardAreaWidth - btnW - 8;
            int btnRowY = cardY + CARD_HEIGHT - btnH - 4;

            TianshuGUI.BrightButton downloadBtn = TianshuGUI.BrightButton.create(
                    Component.literal(isDownloading ? downloadProgress + "%" : (isDownloaded ? "已下载" : "下载")),
                    b -> {
                        if (!isDownloaded && !isDownloading) {
                            startDownload(info);
                        }
                    }
            ).pos(btnRowX, btnRowY).size(btnW, btnH).build();
            downloadBtn.active = !isDownloaded && !isDownloading;
            this.addRenderableWidget(downloadBtn);

            int deleteBtnX = btnRowX - btnW - btnGap;
            TianshuGUI.BrightButton deleteBtn = TianshuGUI.BrightButton.create(
                    Component.literal("删除"), b -> deleteTtsModel(info)
            ).pos(deleteBtnX, btnRowY).size(btnW, btnH).build();
            deleteBtn.active = hasContent && !isDownloading;
            this.addRenderableWidget(deleteBtn);

            int selectBtnX = deleteBtnX - btnW - btnGap;
            TianshuGUI.BrightButton selectBtn = TianshuGUI.BrightButton.create(
                    Component.literal("选择"), b -> selectTtsModel(info)
            ).pos(selectBtnX, btnRowY).size(btnW, btnH).build();
            selectBtn.active = isDownloaded;
            this.addRenderableWidget(selectBtn);
        }

        this.addRenderableWidget(TianshuGUI.BrightButton.create(Component.literal("返回"), b -> {
            Minecraft.getInstance().setScreen(parent);
        }).pos(this.width / 2 - 50, this.height - 30).size(100, 20).build());
    }

    private boolean isModelDownloaded(TtsModelInfo info) {
        return coreManager.getModelManager().isTtsModelDownloaded(info);
    }

    private boolean hasModelContent(TtsModelInfo info) {
        if (info.name == null) return false;
        Path modelDir = config.getTtsBasePath().resolve(info.name);
        if (!Files.exists(modelDir) || !Files.isDirectory(modelDir)) return false;
        try {
            return Files.list(modelDir).findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private void startDownload(TtsModelInfo info) {
        downloadingModel = info.name;
        downloadProgress = 0;
        rebuildCards();

        coreManager.downloadTtsModel(info, new TianshuCoreManager.DownloadProgressCallback() {
            @Override
            public void onProgress(String label, int percent) {
                downloadProgress = percent;
                Minecraft.getInstance().execute(() -> rebuildCards());
            }

            @Override
            public void onComplete() {
                Minecraft.getInstance().execute(() -> {
                    downloadingModel = null;
                    downloadProgress = 0;
                    rebuildCards();
                });
            }

            @Override
            public void onError(String message) {
                Minecraft.getInstance().execute(() -> {
                    downloadingModel = null;
                    downloadProgress = 0;
                    rebuildCards();
                });
            }
        });
    }

    private void deleteTtsModel(TtsModelInfo info) {
        if (info.name == null) return;
        Path modelDir = config.getTtsBasePath().resolve(info.name);
        try {
            deleteRecursively(modelDir);
        } catch (IOException ignored) {}
        scrollOffset = 0;
        rebuildCards();
    }

    private void selectTtsModel(TtsModelInfo info) {
        config.setCustomTtsName(info.name);
        config.save();
        Minecraft.getInstance().setScreen(new TianshuGUI(coreManager, config, audioManager, nativeLibBridge));
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path child : entries.collect(Collectors.toList())) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(path);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalScroll, double verticalScroll) {
        int tabAreaWidth = GROUP_TAB_WIDTH + 8;
        int cardAreaX = 16 + tabAreaWidth + 12;
        if (mouseX >= cardAreaX) {
            List<TtsModelInfo> models = getCurrentGroupModels();
            int cardAreaHeight = this.height - 100;
            int visibleCount = Math.max(1, cardAreaHeight / (CARD_HEIGHT + CARD_GAP));
            int maxScroll = Math.max(0, models.size() - visibleCount);
            if (verticalScroll > 0 && scrollOffset > 0) {
                scrollOffset--;
                rebuildCards();
            } else if (verticalScroll < 0 && scrollOffset < maxScroll) {
                scrollOffset++;
                rebuildCards();
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalScroll, verticalScroll);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        String title = "TTS 模型选择（共 " + allModels.size() + " 个模型）";
        guiGraphics.drawString(this.font, title, (this.width - this.font.width(title)) / 2, 16, 0xFFFFFF);

        int tabAreaWidth = GROUP_TAB_WIDTH + 8;
        int cardAreaX = 16 + tabAreaWidth + 12;
        int cardAreaWidth = this.width - cardAreaX - 16;
        int cardStartY = 40;
        int cardAreaHeight = this.height - 100;
        int visibleCount = Math.max(1, cardAreaHeight / (CARD_HEIGHT + CARD_GAP));

        List<TtsModelInfo> models = getCurrentGroupModels();

        if (models.isEmpty()) {
            String hint = "该语言分组暂无模型";
            guiGraphics.drawString(this.font, hint, cardAreaX + (cardAreaWidth - this.font.width(hint)) / 2, cardStartY + 40, 0xFFAAAA);
            return;
        }

        for (int i = 0; i < visibleCount && (i + scrollOffset) < models.size(); i++) {
            int idx = i + scrollOffset;
            TtsModelInfo info = models.get(idx);
            int cardY = cardStartY + i * (CARD_HEIGHT + CARD_GAP);

            guiGraphics.fill(cardAreaX, cardY, cardAreaX + cardAreaWidth, cardY + CARD_HEIGHT, 0xCC31475E);
            guiGraphics.fill(cardAreaX, cardY, cardAreaX + cardAreaWidth, cardY + 1, 0xFFA1C7ED);
            guiGraphics.fill(cardAreaX, cardY + CARD_HEIGHT - 1, cardAreaX + cardAreaWidth, cardY + CARD_HEIGHT, 0xFFA1C7ED);
            guiGraphics.fill(cardAreaX, cardY, cardAreaX + 1, cardY + CARD_HEIGHT, 0xFFA1C7ED);

            String displayName = info.name != null ? info.name : info.id;
            guiGraphics.drawString(this.font, displayName, cardAreaX + 8, cardY + 4, 0xFFFFFF);

            String langStr = buildLangString(info);
            String sizeStr = formatSize(info.size);
            String engineType = info.getEngineType();
            String infoLine = langStr + "  |  " + engineType + "  |  ~" + sizeStr;
            guiGraphics.drawString(this.font, infoLine, cardAreaX + 8, cardY + 18, 0xD8E6F0);

            if (downloadingModel != null && downloadingModel.equals(info.name)) {
                String dlText = "下载中 " + downloadProgress + "%";
                guiGraphics.drawString(this.font, dlText, cardAreaX + 8, cardY + 32, 0xFFCC44);
            } else if (isModelDownloaded(info)) {
                guiGraphics.drawString(this.font, "已下载", cardAreaX + 8, cardY + 32, 0x66FF66);
            }
        }

        if (models.size() > visibleCount) {
            String scrollHint = (scrollOffset + 1) + "-" + Math.min(scrollOffset + visibleCount, models.size()) + "/" + models.size();
            guiGraphics.drawString(this.font, scrollHint, cardAreaX + (cardAreaWidth - this.font.width(scrollHint)) / 2, this.height - 48, 0xAAAAAA);
        }
    }

    private String buildLangString(TtsModelInfo info) {
        if (info.lang == null || info.lang.isEmpty()) return "未知语言";
        StringBuilder sb = new StringBuilder();
        for (String lang : info.lang) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(LANG_NAMES.getOrDefault(lang, lang));
        }
        return sb.toString();
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
