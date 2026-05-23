package com.rheinmetal.tianshu.platform.provider;

import com.rheinmetal.tianshu.client.language.ClientLanguagePolicy;
import com.rheinmetal.tianshu.provider.IPlayerStateProvider;
import com.rheinmetal.tianshu.snapshot.PlayerStatusData;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

public class NeoForgePlayerStateProvider implements IPlayerStateProvider {

    @Override
    public String getCurrentDimensionDisplayName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return "unknown";
        try {
            String dimensionId = mc.level.dimension().location().toString();
            String displayName = ClientLanguagePolicy.registryDisplayName(mc.level.dimension().location(), "dimension");
            return displayName == null || displayName.isBlank() ? dimensionId : displayName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public PlayerStatusData getPlayerStatus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return new PlayerStatusData(0, 0, 0, 0);
        }

        Player player = mc.player;
        return new PlayerStatusData(
                player.getHealth(),
                player.getMaxHealth(),
                player.getFoodData().getFoodLevel(),
                player.experienceLevel
        );
    }
}
