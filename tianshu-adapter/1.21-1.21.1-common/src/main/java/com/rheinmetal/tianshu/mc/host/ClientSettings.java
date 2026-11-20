package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.client.host.settings.ClientAudioSettingsPort;
import com.rheinmetal.tianshu.client.host.settings.ClientPackSettingsPort;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.flag.FeatureFlagSet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The client's own preferences: volume channels and resource packs.
 *
 * <p>Everything here reads or writes {@link Minecraft#options}, which belongs to the person at the keyboard. Nothing in
 * this class talks to a server, asks for permission or touches the world.</p>
 *
 * <p>Both domains answer "nothing" rather than throwing when the client is absent: a module may ask before a client
 * exists, and a preference is not a game loop.</p>
 */
public final class ClientSettings implements ClientAudioSettingsPort, ClientPackSettingsPort {

    @Override
    public List<Channel> channels() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return List.of();
        }
        List<Channel> channels = new ArrayList<>();
        for (SoundSource source : SoundSource.values()) {
            var option = minecraft.options.getSoundSourceOptionInstance(source);
            if (option == null) {
                continue;
            }
            channels.add(new Channel(source.getName(), label(source.getName()), percent(option.get())));
        }
        return channels;
    }

    @Override
    public boolean setVolume(String channel, int percent) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null || channel == null) {
            return false;
        }
        String wanted = channel.trim().toLowerCase(Locale.ROOT);
        for (SoundSource source : SoundSource.values()) {
            if (!source.getName().equals(wanted)) {
                continue;
            }
            var option = minecraft.options.getSoundSourceOptionInstance(source);
            if (option == null) {
                return false;
            }
            option.set(Math.max(0, Math.min(100, percent)) / 100.0D);
            minecraft.options.save();
            return true;
        }
        return false;
    }

    @Override
    public List<Pack> packs() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return List.of();
        }
        Set<String> selected = new LinkedHashSet<>(minecraft.options.resourcePacks);
        List<Pack> packs = new ArrayList<>();
        for (var pack : minecraft.getResourcePackRepository().getAvailablePacks()) {
            packs.add(new Pack(pack.getId(), pack.getTitle().getString(), selected.contains(pack.getId()),
                    pack.isRequired()));
        }
        return packs;
    }

    @Override
    public boolean setEnabled(String packId, boolean enabled) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null || packId == null) {
            return false;
        }
        String wanted = packId.trim();
        var repository = minecraft.getResourcePackRepository();
        boolean known = false;
        boolean required = false;
        for (var pack : repository.getAvailablePacks()) {
            if (pack.getId().equals(wanted)) {
                known = true;
                required = pack.isRequired();
                break;
            }
        }
        if (!known || (required && !enabled)) {
            return false;
        }
        List<String> selected = new ArrayList<>(minecraft.options.resourcePacks);
        selected.remove(wanted);
        if (enabled) {
            selected.add(wanted);
        }
        minecraft.options.resourcePacks = selected;
        minecraft.options.save();
        // The screen and the option list have to agree again: a pack that is "enabled" but not loaded is a lie.
        minecraft.reloadResourcePacks();
        return true;
    }

    private static int percent(double fraction) {
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, fraction)) * 100.0D);
    }

    private static String label(String sourceName) {
        return Component.translatable("soundCategory." + sourceName).getString();
    }

    /** Not used at runtime; kept so a future feature-flag check has one obvious place. */
    private static FeatureFlagSet featureFlags(Minecraft minecraft) {
        return minecraft.level == null ? FeatureFlagSet.of() : minecraft.level.enabledFeatures();
    }
}