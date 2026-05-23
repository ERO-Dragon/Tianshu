package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.client.language.ClientLanguagePolicy;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.snapshot.PotionEffectData;
import com.rheinmetal.tianshu.snapshot.WorldEnvironmentData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NeoForgeEnvironmentProvider implements IEnvironmentAwarenessProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    public NeoForgeEnvironmentProvider() {
    }

    @Override
    public List<PotionEffectData> getActivePotionEffects() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return Collections.emptyList();
        List<PotionEffectData> result = new ArrayList<>();
        for (MobEffectInstance effect : mc.player.getActiveEffects()) {
            try {
                ResourceLocation effectId = effect.getEffect().unwrapKey().map(key -> key.location()).orElse(null);
                String displayName = ClientLanguagePolicy.effectDisplayName(effectId, effect.getEffect().value().getDescriptionId());
                int durationTicks = effect.getDuration();
                int amplifier = effect.getAmplifier();
                boolean beneficial = effect.getEffect().value().isBeneficial();
                result.add(new PotionEffectData(displayName, durationTicks, amplifier, beneficial));
            } catch (Exception e) {
                LOGGER.warn("提取药水效果失败: {}", e.getMessage());
            }
        }
        return result;
    }

    @Override
    public WorldEnvironmentData getWorldEnvironmentInfo() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return new WorldEnvironmentData(false, false, 0, "unknown", "unknown");
        }
        Level level = mc.level;
        long dayTime = level.getDayTime() % 24000;

        String biomeId = "unknown";
        String biomeDisplayName = "unknown";
        try {
            biomeId = level.getBiome(mc.player.blockPosition()).unwrapKey().map(key -> key.location().toString()).orElse("unknown");
            biomeDisplayName = level.getBiome(mc.player.blockPosition()).unwrapKey().map(key -> ClientLanguagePolicy.registryDisplayName(key.location(), "biome")).orElse(biomeId);
        } catch (Exception e) {
            LOGGER.warn("获取生物群系失败: {}", e.getMessage());
        }
        return new WorldEnvironmentData(level.isRaining(), level.isThundering(), dayTime, biomeId, biomeDisplayName);
    }
}
