package com.rheinmetal.tianshu.neoforge.adapter;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientPresentationPort;
import com.rheinmetal.tianshu.neoforge.ui.settings.NeoForgeUiText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * The client's local presentation primitives, implemented on vanilla overlays.
 *
 * <p>Everything here is local: a chat line is {@code displayClientMessage}, a toast is a vanilla
 * {@link SystemToast}, a title is the vanilla title overlay. Nothing is sent to the server, so no other player
 * sees or hears these effects.</p>
 *
 * <p>Each call is a hand-off to an overlay that already exists; there is no per-frame work and no allocation
 * beyond the component itself. Callers that need continuous drawing use a HUD layer instead.</p>
 */
public final class NeoForgeClientPresentation implements ClientPresentationPort {

    @Override
    public void chat(UiText text) {
        message(text, false);
    }

    @Override
    public void actionBar(UiText text) {
        message(text, true);
    }

    @Override
    public void toast(UiText title, UiText description) {
        if (title == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getToasts() == null) {
            return;
        }
        Component body = description == null ? Component.empty() : NeoForgeUiText.toComponent(description);
        minecraft.getToasts().addToast(new SystemToast(
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                NeoForgeUiText.toComponent(title),
                body
        ));
    }

    @Override
    public void title(UiText title, UiText subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null) {
            return;
        }
        minecraft.gui.setTimes(Math.max(0, fadeInTicks), Math.max(0, stayTicks), Math.max(0, fadeOutTicks));
        minecraft.gui.setTitle(title == null ? Component.empty() : NeoForgeUiText.toComponent(title));
        minecraft.gui.setSubtitle(subtitle == null ? Component.empty() : NeoForgeUiText.toComponent(subtitle));
    }

    @Override
    public void playSound(String soundId, float volume, float pitch) {
        if (soundId == null || soundId.isBlank()) {
            return;
        }
        ResourceLocation key = ResourceLocation.tryParse(soundId.trim());
        if (key == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) {
            return;
        }
        SoundEvent event = SoundEvent.createVariableRangeEvent(key);
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(event, Math.max(0.0F, volume), pitch));
    }

    private void message(UiText text, boolean actionBar) {
        if (text == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        minecraft.player.displayClientMessage(NeoForgeUiText.toComponent(text), actionBar);
    }
}
