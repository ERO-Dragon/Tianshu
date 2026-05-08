package com.rheinmetal.tianshu.client.junk;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.function.junk.JunkVoiceIntent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

public final class JunkCleanerClientController {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final JunkListStore store = new JunkListStore();
    private final JunkVoiceIntentParser parser = new JunkVoiceIntentParser();

    public boolean handleAsrText(String text) {
        JunkVoiceIntent intent = parser.parse(text);
        if (!intent.actionable()) return false;
        return switch (intent.action()) {
            case MARK -> mark(intent.itemId(), intent.displayName());
            case UNMARK -> unmark(intent.itemId(), intent.displayName());
            case CLEAR -> requestClear();
            case NONE -> false;
        };
    }

    public boolean isJunk(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return store.contains(stack.getItemHolder().getRegisteredName());
    }

    private boolean mark(String itemId, String displayName) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean changed = store.add(itemId);
        String name = !displayName.isBlank() ? displayName : itemId;
        display(changed ? "已标记垃圾：" + name : "已经在垃圾清单：" + name);
        if (minecraft != null) LOGGER.info("[净囊] 标记垃圾 itemId={}", itemId);
        return true;
    }

    private boolean unmark(String itemId, String displayName) {
        boolean changed = store.remove(itemId);
        String name = !displayName.isBlank() ? displayName : itemId;
        display(changed ? "已取消垃圾标记：" + name : "垃圾清单中没有：" + name);
        return true;
    }

    private boolean requestClear() {
        JunkClearRequestClientGateway.Result result = JunkClearRequestClientGateway.request(store.snapshot());
        display(result.message());
        return true;
    }

    private void display(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("§b[净囊] §f" + message), false);
        }
    }
}
