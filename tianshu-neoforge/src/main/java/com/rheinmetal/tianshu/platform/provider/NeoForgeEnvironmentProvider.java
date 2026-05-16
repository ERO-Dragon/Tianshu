package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.snapshot.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NeoForgeEnvironmentProvider implements IEnvironmentAwarenessProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int OCCLUSION_SAMPLE_INTERVAL = 5;
    private static final double FALLBACK_UNARMED_ATTACK_DAMAGE = 1.0;
    private static final double FALLBACK_UNARMED_ATTACK_SPEED = 4.0;
    private static final double FULL_DRAW_BOW_DAMAGE = 6.0;
    private static final int TARGET_DAMAGE_IMMUNITY_TICKS = 10;
    private static final int MAX_BENEFICIAL_EFFECTS = 4;
    private static final int MAX_HARMFUL_EFFECTS = 2;
    private static final int MAX_POSSIBLE_DROPS = 8;
    private static final double DEFAULT_CROSSHAIR_TARGET_RANGE = 32.0;

    private volatile double activeScanRadius = 0;

    private volatile List<NearbyEntityData> cachedHostileSnapshot = Collections.emptyList();
    private volatile List<NearbyEntityData> cachedAllEntitySnapshot = Collections.emptyList();
    private final Map<String, Boolean> occlusionVisibleCache = new ConcurrentHashMap<>();
    private final Map<String, Long> occlusionVisibleCacheTick = new ConcurrentHashMap<>();
    private long lastSnapshotTick = -1;

    public NeoForgeEnvironmentProvider() {
        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
    }

    /**
     * 核心接口：由上层管理者（�?TianshuClient）统一调用，更新底层的扫描框大小�?
     * 上层需要自行计算当前所有开启系统（雷达、MR等）中的最大需求半径传入�?
     * 如果所有系统都关闭了，传入 0 即可让底层进入休眠状态�?
     */
    public void setActiveScanRadius(double radius) {
        this.activeScanRadius = radius <= 0.0 ? 0.0 : Math.max(4.0, radius);
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        // 1. 绝对 0 损耗拦截：没系统要数据，直接跳�?
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

        // 2. 动态膨胀：框的大小精准匹配当前最大需�?
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

                // 4. 修复：干掉荒谬的怪物潜行，改为蓄力攻击判�?
                boolean charging = isMobChargingAttack(living);

                boolean occlusionVisible = computeOcclusionVisibleWithSample(uuid, playerEyePos, living, level, currentTick);

                String mainHandItemId = null;
                net.minecraft.world.item.ItemStack mainHand = living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                if (!mainHand.isEmpty()) {
                    mainHandItemId = BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString();
                }

                float attackDamage = 0f;
                {
                    var attr = living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
                    if (attr != null) attackDamage = (float) attr.getValue();
                }

                float armorValue = 0f;
                {
                    var attr = living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
                    if (attr != null) armorValue = (float) attr.getValue();
                }

                String targetUuid = null;
                if (living instanceof net.minecraft.world.entity.Mob mob) {
                    net.minecraft.world.entity.LivingEntity target = mob.getTarget();
                    if (target != null) {
                        targetUuid = target.getUUID().toString();
                    }
                }

                EntityExplanationData explanationData = buildEntityExplanationData(mc, living);
                String detailText = explanationData != null ? buildEnemyFocusDetailText(explanationData) : null;

                NearbyEntityData data = new NearbyEntityData(
                        entityId, uuid, targetUuid, displayName,
                        relX, relY, relZ,
                        relativeAngle, distance, hostile,
                        health, maxHealth,
                        motionX, motionY, motionZ,
                        pullingBow, charging,
                        occlusionVisible,
                        living.getBbHeight(), living.getEyeHeight(),
                        mainHandItemId, attackDamage, armorValue,
                        detailText, explanationData
                );
                allResult.add(data);
                if (hostile) {
                    hostileResult.add(data);
                }
            } catch (Exception e) {
                LOGGER.warn("扫描实体数据失败: {}", e.getMessage());
            }
        }

        occlusionVisibleCache.keySet().retainAll(activeUuids);
        occlusionVisibleCacheTick.keySet().retainAll(activeUuids);
        cachedAllEntitySnapshot = allResult;
        cachedHostileSnapshot = hostileResult;
    }

    private boolean computeOcclusionVisibleWithSample(String uuid, Vec3 from, LivingEntity living, Level level, long currentTick) {
        Long lastTick = occlusionVisibleCacheTick.get(uuid);
        if (lastTick != null && (currentTick - lastTick) < OCCLUSION_SAMPLE_INTERVAL) {
            Boolean cached = occlusionVisibleCache.get(uuid);
            if (cached != null) return cached;
        }
        boolean result = checkOcclusionVisible(from, living, level);
        occlusionVisibleCache.put(uuid, result);
        occlusionVisibleCacheTick.put(uuid, currentTick);
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

    @Override
    public String getCrosshairTargetEntityUuid() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult instanceof EntityHitResult entityHit) {
            return entityHit.getEntity().getUUID().toString();
        }
        if (mc.crosshairPickEntity != null) {
            return mc.crosshairPickEntity.getUUID().toString();
        }
        Entity entity = resolveTargetEntity(mc, currentTargetRange());
        return entity != null ? entity.getUUID().toString() : null;
    }

    @Override
    public String getCrosshairTargetKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Entity entity = resolveTargetEntity(mc, currentTargetRange());
        if (entity != null) return entity.getUUID().toString();
        BlockHitResult blockHit = resolveTargetBlock(mc, currentTargetRange());
        if (blockHit == null || blockHit.getType() == HitResult.Type.MISS) return null;
        BlockPos pos = blockHit.getBlockPos();
        BlockState blockState = mc.level.getBlockState(pos);
        if (blockState.isAir()) return null;
        return "block:" + mc.level.dimension().location() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }

    @Override
    public FocusTargetData getFocusTarget(double range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        double focusRange = Math.max(4.0, range);
        BlockHitResult blockHit = resolveTargetBlock(mc, focusRange);
        Entity entity = resolveTargetEntity(mc, focusRange, blockHit);
        if (entity instanceof LivingEntity living) {
            return buildEntityFocusTarget(mc, living);
        }
        if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
            return buildBlockFocusTarget(mc, blockHit);
        }
        return null;
    }

    @Override
    public FocusTargetData refreshFocusTarget(FocusTargetData currentTarget, double range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || currentTarget == null) return currentTarget;
        if (currentTarget.getType() == FocusTargetData.TargetType.BLOCK) {
            return rebuildBlockTargetFromStoredAnchor(mc, currentTarget);
        }
        Entity entity = resolveEntityByUuid(mc, currentTarget.getUuid());
        if (entity instanceof LivingEntity living) return buildEntityFocusTarget(mc, living);
        return currentTarget;
    }

    private double currentTargetRange() {
        return Math.max(4.0, activeScanRadius > 0.0 ? activeScanRadius : DEFAULT_CROSSHAIR_TARGET_RANGE);
    }

    private Entity resolveEntityByUuid(Minecraft mc, String uuid) {
        if (mc.level == null || uuid == null || uuid.isEmpty()) return null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (uuid.equals(entity.getUUID().toString())) return entity;
        }
        double range = currentTargetRange();
        AABB searchBox = mc.player.getBoundingBox().inflate(range);
        for (Entity entity : mc.level.getEntities((Entity) null, searchBox, e -> e instanceof LivingEntity)) {
            if (uuid.equals(entity.getUUID().toString())) return entity;
        }
        return null;
    }

    private Entity resolveTargetEntity(Minecraft mc, double range) {
        return resolveTargetEntity(mc, range, null);
    }

    private Entity resolveTargetEntity(Minecraft mc, double range, BlockHitResult blockHitLimit) {
        if (mc.player == null || mc.level == null) return null;
        if (mc.hitResult instanceof EntityHitResult entityHit) return entityHit.getEntity();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        double maxEntityDistance = range;
        if (blockHitLimit != null && blockHitLimit.getType() != HitResult.Type.MISS) {
            maxEntityDistance = Math.max(0.0, eye.distanceTo(blockHitLimit.getLocation()) - 0.08);
        }
        AABB searchBox = mc.player.getBoundingBox().expandTowards(look.scale(maxEntityDistance)).inflate(0.35);
        Entity bestEntity = null;
        double bestDistance = maxEntityDistance * maxEntityDistance;
        double bestAimError = 0.018;
        for (Entity entity : mc.level.getEntities((Entity) null, searchBox, e -> e != mc.player && !(e instanceof Player) && e instanceof LivingEntity)) {
            AABB box = entity.getBoundingBox().inflate(0.12);
            Optional<Vec3> hit = box.clip(eye, eye.add(look.x * maxEntityDistance, look.y * maxEntityDistance, look.z * maxEntityDistance));
            if (hit.isEmpty()) continue;
            double distance = eye.distanceToSqr(hit.get());
            Vec3 toHit = hit.get().subtract(eye);
            double length = toHit.length();
            if (length <= 0.001) continue;
            Vec3 normalized = toHit.scale(1.0 / length);
            double aimError = 1.0 - normalized.dot(look);
            if (aimError > bestAimError) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestEntity = entity;
                bestAimError = Math.max(0.004, aimError + 0.002);
            }
        }
        return bestEntity;
    }

    private BlockHitResult resolveTargetBlock(Minecraft mc, double range) {
        if (mc.hitResult instanceof BlockHitResult blockHit && blockHit.getType() != HitResult.Type.MISS) return blockHit;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        Vec3 end = eye.add(look.x * range, look.y * range, look.z * range);
        return mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    }

    private FocusTargetData rebuildBlockTargetFromStoredAnchor(Minecraft mc, FocusTargetData target) {
        BlockPos pos = parseBlockTargetPos(target);
        if (pos == null) return target;
        BlockState blockState = mc.level.getBlockState(pos);
        if (blockState.isAir()) return target;
        String blockId = blockState.getBlockHolder().getRegisteredName();
        String displayName = LocalizationHelper.safeGetDisplayName(blockState.getBlock().getName().getString());
        Vec3 playerPos = mc.player.position();
        double relX = target.getWorldX() - playerPos.x;
        double relY = target.getWorldY() - playerPos.y;
        double relZ = target.getWorldZ() - playerPos.z;
        double distance = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
        String detail = buildBlockDetailText(mc, pos, blockState, blockId, displayName, distance);
        return new FocusTargetData(FocusTargetData.TargetType.BLOCK, target.getUuid(), blockId, displayName, relX, relY, relZ, target.getWorldX(), target.getWorldY(), target.getWorldZ(), distance, 0.0f, 0.0f, 0.0f, 0.0f, null, false, true, 1.0f, 0.0f, detail);
    }

    private BlockPos parseBlockTargetPos(FocusTargetData target) {
        if (target == null || target.getUuid() == null || !target.getUuid().startsWith("block:")) return null;
        String[] parts = target.getUuid().split(":");
        if (parts.length < 6) return null;
        try {
            int x = Integer.parseInt(parts[parts.length - 3]);
            int y = Integer.parseInt(parts[parts.length - 2]);
            int z = Integer.parseInt(parts[parts.length - 1]);
            return new BlockPos(x, y, z);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private FocusTargetData buildEntityFocusTarget(Minecraft mc, LivingEntity living) {
        Player player = mc.player;
        Vec3 playerPos = player.position();
        double worldX = living.getX();
        double worldY = living.getY() + living.getEyeHeight() * 0.8;
        double worldZ = living.getZ();
        double relX = worldX - playerPos.x;
        double relY = worldY - playerPos.y;
        double relZ = worldZ - playerPos.z;
        double distance = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
        String entityId = living.getType().toString();
        String uuid = living.getUUID().toString();
        String mainHandItemId = null;
        net.minecraft.world.item.ItemStack mainHand = living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        if (!mainHand.isEmpty()) {
            mainHandItemId = BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString();
        }
        float attackDamage = 0f;
        var attackAttr = living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        if (attackAttr != null) attackDamage = (float) attackAttr.getValue();
        float armorValue = 0f;
        var armorAttr = living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        if (armorAttr != null) armorValue = (float) armorAttr.getValue();
        EntityExplanationData explanationData = buildEntityExplanationData(mc, living);
        String detailText = explanationData != null ? buildEnemyFocusDetailText(explanationData) : buildBasicEntityDetailText(living, entityId, uuid, distance, attackDamage, armorValue, relX, relY, relZ, mainHandItemId);
        return new FocusTargetData(FocusTargetData.TargetType.ENTITY, uuid, entityId, living.getName().getString(), relX, relY, relZ, worldX, worldY, worldZ, distance, living.getHealth(), living.getMaxHealth(), attackDamage, armorValue, mainHandItemId, living instanceof Enemy, true, living.getBbHeight(), living.getEyeHeight(), detailText, explanationData);
    }

    private String buildBasicEntityDetailText(LivingEntity living, String entityId, String uuid, double distance, float attackDamage, float armorValue, double relX, double relY, double relZ, String mainHandItemId) {
        String detailText = "ENTITY " + living.getName().getString()
                + "\nID " + entityId
                + "\nUUID " + shortUuid(uuid)
                + "\nHP " + String.format("%.1f/%.1f", living.getHealth(), living.getMaxHealth())
                + "  DIST " + String.format("%.1fm", distance)
                + "\nATK " + String.format("%.1f", attackDamage)
                + "  ARM " + String.format("%.1f", armorValue)
                + "  TYPE " + (living instanceof Enemy ? "HOSTILE" : "NEUTRAL")
                + "\nPOS " + String.format("%.1f %.1f %.1f", relX, relY, relZ);
        if (mainHandItemId != null && !mainHandItemId.isEmpty()) {
            detailText += "\nMAIN " + mainHandItemId;
        }
        return detailText;
    }

    private String buildEnemyFocusDetailText(EntityExplanationData data) {
        StringBuilder detail = new StringBuilder();
        detail.append("名称 ").append(data.getName());
        detail.append("\n类型 ").append(data.getTypeLabel());
        if (data.isInvisible()) detail.append("\n状态 隐身");
        if (data.getMovementSpeedLabel() != null && !data.getMovementSpeedLabel().isBlank()) {
            detail.append("\n移动速度 ").append(data.getMovementSpeedLabel());
        }
        appendCombatEstimate(detail, "近战测算", data.getMeleeEstimate());
        appendCombatEstimate(detail, "远程更优", data.getRangedEstimate());
        appendEffects(detail, "增益效果", data.getBeneficialEffects());
        appendEffects(detail, "负面效果", data.getHarmfulEffects());
        if (!data.getPossibleDrops().isEmpty()) {
            detail.append("\n可能掉落 ").append(String.join("、", data.getPossibleDrops()));
        }
        if (data.getFollowRange() != null && data.getFollowRange() > 0.0) {
            detail.append("\n脱离锁定 ").append(formatOneDecimal(data.getFollowRange())).append("格外");
        }
        return detail.toString();
    }

    private void appendCombatEstimate(StringBuilder detail, String label, EntityExplanationData.CombatEstimateData estimate) {
        if (estimate == null) return;
        detail.append("\n").append(label).append(" ").append(estimate.getWeaponName()).append(" ");
        if ("bow".equals(estimate.getMode())) {
            detail.append("满弓").append(estimate.getHitCount()).append("箭");
        } else {
            detail.append(estimate.getHitCount()).append("次满蓄力攻击");
        }
        detail.append("，最快约").append(formatOneDecimal(estimate.getFastestSeconds())).append("秒");
    }

    private void appendEffects(StringBuilder detail, String label, List<EntityExplanationData.EffectData> effects) {
        if (effects == null || effects.isEmpty()) return;
        List<String> texts = new ArrayList<>();
        for (EntityExplanationData.EffectData effect : effects) {
            texts.add(effect.getDisplayName() + toRomanNumeral(effect.getAmplifier() + 1) + " " + effect.getDurationSeconds() + "秒");
        }
        detail.append("\n").append(label).append(" ").append(String.join("、", texts));
    }

    private String toRomanNumeral(int level) {
        return switch (level) {
            case 1 -> " I";
            case 2 -> " II";
            case 3 -> " III";
            case 4 -> "";
            case 5 -> " V";
            default -> " " + level;
        };
    }

    private String formatOneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private EntityExplanationData buildEntityExplanationData(Minecraft mc, LivingEntity living) {
        if (!(living instanceof Enemy) || mc.player == null) return null;
        double movementSpeed = getAttributeValue(living, Attributes.MOVEMENT_SPEED, 0.0);
        CombatCandidate bestMelee = findBestMeleeCandidate(mc.player, living);
        EntityExplanationData.CombatEstimateData meleeEstimate = bestMelee != null ? bestMelee.toEstimateData("melee") : null;
        EntityExplanationData.CombatEstimateData rangedEstimate = buildRangedEstimateIfBetter(mc.player, living, bestMelee);
        List<EntityExplanationData.EffectData> beneficialEffects = collectEffects(living, true, MAX_BENEFICIAL_EFFECTS);
        List<EntityExplanationData.EffectData> harmfulEffects = collectEffects(living, false, MAX_HARMFUL_EFFECTS);
        List<String> possibleDrops = collectPossibleDrops(living);
        Double followRange = getNullableAttributeValue(living, Attributes.FOLLOW_RANGE);
        return new EntityExplanationData(
                LocalizationHelper.safeGetDisplayName(living.getName().getString()),
                "敌人",
                living.isInvisible(),
                movementSpeed,
                describeMovementSpeed(movementSpeed),
                meleeEstimate,
                rangedEstimate,
                beneficialEffects,
                harmfulEffects,
                possibleDrops,
                followRange
        );
    }

    private CombatCandidate findBestMeleeCandidate(Player player, LivingEntity target) {
        CombatCandidate best = null;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (!isMeleeCandidate(stack)) continue;
            CombatCandidate candidate = buildMeleeCandidate(player, target, stack);
            if (candidate == null) continue;
            if (best == null || candidate.dps > best.dps) best = candidate;
        }
        if (best != null) return best;
        return buildMeleeCandidate(player, target, ItemStack.EMPTY);
    }

    private boolean isMeleeCandidate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem) return false;
        return hasItemAttributeModifier(stack, "attack_damage") || hasItemAttributeModifier(stack, "attack_speed");
    }

    private CombatCandidate buildMeleeCandidate(Player player, LivingEntity target, ItemStack stack) {
        double attackDamage = getAttributeValue(player, Attributes.ATTACK_DAMAGE, FALLBACK_UNARMED_ATTACK_DAMAGE) + getItemAttributeModifier(stack, "attack_damage");
        double attackSpeed = getAttributeValue(player, Attributes.ATTACK_SPEED, FALLBACK_UNARMED_ATTACK_SPEED) + getItemAttributeModifier(stack, "attack_speed");
        if (attackSpeed <= 0.0) attackSpeed = FALLBACK_UNARMED_ATTACK_SPEED;
        attackDamage += getStrengthWeaknessBonus(player);
        attackDamage += getWeaponDamageEnchantmentBonus(stack, target);
        double effectiveDamage = applyTargetDamageReductions(Math.max(0.0, attackDamage), target);
        if (effectiveDamage <= 0.0) return null;
        double health = Math.max(0.0, target.getHealth() + target.getAbsorptionAmount());
        int hits = Math.max(1, (int) Math.ceil(health / effectiveDamage));
        int effectiveIntervalTicks = estimateFullStrengthMeleeIntervalTicks(attackSpeed);
        double seconds = estimateFullStrengthMeleeSeconds(hits, effectiveIntervalTicks);
        String weaponName = stack.isEmpty() ? "空手" : LocalizationHelper.safeGetDisplayName(stack.getHoverName().getString());
        String weaponId = stack.isEmpty() ? "minecraft:empty_hand" : stack.getItemHolder().getRegisteredName();
        double dps = effectiveDamage * 20.0 / effectiveIntervalTicks;
        return new CombatCandidate(weaponName, weaponId, hits, seconds, effectiveDamage, attackSpeed, dps);
    }

    private int estimateFullStrengthMeleeIntervalTicks(double attackSpeed) {
        double safeAttackSpeed = attackSpeed > 0.0 ? attackSpeed : FALLBACK_UNARMED_ATTACK_SPEED;
        int playerCooldownTicks = Math.max(1, (int) Math.ceil(20.0 / safeAttackSpeed));
        return Math.max(playerCooldownTicks, TARGET_DAMAGE_IMMUNITY_TICKS);
    }

    private double estimateFullStrengthMeleeSeconds(int hits, int effectiveIntervalTicks) {
        if (hits <= 1) return 0.0;
        return (hits - 1) * Math.max(1, effectiveIntervalTicks) / 20.0;
    }

    private EntityExplanationData.CombatEstimateData buildRangedEstimateIfBetter(Player player, LivingEntity target, CombatCandidate bestMelee) {
        if (bestMelee == null || !hasArrowForBow(player)) return null;
        ItemStack bestBow = findBestBow(player);
        if (bestBow == null || bestBow.isEmpty()) return null;
        int power = getEnchantmentLevel(bestBow, "power");
        double rawDamage = FULL_DRAW_BOW_DAMAGE;
        if (power > 0) rawDamage += 0.5 * power + 0.5;
        double effectiveDamage = applyTargetDamageReductions(rawDamage, target);
        if (effectiveDamage <= 0.0) return null;
        double health = Math.max(0.0, target.getHealth() + target.getAbsorptionAmount());
        int arrows = Math.max(1, (int) Math.ceil(health / effectiveDamage));
        double seconds = arrows;
        if (seconds >= bestMelee.fastestSeconds) return null;
        String weaponName = LocalizationHelper.safeGetDisplayName(bestBow.getHoverName().getString());
        String weaponId = bestBow.getItemHolder().getRegisteredName();
        return new EntityExplanationData.CombatEstimateData(weaponName, weaponId, "bow", arrows, seconds, effectiveDamage, 1.0);
    }

    private ItemStack findBestBow(Player player) {
        ItemStack bestBow = ItemStack.EMPTY;
        int bestPower = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BowItem)) continue;
            int power = getEnchantmentLevel(stack, "power");
            if (power > bestPower) {
                bestPower = power;
                bestBow = stack;
            }
        }
        return bestBow;
    }

    private boolean hasArrowForBow(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack != null && !stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.ArrowItem) return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (stack != null && !stack.isEmpty() && stack.getItem() instanceof BowItem && getEnchantmentLevel(stack, "infinity") > 0) return true;
        }
        return false;
    }

    private double applyTargetDamageReductions(double damage, LivingEntity target) {
        double armor = getAttributeValue(target, Attributes.ARMOR, 0.0);
        double toughness = getAttributeValue(target, Attributes.ARMOR_TOUGHNESS, 0.0);
        double armorReduction = Math.min(20.0, Math.max(armor / 5.0, armor - damage / (2.0 + toughness / 4.0))) / 25.0;
        double result = damage * (1.0 - armorReduction);
        int resistance = getEffectLevel(target, "resistance");
        if (resistance > 0) {
            result *= Math.max(0.0, 1.0 - resistance * 0.2);
        }
        int protection = getArmorEnchantmentLevel(target, "protection");
        if (protection > 0) {
            result *= Math.max(0.0, 1.0 - Math.min(20, protection) * 0.04);
        }
        return Math.max(0.0, result);
    }

    private List<EntityExplanationData.EffectData> collectEffects(LivingEntity living, boolean beneficial, int limit) {
        List<EntityExplanationData.EffectData> effects = new ArrayList<>();
        for (MobEffectInstance effect : living.getActiveEffects()) {
            try {
                if (effect.getEffect().value().isBeneficial() != beneficial) continue;
                String effectId = effect.getEffect().unwrapKey().map(key -> key.location().toString()).orElse("unknown");
                String displayName = Component.translatable(effect.getEffect().value().getDescriptionId()).getString();
                int seconds = Math.max(0, effect.getDuration() / 20);
                effects.add(new EntityExplanationData.EffectData(effectId, LocalizationHelper.safeGetDisplayName(displayName), effect.getAmplifier(), seconds, beneficial));
            } catch (Exception e) {
                LOGGER.warn("提取实体药水效果失败: {}", e.getMessage());
            }
        }
        effects.sort(Comparator.comparingInt(EntityExplanationData.EffectData::getAmplifier).reversed().thenComparing(Comparator.comparingInt(EntityExplanationData.EffectData::getDurationSeconds).reversed()));
        if (effects.size() <= limit) return effects;
        return new ArrayList<>(effects.subList(0, limit));
    }

    private List<String> collectPossibleDrops(LivingEntity living) {
        LinkedHashSet<String> drops = new LinkedHashSet<>();
        try {
            ResourceLocation lootTable = living.getType().getDefaultLootTable().location();
            addKnownDropHints(lootTable, drops);
        } catch (Exception ignored) {}
        return new ArrayList<>(drops);
    }

    private void addKnownDropHints(ResourceLocation lootTable, Set<String> drops) {
        String path = lootTable.toString();
        if (path.endsWith("entities/zombie")) addDrops(drops, "腐肉", "铁锭", "胡萝�?, "马铃�?);
        else if (path.endsWith("entities/skeleton")) addDrops(drops, "骨头", "�?, "�?);
        else if (path.endsWith("entities/creeper")) addDrops(drops, "火药", "音乐唱片");
        else if (path.endsWith("entities/spider")) addDrops(drops, "�?, "蜘蛛�?);
        else if (path.endsWith("entities/enderman")) addDrops(drops, "末影珍珠");
        else if (path.endsWith("entities/witch")) addDrops(drops, "玻璃�?, "萤石�?, "红石�?, "火药", "蜘蛛�?, "�?, "木棍");
        else if (path.endsWith("entities/slime")) addDrops(drops, "黏液�?);
        else if (path.endsWith("entities/blaze")) addDrops(drops, "烈焰�?);
        else if (path.endsWith("entities/ghast")) addDrops(drops, "恶魂之泪", "火药");
        else if (path.endsWith("entities/guardian") || path.endsWith("entities/elder_guardian")) addDrops(drops, "海晶碎片", "海晶砂粒", "生鳕�?);
        else if (path.endsWith("entities/drowned")) addDrops(drops, "腐肉", "铜锭", "三叉�?, "鹦鹉螺壳");
        else if (path.endsWith("entities/husk")) addDrops(drops, "腐肉", "铁锭", "胡萝�?, "马铃�?);
        else if (path.endsWith("entities/stray")) addDrops(drops, "骨头", "�?, "迟缓之箭", "�?);
        while (drops.size() > MAX_POSSIBLE_DROPS) {
            Iterator<String> iterator = drops.iterator();
            String last = null;
            while (iterator.hasNext()) last = iterator.next();
            if (last == null) break;
            drops.remove(last);
        }
    }

    private void addDrops(Set<String> drops, String... values) {
        drops.addAll(Arrays.asList(values));
    }

    private String describeMovementSpeed(double speed) {
        if (speed <= 0.0) return null;
        if (speed < 0.2) return "�?;
        if (speed < 0.3) return "普�?;
        if (speed < 0.4) return "�?;
        return "很快";
    }

    private double getStrengthWeaknessBonus(Player player) {
        double bonus = 0.0;
        int strength = getEffectLevel(player, "strength");
        if (strength > 0) bonus += 3.0 * strength;
        int weakness = getEffectLevel(player, "weakness");
        if (weakness > 0) bonus -= 4.0 * weakness;
        return bonus;
    }

    private int getEffectLevel(LivingEntity living, String effectName) {
        for (MobEffectInstance effect : living.getActiveEffects()) {
            String id = effect.getEffect().unwrapKey().map(key -> key.location().toString()).orElse("");
            if (id.endsWith(effectName)) return effect.getAmplifier() + 1;
        }
        return 0;
    }

    private double getWeaponDamageEnchantmentBonus(ItemStack stack, LivingEntity target) {
        if (stack == null || stack.isEmpty()) return 0.0;
        int sharpness = getEnchantmentLevel(stack, "sharpness");
        double bonus = sharpness > 0 ? 0.5 * sharpness + 0.5 : 0.0;
        int smite = getEnchantmentLevel(stack, "smite");
        if (smite > 0 && isUndeadTarget(target)) bonus += 2.5 * smite;
        int bane = getEnchantmentLevel(stack, "bane_of_arthropods");
        if (bane > 0 && isArthropodTarget(target)) bonus += 2.5 * bane;
        return bonus;
    }

    private boolean isUndeadTarget(LivingEntity target) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        return id.contains("zombie")
                || id.contains("skeleton")
                || id.contains("wither")
                || id.contains("phantom")
                || id.contains("drowned")
                || id.contains("husk")
                || id.contains("stray")
                || id.contains("zombified");
    }

    private boolean isArthropodTarget(LivingEntity target) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        return id.contains("spider")
                || id.contains("silverfish")
                || id.contains("endermite")
                || id.contains("bee");
    }

    private int getArmorEnchantmentLevel(LivingEntity living, String enchantmentName) {
        int level = 0;
        for (ItemStack stack : living.getArmorSlots()) {
            level += getEnchantmentLevel(stack, enchantmentName);
        }
        return level;
    }

    private int getEnchantmentLevel(ItemStack stack, String enchantmentName) {
        if (stack == null || stack.isEmpty()) return 0;
        try {
            ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
            if (enchantments == null) return 0;
            int level = 0;
            for (var entry : enchantments.entrySet()) {
                String id = entry.getKey().unwrapKey().map(key -> key.location().toString()).orElse("");
                if (id.endsWith(enchantmentName)) level = Math.max(level, entry.getIntValue());
            }
            return level;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean hasItemAttributeModifier(ItemStack stack, String attributeName) {
        return getItemAttributeModifier(stack, attributeName) != 0.0;
    }

    private double getItemAttributeModifier(ItemStack stack, String attributeName) {
        if (stack == null || stack.isEmpty()) return 0.0;
        try {
            var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (modifiers == null) return 0.0;
            double value = 0.0;
            for (var modifier : modifiers.modifiers()) {
                String attrId = modifier.attribute().unwrapKey().map(key -> key.location().toString()).orElse("");
                if (!attrId.endsWith(attributeName)) continue;
                value += modifier.modifier().amount();
            }
            return value;
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private double getAttributeValue(LivingEntity living, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double fallback) {
        var instance = living.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    private Double getNullableAttributeValue(LivingEntity living, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var instance = living.getAttribute(attribute);
        return instance == null ? null : instance.getValue();
    }

    private static final class CombatCandidate {
        final String weaponName;
        final String weaponId;
        final int hitCount;
        final double fastestSeconds;
        final double effectiveDamage;
        final double attackSpeed;
        final double dps;

        CombatCandidate(String weaponName, String weaponId, int hitCount, double fastestSeconds, double effectiveDamage, double attackSpeed, double dps) {
            this.weaponName = weaponName;
            this.weaponId = weaponId;
            this.hitCount = hitCount;
            this.fastestSeconds = fastestSeconds;
            this.effectiveDamage = effectiveDamage;
            this.attackSpeed = attackSpeed;
            this.dps = dps;
        }

        EntityExplanationData.CombatEstimateData toEstimateData(String mode) {
            return new EntityExplanationData.CombatEstimateData(weaponName, weaponId, mode, hitCount, fastestSeconds, effectiveDamage, attackSpeed);
        }
    }

    private String buildBlockDetailText(Minecraft mc, BlockPos pos, BlockState blockState, String blockId, String displayName, double distance) {
        StringBuilder detail = new StringBuilder();
        detail.append("BLOCK ").append(displayName);
        detail.append("\nID ").append(blockId);
        detail.append("\nPOS ").append(pos.getX()).append(" ").append(pos.getY()).append(" ").append(pos.getZ());
        detail.append("  DIST ").append(String.format("%.1fm", distance));
        for (Property<?> property : blockState.getProperties()) {
            Comparable<?> value = blockState.getValue(property);
            detail.append("\nSTATE ").append(property.getName()).append("=").append(value);
        }
        BlockEntity blockEntity = mc.level.getBlockEntity(pos);
        if (blockEntity != null) {
            detail.append("\nBLOCK_ENTITY YES");
        }
        return detail.toString();
    }

    private FocusTargetData buildBlockFocusTarget(Minecraft mc, BlockHitResult blockHit) {
        BlockPos pos = blockHit.getBlockPos();
        BlockState blockState = mc.level.getBlockState(pos);
        if (blockState.isAir()) return null;
        String blockId = blockState.getBlockHolder().getRegisteredName();
        String displayName = LocalizationHelper.safeGetDisplayName(blockState.getBlock().getName().getString());
        Vec3 hitLocation = blockHit.getLocation();
        Vec3 playerPos = mc.player.position();
        double relX = hitLocation.x - playerPos.x;
        double relY = hitLocation.y - playerPos.y;
        double relZ = hitLocation.z - playerPos.z;
        double distance = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
        String uuid = "block:" + mc.level.dimension().location() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        String detail = buildBlockDetailText(mc, pos, blockState, blockId, displayName, distance);
        return new FocusTargetData(FocusTargetData.TargetType.BLOCK, uuid, blockId, displayName, relX, relY, relZ, hitLocation.x, hitLocation.y, hitLocation.z, distance, 0.0f, 0.0f, 0.0f, 0.0f, null, false, true, 1.0f, 0.0f, detail);
    }

    private String shortUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return "unknown";
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8);
    }

    private boolean isHighValueBlock(String blockId) {
        if (blockId == null) return false;
        return blockId.contains("diamond_ore") || blockId.contains("deepslate_diamond_ore") || blockId.contains("ancient_debris") || blockId.contains("emerald_ore") || blockId.contains("deepslate_emerald_ore") || blockId.contains("gold_ore") || blockId.contains("deepslate_gold_ore") || blockId.contains("lapis_ore") || blockId.contains("deepslate_lapis_ore");
    }

    private boolean checkOcclusionVisible(Vec3 from, LivingEntity living, Level level) {
        try {
            for (Vec3 samplePoint : buildOcclusionSamplePoints(living)) {
                if (isOcclusionRayClear(from, samplePoint, level)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private List<Vec3> buildOcclusionSamplePoints(LivingEntity living) {
        AABB box = living.getBoundingBox();
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;
        double height = Math.max(0.1, box.getYsize());
        List<Vec3> points = new ArrayList<>(3);
        points.add(living.getEyePosition());
        points.add(new Vec3(centerX, box.minY + height * 0.55, centerZ));
        points.add(new Vec3(centerX, box.minY + height * 0.18, centerZ));
        return points;
    }

    private boolean isOcclusionRayClear(Vec3 from, Vec3 to, Level level) {
        net.minecraft.world.phys.BlockHitResult hitResult = level.clip(new ClipContext(
                from,
                to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                Minecraft.getInstance().player
        ));
        return hitResult.getType() == HitResult.Type.MISS;
    }
}
