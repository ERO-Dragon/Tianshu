package com.rheinmetal.tianshu.gui;

import com.rheinmetal.tianshu.audio.AudioManager;
import com.rheinmetal.tianshu.config.NeoForgeConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.model.ModelManager;
import com.rheinmetal.tianshu.model.TtsModelInfo;
import com.rheinmetal.tianshu.platform.NeoForgeNativeLibBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

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
    private static final String TIER_STANDARD_LABEL = "普通";
    private static final String TIER_PREMIUM_LABEL = "高端";

    private static final int PAGE_LANG = 0;
    private static final int PAGE_TIER = 1;
    private static final int PAGE_MODELS = 2;

    private final Screen parent;
    private final NeoForgeConfig config;
    private final TianshuCoreManager coreManager;
    private final AudioManager audioManager;
    private final NeoForgeNativeLibBridge nativeLibBridge;
    private EditBox proxyEditBox;

    private List<TtsModelInfo> allModels;
    private Map<String, List<TtsModelInfo>> groupedByLang;
    private List<String> langGroupOrder;

    private int currentPage = PAGE_LANG;
    private String selectedLangGroup;
    private String selectedTier = TIER_STANDARD_LABEL;

    private int scrollOffset = 0;
    private double smoothScrollY = 0;
    private static final int SCROLL_PIXELS_PER_TICK = 20;
    private String downloadingModel = null;
    private volatile int downloadProgress = 0;

    private static final int CARD_HEIGHT = 44;
    private static final int CARD_GAP = 4;
    private static final int LANG_CARD_WIDTH = 140;
    private static final int LANG_CARD_HEIGHT = 36;
    private static final int LANG_CARD_GAP = 8;
    private static final int TIER_CARD_WIDTH = 200;
    private static final int TIER_CARD_HEIGHT = 80;
    private static final int TIER_CARD_GAP = 16;

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
        rebuildPage();
    }

    private void loadAndGroupModels() {
        allModels = ModelManager.loadTtsModelCatalog();
        groupedByLang = new LinkedHashMap<>();
        langGroupOrder = new ArrayList<>();

        for (TtsModelInfo info : allModels) {
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

    private List<TtsModelInfo> getCurrentModels() {
        if (selectedLangGroup == null) return Collections.emptyList();
        List<TtsModelInfo> langModels = groupedByLang.getOrDefault(selectedLangGroup, Collections.emptyList());
        if (TIER_PREMIUM_LABEL.equals(selectedTier)) {
            return langModels.stream()
                    .filter(m -> TtsModelInfo.TIER_PREMIUM.equals(m.getTier()))
                    .collect(Collectors.toList());
        } else {
            return langModels.stream()
                    .filter(m -> !TtsModelInfo.TIER_PREMIUM.equals(m.getTier()))
                    .collect(Collectors.toList());
        }
    }

    private boolean hasTierInLang(String langGroup, String tierLabel) {
        List<TtsModelInfo> langModels = groupedByLang.getOrDefault(langGroup, Collections.emptyList());
        if (TIER_PREMIUM_LABEL.equals(tierLabel)) {
            return langModels.stream().anyMatch(m -> TtsModelInfo.TIER_PREMIUM.equals(m.getTier()));
        } else {
            return langModels.stream().anyMatch(m -> !TtsModelInfo.TIER_PREMIUM.equals(m.getTier()));
        }
    }

    private void navigateTo(int page) {
        currentPage = page;
        scrollOffset = 0;
        rebuildPage();
    }

    private void rebuildPage() {
        clearWidgets();

        switch (currentPage) {
            case PAGE_LANG -> buildLangPage();
            case PAGE_TIER -> buildTierPage();
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
                navigateTo(PAGE_TIER);
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

    private void buildTierPage() {
        int listWidth = this.width / 3;
        int centerX = (this.width - listWidth) / 2;
        int cardY = 60;
        int itemHeight = 40;
        int itemGap = 8;

        boolean hasStandard = hasTierInLang(selectedLangGroup, TIER_STANDARD_LABEL);
        boolean hasPremium = hasTierInLang(selectedLangGroup, TIER_PREMIUM_LABEL);

        if (hasStandard) {
            this.addRenderableWidget(new TianshuGUI.BrightButton(centerX, cardY, listWidth, itemHeight,
                    Component.literal(TIER_STANDARD_LABEL), b -> {
                selectedTier = TIER_STANDARD_LABEL;
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

                    String title = TIER_STANDARD_LABEL;
                    int tw = Minecraft.getInstance().font.width(title);
                    g.drawString(Minecraft.getInstance().font, title, x + (w - tw) / 2, y + 6, 0xFFFFFFFF);

                    String desc = "SherpaOnnx 引擎，轻量快速";
                    int dw = Minecraft.getInstance().font.width(desc);
                    g.drawString(Minecraft.getInstance().font, desc, x + (w - dw) / 2, y + 22, 0xFFAABBCC);
                }
            });
            cardY += itemHeight + itemGap;
        }

        if (hasPremium) {
            this.addRenderableWidget(new TianshuGUI.BrightButton(centerX, cardY, listWidth, itemHeight,
                    Component.literal(TIER_PREMIUM_LABEL), b -> {
                selectedTier = TIER_PREMIUM_LABEL;
                navigateTo(PAGE_MODELS);
            }) {
                @Override
                public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
                    int x = getX(), y = getY(), w = getWidth(), h = getHeight();
                    boolean hovered = this.isHovered();
                    int bgCol = hovered ? 0xFF6A3AB5 : 0xFF4A2A7A;
                    int borderCol = hovered ? 0xFFA78BFA : 0xFF7A5ACA;

                    g.fill(x, y, x + w, y + h, bgCol);
                    g.fill(x, y, x + w, y + 1, borderCol);
                    g.fill(x, y + h - 1, x + w, y + h, borderCol);
                    g.fill(x, y, x + 1, y + h, borderCol);
                    g.fill(x + w - 1, y, x + w, y + h, borderCol);

                    String title = TIER_PREMIUM_LABEL;
                    int tw = Minecraft.getInstance().font.width(title);
                    g.drawString(Minecraft.getInstance().font, title, x + (w - tw) / 2, y + 6, 0xFFFFFFFF);

                    String desc = "自回归合成，音质更自然";
                    int dw = Minecraft.getInstance().font.width(desc);
                    g.drawString(Minecraft.getInstance().font, desc, x + (w - dw) / 2, y + 22, 0xFFCCBBDD);
                }
            });
        }

        if (!hasStandard && !hasPremium) {
            String hint = "该语言暂无可用模型";
            int hw = this.font.width(hint);
            this.addRenderableWidget(TianshuGUI.BrightButton.create(Component.literal(hint), b -> {})
                    .pos(centerX, cardY).size(listWidth, 30).build());
        }
    }

    private void buildModelsPage() {
        int listWidth = this.width / 3;
        int cardAreaX = (this.width - listWidth) / 2;
        int cardStartY = 44;

        List<TtsModelInfo> models = getCurrentModels();
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
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        double partialOffset = smoothScrollY % (CARD_HEIGHT + CARD_GAP);

        for (int i = 0; i < visibleCount && (i + scrollOffset) < models.size(); i++) {
            int idx = i + scrollOffset;
            TtsModelInfo info = models.get(idx);
            int cardY = cardStartY + i * (CARD_HEIGHT + CARD_GAP) + (int) partialOffset;

            boolean isDownloaded = isModelDownloaded(info);
            boolean hasContent = hasModelContent(info);
            boolean isDownloading = info.name != null && info.name.equals(downloadingModel);

            int btnW = 40;
            int btnH = 14;
            int btnGap = 3;
            int btnRowX = cardAreaX + listWidth - btnW - 6;
            int btnRowY = cardY + CARD_HEIGHT - btnH - 3;

            if (isDownloading) {
                TianshuGUI.BrightButton cancelBtn = TianshuGUI.BrightButton.create(
                        Component.literal("取消"), b -> {
                            coreManager.cancelDownload();
                        }
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
                        Component.literal("删除"), b -> deleteTtsModel(info)
                ).pos(deleteBtnX, btnRowY).size(btnW, btnH).build();
                deleteBtn.active = hasContent;
                this.addRenderableWidget(deleteBtn);

                int selectBtnX = deleteBtnX - btnW - btnGap;
                TianshuGUI.BrightButton selectBtn = TianshuGUI.BrightButton.create(
                        Component.literal("选择"), b -> selectTtsModel(info)
                ).pos(selectBtnX, btnRowY).size(btnW, btnH).build();
                selectBtn.active = isDownloaded;
                this.addRenderableWidget(selectBtn);

                if ("zipvoice".equals(info.getEngineType()) && isDownloaded) {
                    int voiceBtnX = selectBtnX - btnW - btnGap;
                    TianshuGUI.BrightButton voiceBtn = TianshuGUI.BrightButton.create(
                            Component.literal("音色"), b -> {
                                String customPath = coreManager.getZipVoiceCustomVoicePath(info);
                                Minecraft.getInstance().player.displayClientMessage(
                                        Component.literal("\u00a7b[天枢] \u00a7f自定义音色: 将录音文件重命名为 custom_prompt.wav 放入:\n" + customPath), false);
                            }
                    ).pos(voiceBtnX, btnRowY).size(btnW, btnH).build();
                    this.addRenderableWidget(voiceBtn);
                }
            }
        }
    }

    private boolean isModelDownloaded(TtsModelInfo info) {
        return coreManager.getModelManager().isTtsModelDownloaded(info);
    }

    private boolean hasModelContent(TtsModelInfo info) {
        return coreManager.hasTtsModelContent(info);
    }

    private void startDownload(TtsModelInfo info) {
        downloadingModel = info.name;
        downloadProgress = 0;
        rebuildPage();

        String proxyUrl = (proxyEditBox != null) ? proxyEditBox.getValue() : null;
        coreManager.downloadTtsModel(info, proxyUrl, new TianshuCoreManager.DownloadProgressCallback() {
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

    private void deleteTtsModel(TtsModelInfo info) {
        coreManager.deleteTtsModel(info);
        scrollOffset = 0;
        rebuildPage();
    }

    private void selectTtsModel(TtsModelInfo info) {
        config.setCustomTtsName(info.name);
        config.save();
        Minecraft.getInstance().setScreen(new TianshuGUI(coreManager, config, audioManager, nativeLibBridge));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalScroll, double verticalScroll) {
        if (currentPage == PAGE_MODELS) {
            List<TtsModelInfo> models = getCurrentModels();
            int cardAreaHeight = this.height - 90;
            int visibleCount = Math.max(1, cardAreaHeight / (CARD_HEIGHT + CARD_GAP));
            int maxScroll = Math.max(0, models.size() - visibleCount);
            int maxScrollPixels = maxScroll * (CARD_HEIGHT + CARD_GAP);

            int oldOffset = scrollOffset;
            smoothScrollY = Math.max(0, Math.min(maxScrollPixels, smoothScrollY + verticalScroll * SCROLL_PIXELS_PER_TICK));
            scrollOffset = (int) (smoothScrollY / (CARD_HEIGHT + CARD_GAP));

            if (scrollOffset != oldOffset) {
                rebuildPage();
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalScroll, verticalScroll);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        switch (currentPage) {
            case PAGE_LANG -> renderLangPage(guiGraphics);
            case PAGE_TIER -> renderTierPage(guiGraphics);
            case PAGE_MODELS -> renderModelsPage(guiGraphics);
        }
    }

    private void renderLangPage(GuiGraphics g) {
        String title = "选择语言";
        int tw = this.font.width(title);
        g.drawString(this.font, title, (this.width - tw) / 2, 24, 0xFFFFFFFF);
    }

    private void renderTierPage(GuiGraphics g) {
        String title = "选择档位 — " + selectedLangGroup;
        int tw = this.font.width(title);
        g.drawString(this.font, title, (this.width - tw) / 2, 24, 0xFFFFFFFF);

        String hint = "普通和高端与预设档位无关，仅区分模型推理方式";
        int hw = this.font.width(hint);
        g.drawString(this.font, hint, (this.width - hw) / 2, 36, 0xFF888888);
    }

    private void renderModelsPage(GuiGraphics g) {
        String tierLabel = TIER_PREMIUM_LABEL.equals(selectedTier) ? TIER_PREMIUM_LABEL : TIER_STANDARD_LABEL;
        String title = selectedLangGroup + " — " + tierLabel + " 模型";
        int tw = this.font.width(title);
        g.drawString(this.font, title, (this.width - tw) / 2, 12, 0xFFFFFFFF);

        String tierDesc = TIER_PREMIUM_LABEL.equals(selectedTier)
                ? "自回归合成，音质更自然"
                : "SherpaOnnx 引擎，轻量快速";
        int dw = this.font.width(tierDesc);
        g.drawString(this.font, tierDesc, (this.width - dw) / 2, 28, 0xFFAAAAAA);

        int listWidth = this.width / 3;
        int cardAreaX = (this.width - listWidth) / 2;
        int cardStartY = 44;

        List<TtsModelInfo> models = getCurrentModels();
        boolean hasGithubModel = models.stream().anyMatch(m -> m.downloadUrl != null && !m.downloadUrl.isBlank());

        if (hasGithubModel) {
            g.drawString(this.font, "代理:", cardAreaX + 4, cardStartY + 4, 0xFFCCCCCC);
            String proxyHint = "可输入 GitHub 代理加速下载";
            int proxyFieldWidth = Math.min(200, listWidth - 80);
            g.drawString(this.font, proxyHint, cardAreaX + 60 + proxyFieldWidth + 6, cardStartY + 4, 0xFF888888);
            cardStartY += 22;
        }

        int cardAreaHeight = this.height - 90 - (cardStartY - 44);
        int visibleCount = Math.max(1, cardAreaHeight / (CARD_HEIGHT + CARD_GAP));

        if (models.isEmpty()) {
            String hint = "该分类暂无模型";
            int hw = this.font.width(hint);
            g.drawString(this.font, hint, (this.width - hw) / 2, cardStartY + 40, 0xFFAAAAAA);
            return;
        }

        double partialOffset = smoothScrollY % (CARD_HEIGHT + CARD_GAP);

        g.enableScissor(cardAreaX, cardStartY, cardAreaX + listWidth, cardStartY + cardAreaHeight);
        g.pose().pushPose();
        g.pose().translate(0, -partialOffset, 0);

        for (int i = 0; i < visibleCount && (i + scrollOffset) < models.size(); i++) {
            int idx = i + scrollOffset;
            TtsModelInfo info = models.get(idx);
            int cardY = cardStartY + i * (CARD_HEIGHT + CARD_GAP);

            boolean isPremium = TtsModelInfo.TIER_PREMIUM.equals(info.getTier());
            int cardBg = isPremium ? 0xCC4A3070 : 0xCC31475E;
            int cardBorder = isPremium ? 0xFFB088FF : 0xFFA1C7ED;

            g.fill(cardAreaX, cardY, cardAreaX + listWidth, cardY + CARD_HEIGHT, cardBg);
            g.fill(cardAreaX, cardY, cardAreaX + listWidth, cardY + 1, cardBorder);
            g.fill(cardAreaX, cardY + CARD_HEIGHT - 1, cardAreaX + listWidth, cardY + CARD_HEIGHT, cardBorder);
            g.fill(cardAreaX, cardY, cardAreaX + 1, cardY + CARD_HEIGHT, cardBorder);

            String displayName = info.name != null ? info.name : info.id;
            g.drawString(this.font, displayName, cardAreaX + 6, cardY + 4, 0xFFFFFF);

            String engineType = info.getEngineType();
            String sizeStr = formatSize(info.size);
            String infoLine = engineType + " | ~" + sizeStr;
            g.drawString(this.font, infoLine, cardAreaX + 6, cardY + 18, 0xD8E6F0);

            if (downloadingModel != null && downloadingModel.equals(info.name)) {
                String dlText = "下载中 " + downloadProgress + "%";
                g.drawString(this.font, dlText, cardAreaX + 6, cardY + 32, 0xFFCC44);
            } else if ("zipvoice".equals(engineType) && isModelDownloaded(info)) {
                g.drawString(this.font, "已下载 | 支持自定义音色", cardAreaX + 6, cardY + 32, 0xFFAADDFF);
            } else if (isModelDownloaded(info)) {
                g.drawString(this.font, "已下载", cardAreaX + 6, cardY + 32, 0x66FF66);
            }
        }

        g.pose().popPose();
        g.disableScissor();

        if (models.size() > visibleCount) {
            String scrollHint = (scrollOffset + 1) + "-" + Math.min(scrollOffset + visibleCount, models.size()) + "/" + models.size();
            int sw = this.font.width(scrollHint);
            g.drawString(this.font, scrollHint, (this.width - sw) / 2, this.height - 48, 0xAAAAAA);
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