package com.rheinmetal.tianshu.mixin;

import com.rheinmetal.tianshu.client.geminicard.GeminiCardTooltipAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsTooltipMixin {

    @Inject(method = "renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void tianshu$appendGeminiCardTooltip(Font font, List<FormattedText> lines, int mouseX, int mouseY, ItemStack stack, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        GeminiCardTooltipAdapter.appendTooltipLines(stack, lines, minecraft.player);
    }
}
