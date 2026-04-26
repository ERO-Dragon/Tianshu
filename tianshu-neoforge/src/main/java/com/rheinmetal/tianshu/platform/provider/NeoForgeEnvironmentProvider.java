package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.snapshot.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NeoForgeEnvironmentProvider implements IEnvironmentAwarenessProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public List<NearbyEntityData> getNearbyEntities(double radius) {
        return scanNearbyEntities(radius, false);
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
            return new WorldEnvironmentData(false, false, 0, 0, "unknown", "unknown", 0, 0, 0, 0, "unknown", false);
        }

        Level level = mc.level;
        long dayTime = level.getDayTime() % 24000;
        long totalTicks = level.getGameTime();

        boolean isDaytime = dayTime >= 0 && dayTime < 12000;
        boolean isDusk = dayTime >= 12000 && dayTime < 13000;
        boolean isNighttime = dayTime >= 13000 && dayTime < 23000;
        boolean isDawn = dayTime >= 23000;

        float secondsUntilNight;
        if (isDaytime) {
            secondsUntilNight = (12000 - dayTime) / 20f;
        } else if (isNighttime) {
            secondsUntilNight = 0;
        } else {
            secondsUntilNight = 0;
        }

        float secondsUntilDay;
        if (isNighttime) {
            secondsUntilDay = (24000 - dayTime) / 20f;
        } else if (isDaytime) {
            secondsUntilDay = 0;
        } else if (isDusk) {
            secondsUntilDay = (24000 - dayTime) / 20f;
        } else {
            secondsUntilDay = (24000 - dayTime) / 20f;
        }

        float skyLight = 0;
        try {
            skyLight = level.getBrightness(LightLayer.SKY, mc.player.blockPosition());
        } catch (Exception ignored) {}

        int moonPhase = 0;
        try {
            moonPhase = level.getMoonPhase();
        } catch (Exception ignored) {}

        String difficulty = "unknown";
        try {
            difficulty = level.getDifficulty().getKey();
        } catch (Exception ignored) {}

        boolean isHardcore = false;
        try {
            isHardcore = level.getLevelData().isHardcore();
        } catch (Exception ignored) {}

        String biomeId = "unknown";
        String biomeDisplayName = "unknown";
        try {
            Holder<Biome> biomeHolder = level.getBiome(mc.player.blockPosition());
            biomeId = biomeHolder.unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("unknown");
            biomeDisplayName = biomeHolder.unwrapKey()
                    .map(key -> LocalizationHelper.safeGetDisplayName(
                            Component.translatable(key.location().toLanguageKey("biome")).getString()))
                    .orElse(biomeId);
        } catch (Exception e) {
            LOGGER.warn("获取生物群系失败: {}", e.getMessage());
        }

        return new WorldEnvironmentData(
                level.isRaining(), level.isThundering(),
                dayTime, totalTicks,
                biomeId, biomeDisplayName,
                secondsUntilNight, secondsUntilDay,
                skyLight, moonPhase,
                difficulty, isHardcore
        );
    }

    @Override
    public List<NearbyEntityData> getNearbyHostiles(double radius) {
        return scanNearbyEntities(radius, true);
    }

    @Override
    public float getSkyLightAtPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;
        try {
            return mc.level.getBrightness(LightLayer.SKY, mc.player.blockPosition());
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public MiningTargetData getCurrentMiningTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        try {
            if (mc.gameMode == null || mc.hitResult == null) return null;
            if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit)) return null;

            net.minecraft.core.BlockPos breakingPos = blockHit.getBlockPos();
            boolean isDestroying = false;
            try {
                isDestroying = mc.gameMode.isDestroying();
            } catch (Exception ignored) {}
            if (!isDestroying) return null;

            net.minecraft.world.level.block.state.BlockState bs = mc.level.getBlockState(breakingPos);
            String blockId = bs.getBlockHolder().getRegisteredName();
            String displayName = LocalizationHelper.safeGetDisplayName(bs.getBlock().getName().getString());

            boolean highValue = isHighValueBlock(blockId);

            float progress = 0f;
            try {
                progress = mc.player.hasCorrectToolForDrops(bs) ? 0.5f : 0f;
            } catch (Exception ignored) {}

            return new MiningTargetData(
                    blockId, displayName, highValue, progress,
                    new BlockPosValue(breakingPos.getX(), breakingPos.getY(), breakingPos.getZ())
            );
        } catch (Exception e) {
            LOGGER.warn("获取挖掘目标失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean isHighValueBlock(String blockId) {
        if (blockId == null) return false;
        return blockId.contains("diamond_ore")
                || blockId.contains("deepslate_diamond_ore")
                || blockId.contains("ancient_debris")
                || blockId.contains("emerald_ore")
                || blockId.contains("deepslate_emerald_ore")
                || blockId.contains("gold_ore")
                || blockId.contains("deepslate_gold_ore")
                || blockId.contains("lapis_ore")
                || blockId.contains("deepslate_lapis_ore");
    }

    private List<NearbyEntityData> scanNearbyEntities(double radius, boolean hostileOnly) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return Collections.emptyList();

        Player player = mc.player;
        Level level = mc.level;
        Vec3 playerPos = player.position();
        Vec3 playerEyePos = player.getEyePosition();
        AABB searchBox = player.getBoundingBox().inflate(radius);

        List<NearbyEntityData> result = new ArrayList<>();

        List<Entity> entities = level.getEntities((Entity) null, searchBox, e -> e != player && !(e instanceof Player));
        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!living.isAlive()) continue;

            boolean hostile = living instanceof Enemy;
            if (hostileOnly && !hostile) continue;

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

                String entityId = living.getType().toString();
                String displayName = LocalizationHelper.safeGetDisplayName(living.getName().getString());

                float health = living.getHealth();
                float maxHealth = living.getMaxHealth();

                double motionX = living.getDeltaMovement().x;
                double motionY = living.getDeltaMovement().y;
                double motionZ = living.getDeltaMovement().z;

                boolean pullingBow = false;
                try {
                    if (living.getUseItem().getItem() instanceof BowItem
                            || living.getUseItem().getItem() instanceof CrossbowItem) {
                        pullingBow = true;
                    }
                } catch (Exception ignored) {}

                boolean sneaking = living.isSteppingCarefully();

                boolean lineOfSight = checkLineOfSight(playerEyePos, living.getEyePosition(), level);

                String mainHandItemId = null;
                try {
                    net.minecraft.world.item.ItemStack mainHand = living.getItemBySlot(
                            net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                    if (!mainHand.isEmpty()) {
                        mainHandItemId = mainHand.getItemHolder().getRegisteredName();
                    }
                } catch (Exception ignored) {}

                float attackDamage = 0f;
                try {
                    attackDamage = (float) living.getAttributeValue(
                            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
                } catch (Exception ignored) {}

                float armorValue = 0f;
                try {
                    armorValue = (float) living.getAttributeValue(
                            net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
                } catch (Exception ignored) {}

                String targetUuid = null;
                if (living instanceof net.minecraft.world.entity.Mob mob) {
                    net.minecraft.world.entity.LivingEntity target = mob.getTarget();
                    if (target != null) {
                        targetUuid = target.getUUID().toString();
                    }
                }

                result.add(new NearbyEntityData(
                        entityId, living.getUUID().toString(), targetUuid, displayName,
                        relX, relY, relZ,
                        relativeAngle, distance, hostile,
                        health, maxHealth,
                        motionX, motionY, motionZ,
                        pullingBow, sneaking,
                        lineOfSight,
                        mainHandItemId, attackDamage, armorValue
                ));
            } catch (Exception e) {
                LOGGER.warn("扫描实体数据失败: {}", e.getMessage());
            }
        }

        return result;
    }

    private boolean checkLineOfSight(Vec3 from, Vec3 to, Level level) {
        try {
            net.minecraft.world.phys.BlockHitResult hitResult = level.clip(
                    new net.minecraft.world.level.ClipContext(from, to,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                            (net.minecraft.world.entity.Entity) null));
            return hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
        } catch (Exception e) {
            return false;
        }
    }
}
