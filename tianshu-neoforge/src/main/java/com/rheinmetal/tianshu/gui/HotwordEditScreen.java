package com.rheinmetal.tianshu.gui;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.model.ModelSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class HotwordEditScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Screen parent;
    private final Path modelDir;
    private EditBox editBox;
    private ModelSettings.AsrSettings settings;

    public HotwordEditScreen(Screen parent, Path modelDir) {
        super(Component.literal("编辑热词"));
        this.parent = parent;
        this.modelDir = modelDir;
    }

    @Override
    protected void init() {
        super.init();

        settings = ModelSettings.loadAsrSettings(modelDir);

        int boxWidth = Math.min(300, this.width - 80);
        int boxX = (this.width - boxWidth) / 2;
        int boxY = 50;

        editBox = new EditBox(this.font, boxX, boxY, boxWidth, 20, Component.literal("热词编辑"));
        editBox.setMaxLength(1000);
        editBox.setValue(String.join(",", settings.hotwords));
        editBox.setHint(Component.literal("输入热词，用逗号分隔"));
        this.addRenderableWidget(editBox);

        this.addRenderableWidget(Button.builder(Component.literal("保存并返回"), b -> {
            saveHotwords();
            Minecraft.getInstance().setScreen(parent);
        }).pos(this.width / 2 - 60, boxY + 36).size(120, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> {
            Minecraft.getInstance().setScreen(parent);
        }).pos(this.width / 2 - 40, boxY + 64).size(80, 20).build());
    }

    private void saveHotwords() {
        String text = editBox.getValue();
        List<String> words = new ArrayList<>();
        for (String w : text.split("[,，\n]")) {
            String trimmed = w.trim();
            if (!trimmed.isEmpty()) words.add(trimmed);
        }
        settings.hotwords = words;
        settings.hotwordsScore = Math.max(0.1, settings.hotwordsScore);
        ModelSettings.saveAsrSettings(modelDir, settings);

        try {
            Path hotwordsFile = modelDir.resolve("hotwords.txt");
            if (words.isEmpty()) {
                Files.deleteIfExists(hotwordsFile);
            } else {
                Files.writeString(hotwordsFile, String.join("\n", words));
            }
        } catch (Exception e) {
            LOGGER.error("保存热词文件失败", e);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        String title = "编辑热词（每行一个或用逗号分隔）";
        guiGraphics.drawString(this.font, title, (this.width - this.font.width(title)) / 2, 30, 0xFFFFFF);

        String hint = "热词分数: " + String.format("%.1f", settings.hotwordsScore) + "（修改热词后重新初始化ASR生效）";
        guiGraphics.drawString(this.font, hint, (this.width - this.font.width(hint)) / 2, 76 + 64, 0xAAAAAA);

        String currentWords = "当前热词数: " + settings.hotwords.size();
        guiGraphics.drawString(this.font, currentWords, (this.width - this.font.width(currentWords)) / 2, 76 + 80, 0xD8E6F0);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
