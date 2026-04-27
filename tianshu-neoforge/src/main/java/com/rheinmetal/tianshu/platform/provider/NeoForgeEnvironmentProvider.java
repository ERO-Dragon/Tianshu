package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.snapshot.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NeoForgeEnvironmentProvider implements IEnvironmentAwarenessProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LOS_SAMPLE_INTERVAL = 5;

    // 核心：动态扫描半径。初始为 0，实现没有任何系统开启时的绝对 0 损耗
    private volatile double activeScanRadius = 0;

    private volatile List<NearbyEntityData> cachedHostileSnapshot = Collections.emptyList();
    private volatile List<NearbyEntityData> cachedAllEntitySnapshot = Collections.emptyList();
    private final Map<String, Boolean> losCache = new ConcurrentHashMap<>();
    private final Map<String, Long> losCacheTick = new ConcurrentHashMap<>();
    private long lastSnapshotTick = -1;

    public NeoForgeEnvironmentProvider() {
        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
    }

    /**
     * 核心接口：由上层管理者（如 TianshuClient）统一调用，更新底层的扫描框大小。
     * 上层需要自行计算当前所有开启系统（雷达、MR等）中的最大需求半径传入。
     * 如果所有系统都关闭了，传入 0 即可让底层进入休眠状态。
     */
    public void setActiveScanRadius(double radius) {
        this.activeScanRadius = Math.max(4, radius);
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        // 1. 绝对 0 损耗拦截：没系统要数据，直接跳过
        if (activeScanRadius <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long currentTick = mc.level.getGameTime();
        if (currentTick == lastSnapshotTick) return;
        lastSnapshotTick = currentTick;

        List<NearbyEntityData> allResult = new ArrayList<>();
        List<NearbyEntityData> hostileResult = new ArrayList<>();

        Player player = mc.player;
        Level level = mc.level;
        Vec3 playerPos = player.position();
        Vec3 playerEyePos = player.getEyePosition();

        // 2. 动态膨胀：框的大小精准匹配当前最大需求
        AABB searchBox = player.getBoundingBox().inflate(activeScanRadius);

        List<Entity> entities;
        try {
            entities = level.getEntities((Entity) null, searchBox, e -> e != player && !(e instanceof Player));
        } catch (Exception e) {
            LOGGER.warn("获取实体列表失败: {}", e.getMessage());
            return;
        }

        Set<String> activeUuids = new HashSet<>();
        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!living.isAlive()) continue;

            boolean hostile = living instanceof Enemy;
            String uuid = living.getUUID().toString();
            activeUuids.add(uuid);

            try {
                double relX = living.getX() - playerPos.x;
                double relY = living.getY() - playerPos.y;
                double relZ = living.getZ() - playerPos.z;
                double distance = Math.sqrt(relX * relX + relY * relY + relZ * relZ);

                // 3. 剔除 AABB 角落处稍微超出的实体
                if (distance > activeScanRadius) continue;

                double horizontalAngle = Math.toDegrees(Math.atan2(-relX, relZ));
                double relativeAngle = horizontalAngle - player.getYRot();
                while (relativeAngle > 180) relativeAngle -= 360;
                while (relativeAngle < -180) relativeAngle += 360;

                String entityId = living.getType().toString();
                String displayName = living.getName().getString();
                float health = living.getHealth();
                float maxHealth = living.getMaxHealth();

                double motionX = living.getDeltaMovement().x;
                double motionY = living.getDeltaMovement().y;
                double motionZ = living.getDeltaMovement().z;

                boolean pullingBow = false;
                net.minecraft.world.item.ItemStack useItem = living.getUseItem();
                if (!useItem.isEmpty()) {
                    pullingBow = useItem.getItem() instanceof BowItem || useItem.getItem() instanceof CrossbowItem;
                }

                // 4. 修复：干掉荒谬的怪物潜行，改为蓄力攻击判定
                boolean charging = isMobChargingAttack(living);

                boolean lineOfSight = computeLosWithSample(uuid, playerEyePos, living.getEyePosition(), level, currentTick);

                String mainHandItemId = null;
                net.minecraft.world.item.ItemStack mainHand = living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                if (!mainHand.isEmpty()) {
                    mainHandItemId = BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString();
                }

                float attackDamage = 0f;
                try {
                    attackDamage = (float) living.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
                } catch (NullPointerException ignored) {}

                float armorValue = 0f;
                try {
                    armorValue = (float) living.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
                } catch (NullPointerException ignored) {}

                String targetUuid = null;
                if (living instanceof net.minecraft.world.entity.Mob mob) {
                    net.minecraft.world.entity.LivingEntity target = mob.getTarget();
                    if (target != null) {
                        targetUuid = target.getUUID().toString();
                    }
                }

                NearbyEntityData data = new NearbyEntityData(
                        entityId, uuid, targetUuid, displayName,
                        relX, relY, relZ,
                        relativeAngle, distance, hostile,
                        health, maxHealth,
                        motionX, motionY, motionZ,
                        pullingBow, charging, // 传入修正后的变量
                        lineOfSight,
                        mainHandItemId, attackDamage, armorValue
                );
                allResult.add(data);
                if (hostile) {
                    hostileResult.add(data);
                }
            } catch (Exception e) {
                LOGGER.warn("扫描实体数据失败: {}", e.getMessage());
            }
        }

        losCache.keySet().retainAll(activeUuids);
        losCacheTick.keySet().retainAll(activeUuids);
        cachedAllEntitySnapshot = allResult;
        cachedHostileSnapshot = hostileResult;
    }

    private boolean computeLosWithSample(String uuid, Vec3 from, Vec3 to, Level level, long currentTick) {
        Long lastTick = losCacheTick.get(uuid);
        if (lastTick != null && (currentTick - lastTick) < LOS_SAMPLE_INTERVAL) {
            Boolean cached = losCache.get(uuid);
            if (cached != null) return cached;
        }
        boolean result = checkLineOfSight(from, to, level);
        losCache.put(uuid, result);
        losCacheTick.put(uuid, currentTick);
        return result;
    }

    // 5. 修复逻辑：只有苦力怕点火算蓄力，怪物没有潜行
    private boolean isMobChargingAttack(LivingEntity living) {
        if (living instanceof net.minecraft.world.entity.monster.Creeper creeper) {
            return creeper.isIgnited();
        }
        return false;
    }

    @Override
    public List<NearbyEntityData> getNearbyEntities(double radius) {
        return new ArrayList<>(cachedAllEntitySnapshot);
    }

    @Override
    public List<PotionEffectData> getActivePotionEffects() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return Collections.emptyList();
        List<PotionEffectData> result = new ArrayList<>();
        for (MobEffectInstance effect : mc.player.getActiveEffects()) {
            try {
                String effectId = effect.getEffect().unwrapKey().map(key -> key.location().toString()).orElse("unknown");
                String displayName = Component.translatable(effect.getEffect().value().getDescriptionId()).getString();
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
        if (isDaytime) { secondsUntilNight = (12000 - dayTime) / 20f; } else { secondsUntilNight = 0; }
        float secondsUntilDay;
        if (isNighttime) { secondsUntilDay = (24000 - dayTime) / 20f; } else { secondsUntilDay = (24000 - dayTime) / 20f; }

        float skyLight = 0;
        try { skyLight = level.getBrightness(LightLayer.SKY, mc.player.blockPosition()); } catch (NullPointerException ignored) {}
        int moonPhase = 0;
        try { moonPhase = level.getMoonPhase(); } catch (NullPointerException ignored) {}
        String difficulty = "unknown";
        try { difficulty = level.getDifficulty().getKey(); } catch (NullPointerException ignored) {}
        boolean isHardcore = false;
        try { isHardcore = level.getLevelData().isHardcore(); } catch (NullPointerException ignored) {}

        String biomeId = "unknown";
        String biomeDisplayName = "unknown";
        try {
            Holder<Biome> biomeHolder = level.getBiome(mc.player.blockPosition());
            biomeId = biomeHolder.unwrapKey().map(key -> key.location().toString()).orElse("unknown");
            biomeDisplayName = biomeHolder.unwrapKey().map(key -> Component.translatable(key.location().toLanguageKey("biome")).getString()).orElse(biomeId);
        } catch (Exception e) {
            LOGGER.warn("获取生物群系失败: {}", e.getMessage());
        }
        return new WorldEnvironmentData(level.isRaining(), level.isThundering(), dayTime, totalTicks, biomeId, biomeDisplayName, secondsUntilNight, secondsUntilDay, skyLight, moonPhase, difficulty, isHardcore);
    }

    @Override
    public List<NearbyEntityData> getNearbyHostiles(double radius) {
        return new ArrayList<>(cachedHostileSnapshot);
    }

    @Override
    public float getSkyLightAtPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;
        try { return mc.level.getBrightness(LightLayer.SKY, mc.player.blockPosition()); } catch (NullPointerException e) { return 0; }
    }

    @Override
    public MiningTargetData getCurrentMiningTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        try {
            if (mc.gameMode == null || mc.hitResult == null) return null;
            if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit)) return null;
            net.minecraft.core.BlockPos breakingPos = blockHit.getBlockPos();
            if (!mc.gameMode.isDestroying()) return null;
            net.minecraft.world.level.block.state.BlockState bs = mc.level.getBlockState(breakingPos);
            String blockId = bs.getBlockHolder().getRegisteredName();
            String displayName = bs.getBlock().getName().getString();
            boolean highValue = isHighValueBlock(blockId);
            float progress = 0f;
            if (mc.player.hasCorrectToolForDrops(bs)) { progress = 0.5f; }
            return new MiningTargetData(blockId, displayName, highValue, progress, new BlockPosValue(breakingPos.getX(), breakingPos.getY(), breakingPos.getZ()));
        } catch (Exception e) {
            LOGGER.warn("获取挖掘目标失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean isHighValueBlock(String blockId) {
        if (blockId == null) return false;
        return blockId.contains("diamond_ore") || blockId.contains("deepslate_diamond_ore") || blockId.contains("ancient_debris") || blockId.contains("emerald_ore") || blockId.contains("deepslate_emerald_ore") || blockId.contains("gold_ore") || blockId.contains("deepslate_gold_ore") || blockId.contains("lapis_ore") || blockId.contains("deepslate_lapis_ore");
    }

    private boolean checkLineOfSight(Vec3 from, Vec3 to, Level level) {
        try { net.minecraft.world.phys.BlockHitResult hitResult = level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, (net.minecraft.world.entity.Entity) null)); return hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS; } catch (Exception e) { return false; }
    }
}
