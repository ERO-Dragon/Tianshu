package com.rheinmetal.tianshu.client.junk;

import com.rheinmetal.tianshu.client.ir.ClientItemCommandManager;
import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.function.junk.JunkTextNormalizer;
import com.rheinmetal.tianshu.function.junk.JunkVoiceAction;
import com.rheinmetal.tianshu.function.junk.JunkVoiceIntent;
import com.rheinmetal.tianshu.function.junk.JunkVoiceRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class JunkVoiceIntentParser {
    public JunkVoiceIntent parse(String text) {
        if (text == null || text.isBlank()) return JunkVoiceIntent.none();
        String normalized = normalize(text);
        if (JunkVoiceRules.isClearIntent(normalized)) {
            return new JunkVoiceIntent(JunkVoiceAction.CLEAR, "", "");
        }
        JunkVoiceAction action = JunkVoiceRules.resolveMarkAction(normalized);
        if (action == JunkVoiceAction.NONE) return JunkVoiceIntent.none();
        ItemMatch match = resolveItem(text);
        if (match == null || match.itemId().isBlank()) return JunkVoiceIntent.none();
        return new JunkVoiceIntent(action, match.itemId(), match.displayName());
    }

    private ItemMatch resolveItem(String text) {
        try {
            IRParseResult result = ClientItemCommandManager.parsePlayerCommand(text, true);
            if (result != null && result.getBestCandidateRealItemId() != null && !result.getBestCandidateRealItemId().isBlank()) {
                return new ItemMatch(result.getBestCandidateRealItemId(), result.getBestCandidateText());
            }
            if (result != null) {
                for (var unit : result.getUnits()) {
                    if (unit.targetRealItemId != null && !unit.targetRealItemId.isBlank()) {
                        return new ItemMatch(unit.targetRealItemId, unit.targetRealItemId);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return resolveVisibleContainerNameMatch(text);
    }

    private ItemMatch resolveVisibleContainerNameMatch(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) return null;
        String normalized = normalize(text);
        if (minecraft.screen instanceof AbstractContainerScreen<?> screen) {
            for (Slot slot : screen.getMenu().slots) {
                ItemMatch match = matchStack(slot.getItem(), normalized);
                if (match != null) return match;
            }
        }
        for (int i = 0; i < minecraft.player.getInventory().getContainerSize(); i++) {
            ItemMatch match = matchStack(minecraft.player.getInventory().getItem(i), normalized);
            if (match != null) return match;
        }
        return null;
    }

    private ItemMatch matchStack(ItemStack stack, String normalizedText) {
        if (stack.isEmpty()) return null;
        String displayName = stack.getHoverName().getString();
        String itemId = stack.getItemHolder().getRegisteredName();
        if (normalizedText.contains(normalize(displayName)) || normalizedText.contains(normalize(itemId))) {
            return new ItemMatch(itemId, displayName);
        }
        return null;
    }

    private String normalize(String text) {
        return JunkTextNormalizer.normalize(text);
    }

    private record ItemMatch(String itemId, String displayName) {
    }
}
