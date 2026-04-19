package com.rheinmetal.tianshu.gui;

import com.rheinmetal.tianshu.Tianshu;
import com.rheinmetal.tianshu.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class ModelSelectScreen extends Screen {

    private final Screen parent;
    private final String modelType;
    private List<Path> modelDirs = new ArrayList<>();
    private int scrollOffset = 0;
    private static final int CARD_HEIGHT = 36;
    private static final int CARD_GAP = 6;

    public ModelSelectScreen(Screen parent, String modelType) {
        super(Component.literal("选择" + modelType + "模型"));
        this.parent = parent;
        this.modelType = modelType;
    }

    @Override
    public void init() {
        super.init();
        scanModels();
        rebuildCardWidgets();
    }

    private void scanModels() {
        modelDirs.clear();
        Path basePath = switch (modelType) {
            case "ASR" -> Config.getAsrBasePath();
            case "LLM" -> Config.getLlmBasePath();
            case "TTS" -> Config.getTtsBasePath();
            default -> throw new IllegalArgumentException("Unknown model type: " + modelType);
        };

        if (!Files.exists(basePath)) {
            try {
                Files.createDirectories(basePath);
            } catch (IOException e) {
                Tianshu.LOGGER.error("创建模型目录失败: {}", basePath, e);
            }
            return;
        }

        try {
            modelDirs = Files.list(basePath)
                    .filter(Files::isDirectory)
                    .filter(this::isValidModelDir)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            Tianshu.LOGGER.error("扫描模型目录失败: {}", basePath, e);
        }
    }

    private boolean isValidModelDir(Path dir) {
        try {
            return Files.list(dir).anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".onnx") || name.endsWith(".bin") || name.endsWith(".gguf") || name.endsWith(".txt");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private void rebuildCardWidgets() {
        clearWidgets();

        int contentX = 24;
        int contentWidth = this.width - 48;
        int startY = 50;
        int visibleCount = Math.max(1, (this.height - 120) / (CARD_HEIGHT + CARD_GAP));

        int maxScroll = Math.max(0, modelDirs.size() - visibleCount);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        for (int i = 0; i < visibleCount && (i + scrollOffset) < modelDirs.size(); i++) {
            int idx = i + scrollOffset;
            Path dir = modelDirs.get(idx);
            String folderName = dir.getFileName().toString();
            int cardY = startY + i * (CARD_HEIGHT + CARD_GAP);

            int selectX = contentX + contentWidth - 90;
            this.addRenderableWidget(Button.builder(Component.literal("选择"), b -> {
                selectModel(folderName);
            }).pos(selectX, cardY + 8).size(72, 20).build());

            int deleteX = selectX - 58;
            this.addRenderableWidget(Button.builder(Component.literal("删除"), b -> {
                deleteModel(dir);
            }).pos(deleteX, cardY + 8).size(48, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("返回"), b -> {
            Minecraft.getInstance().setScreen(parent);
        }).pos(this.width / 2 - 50, this.height - 36).size(100, 20).build());
    }

    private void selectModel(String folderName) {
        switch (modelType) {
            case "ASR" -> Config.CUSTOM_ASR_NAME.set(folderName);
            case "LLM" -> Config.CUSTOM_LLM_NAME.set(folderName);
            case "TTS" -> Config.CUSTOM_TTS_NAME.set(folderName);
        }
        Config.SPEC.save();
        Minecraft.getInstance().setScreen(new TianshuGUI());
    }

    private void deleteModel(Path dir) {
        try {
            deleteRecursively(dir);
            scanModels();
            scrollOffset = 0;
            rebuildCardWidgets();
        } catch (IOException e) {
            Tianshu.LOGGER.error("删除模型失败: {}", dir, e);
        }
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
        int visibleCount = Math.max(1, (this.height - 120) / (CARD_HEIGHT + CARD_GAP));
        int maxScroll = Math.max(0, modelDirs.size() - visibleCount);
        if (verticalScroll > 0 && scrollOffset > 0) {
            scrollOffset--;
            rebuildCardWidgets();
        } else if (verticalScroll < 0 && scrollOffset < maxScroll) {
            scrollOffset++;
            rebuildCardWidgets();
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalScroll, verticalScroll);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        String title = "选择" + modelType + "模型（共 " + modelDirs.size() + " 个）";
        guiGraphics.drawString(this.font, title, (this.width - this.font.width(title)) / 2, 20, 0xFFFFFF);

        if (modelDirs.isEmpty()) {
            String hint = "未找到模型，请先将模型文件放入对应目录";
            guiGraphics.drawString(this.font, hint, (this.width - this.font.width(hint)) / 2, 80, 0xFFAAAA);
            return;
        }

        int contentX = 24;
        int contentWidth = this.width - 48;
        int startY = 50;
        int visibleCount = Math.max(1, (this.height - 120) / (CARD_HEIGHT + CARD_GAP));

        for (int i = 0; i < visibleCount && (i + scrollOffset) < modelDirs.size(); i++) {
            int idx = i + scrollOffset;
            Path dir = modelDirs.get(idx);
            String folderName = dir.getFileName().toString();
            int cardY = startY + i * (CARD_HEIGHT + CARD_GAP);

            guiGraphics.fill(contentX, cardY, contentX + contentWidth, cardY + CARD_HEIGHT, 0xCC31475E);
            guiGraphics.fill(contentX, cardY, contentX + contentWidth, cardY + 1, 0xFFA1C7ED);
            guiGraphics.fill(contentX, cardY + CARD_HEIGHT - 1, contentX + contentWidth, cardY + CARD_HEIGHT, 0xFFA1C7ED);
            guiGraphics.fill(contentX, cardY, contentX + 1, cardY + CARD_HEIGHT, 0xFFA1C7ED);

            guiGraphics.drawString(this.font, folderName, contentX + 10, cardY + 4, 0xFFFFFF);

            String sizeStr = formatSize(getDirSize(dir));
            guiGraphics.drawString(this.font, sizeStr, contentX + 10, cardY + 20, 0xD8E6F0);
        }

        if (modelDirs.size() > visibleCount) {
            String scrollHint = "滚动查看更多 (" + (scrollOffset + 1) + "-" + Math.min(scrollOffset + visibleCount, modelDirs.size()) + "/" + modelDirs.size() + ")";
            guiGraphics.drawString(this.font, scrollHint, (this.width - this.font.width(scrollHint)) / 2, this.height - 56, 0xAAAAAA);
        }
    }

    private long getDirSize(Path dir) {
        try {
            return Files.walk(dir).filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
