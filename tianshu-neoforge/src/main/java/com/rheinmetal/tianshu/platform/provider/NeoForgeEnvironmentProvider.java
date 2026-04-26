package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.snapshot.NearbyEntityData;
import com.rheinmetal.tianshu.snapshot.PotionEffectData;
import com.rheinmetal.tianshu.snapshot.WorldEnvironmentData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NeoForgeEnvironmentProvider implements IEnvironmentAwarenessProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public List<NearbyEntityData> getNearbyEntities(double radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return Collections.emptyList();

        Player player = mc.player;
        Level level = mc.level;
        Vec3 playerPos = player.position();
        AABB searchBox = player.getBoundingBox().inflate(radius);

        List<NearbyEntityData> result = new ArrayList<>();

        List<Entity> entities = level.getEntities((Entity) null, searchBox, e -> e != player && !(e instanceof Player));
        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!living.isAlive()) continue;

            try {
                double relX = living.getX() - playerPos.x;
                double relY = living.getY() - playerPos.y;
                double relZ = living.getZ() - playerPos.z;
                double distance = Math.sqrt(relX * relX + relY * relY + relZ * relZ);

                if (distance > radius) continue;

                double horizontalAngle = Math.toDegrees(Math.atan2(-relX, relZ));
                double relativeAngle = horizontalAngle - player.getYRot();
                while (relativeAngle > 180) relativeAngle -= 360;
                while (relativeAngle < -180) relativeAngle += 360;

                boolean hostile = living instanceof Enemy;

                String entityId = living.getType().toString();
                String displayName = LocalizationHelper.safeGetDisplayName(living.getName().getString());

                result.add(new NearbyEntityData(
                        entityId, displayName,
                        relX, relY, relZ,
                        relativeAngle, distance, hostile
                ));
            } catch (Exception e) {
                LOGGER.warn("扫描实体数据失败: {}", e.getMessage());
            }
        }

        return result;
    }

    @Override
    public List<PotionEffectData> getActivePotionEffects() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return Collections.emptyList();

        List<PotionEffectData> result = new ArrayList<>();
        for (MobEffectInstance effect : mc.player.getActiveEffects()) {
            try {
                String effectId = effect.getEffect().unwrapKey()
                        .map(key -> key.location().toString())
                        .orElse("unknown");
                String displayName = LocalizationHelper.safeGetDisplayName(
                        Component.translatable(effect.getEffect().value().getDescriptionId()).getString());
                int durationTicks = effect.getDuration();
                int amplifier = effect.getAmplifier();
                boolean beneficial = effect.getEffect().value().isBeneficial();

                result.add(new PotionEffectData(effectId, displayName, durationTicks, amplifier, beneficial));
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
            return new WorldEnvironmentData(false, false, 0, 0, "unknown");
        }

        Level level = mc.level;

        boolean raining = level.isRaining();
        boolean thundering = level.isThundering();
        long dayTime = level.getDayTime() % 24000;
        long totalTicks = level.getGameTime();

        String biomeId = "unknown";
        try {
            Holder<Biome> biomeHolder = level.getBiome(mc.player.blockPosition());
            biomeId = biomeHolder.unwrapKey()
                    .map(key -> LocalizationHelper.safeGetDisplayName(
                            Component.translatable(key.location().toLanguageKey("biome")).getString()))
                    .orElse("unknown");
        } catch (Exception e) {
            LOGGER.warn("获取生物群系失败: {}", e.getMessage());
        }

        return new WorldEnvironmentData(raining, thundering, dayTime, totalTicks, biomeId);
    }
}
