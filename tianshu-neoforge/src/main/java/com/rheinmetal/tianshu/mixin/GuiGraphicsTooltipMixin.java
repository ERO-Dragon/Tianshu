package com.rheinmetal.tianshu.mixin;

import com.rheinmetal.tianshu.client.geminicard.GeminiCardTooltipAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsTooltipMixin {

    @Shadow
    private ItemStack tooltipStack;

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V", at = @At("HEAD"))
    private void tianshu$appendGeminiCardTooltip(Font font, List<Component> lines, Optional<TooltipComponent> tooltipComponent, int mouseX, int mouseY, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        GeminiCardTooltipAdapter.appendComponentTooltipLines(this.tooltipStack, lines, minecraft.player);
    }

    @Inject(method = "renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void tianshu$appendGeminiCardComponentTooltip(Font font, List<FormattedText> lines, int mouseX, int mouseY, ItemStack stack, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        GeminiCardTooltipAdapter.appendTooltipLines(stack, lines, minecraft.player);
    }
}
