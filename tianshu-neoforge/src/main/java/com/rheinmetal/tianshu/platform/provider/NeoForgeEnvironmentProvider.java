package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.function.MR.MrConstants;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NeoForgeEnvironmentProvider implements IEnvironmentAwarenessProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int OCCLUSION_SAMPLE_INTERVAL = 5;

    // 核心：动态扫描半径。初始为 0，实现没有任何系统开启时的绝对 0 损耗
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
     * 核心接口：由上层管理者（如 TianshuClient）统一调用，更新底层的扫描框大小。
     * 上层需要自行计算当前所有开启系统（雷达、MR等）中的最大需求半径传入。
     * 如果所有系统都关闭了，传入 0 即可让底层进入休眠状态。
     */
    public void setActiveScanRadius(double radius) {
        this.activeScanRadius = radius <= 0.0 ? 0.0 : Math.max(4.0, radius);
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

                NearbyEntityData data = new NearbyEntityData(
                        entityId, uuid, targetUuid, displayName,
                        relX, relY, relZ,
                        relativeAngle, distance, hostile,
                        health, maxHealth,
                        motionX, motionY, motionZ,
                        pullingBow, charging,
                        occlusionVisible,
                        living.getBbHeight(), living.getEyeHeight(),
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
        Entity entity = resolveManualFocusEntity(mc, activeScanRadius > 0.0 ? activeScanRadius : MrConstants.MR_RANGE);
        return entity != null ? entity.getUUID().toString() : null;
    }

    @Override
    public String getCrosshairTargetKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Entity entity = resolveManualFocusEntity(mc, activeScanRadius > 0.0 ? activeScanRadius : MrConstants.MR_RANGE);
        if (entity != null) return entity.getUUID().toString();
        BlockHitResult blockHit = resolveManualFocusBlock(mc, activeScanRadius > 0.0 ? activeScanRadius : MrConstants.MR_RANGE);
        if (blockHit == null || blockHit.getType() == HitResult.Type.MISS) return null;
        BlockPos pos = blockHit.getBlockPos();
        BlockState blockState = mc.level.getBlockState(pos);
        if (blockState.isAir()) return null;
        return "block:" + mc.level.dimension().location() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }

    @Override
    public MrManualFocusTargetData getManualFocusTarget(double range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        double focusRange = Math.max(4.0, range);
        BlockHitResult blockHit = resolveManualFocusBlock(mc, focusRange);
        Entity entity = resolveManualFocusEntity(mc, focusRange, blockHit);
        if (entity instanceof LivingEntity living) {
            return buildManualFocusEntityTarget(mc, living);
        }
        if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
            return buildManualFocusBlockTarget(mc, blockHit);
        }
        return null;
    }

    @Override
    public MrManualFocusTargetData refreshManualFocusTarget(MrManualFocusTargetData currentTarget, double range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || currentTarget == null) return currentTarget;
        if (currentTarget.getType() == MrManualFocusTargetData.TargetType.BLOCK) {
            BlockHitResult blockHit = resolveBlockHitForFocusedBlock(mc, currentTarget, Math.max(4.0, range));
            if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
                MrManualFocusTargetData refreshed = buildManualFocusBlockTarget(mc, blockHit);
                if (refreshed != null && currentTarget.getUuid().equals(refreshed.getUuid())) return refreshed;
            }
            return rebuildBlockTargetFromStoredAnchor(mc, currentTarget);
        }
        Entity entity = resolveEntityByUuid(mc, currentTarget.getUuid());
        if (entity instanceof LivingEntity living) return buildManualFocusEntityTarget(mc, living);
        return currentTarget;
    }

    private Entity resolveEntityByUuid(Minecraft mc, String uuid) {
        if (mc.level == null || uuid == null || uuid.isEmpty()) return null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (uuid.equals(entity.getUUID().toString())) return entity;
        }
        double range = Math.max(4.0, activeScanRadius > 0.0 ? activeScanRadius : MrConstants.MR_RANGE);
        AABB searchBox = mc.player.getBoundingBox().inflate(range);
        for (Entity entity : mc.level.getEntities((Entity) null, searchBox, e -> e instanceof LivingEntity)) {
            if (uuid.equals(entity.getUUID().toString())) return entity;
        }
        return null;
    }

    private Entity resolveManualFocusEntity(Minecraft mc, double range) {
        return resolveManualFocusEntity(mc, range, null);
    }

    private Entity resolveManualFocusEntity(Minecraft mc, double range, BlockHitResult blockHitLimit) {
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

    private BlockHitResult resolveManualFocusBlock(Minecraft mc, double range) {
        if (mc.hitResult instanceof BlockHitResult blockHit && blockHit.getType() != HitResult.Type.MISS) return blockHit;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        Vec3 end = eye.add(look.x * range, look.y * range, look.z * range);
        return mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    }

    private BlockHitResult resolveBlockHitForFocusedBlock(Minecraft mc, MrManualFocusTargetData target, double range) {
        BlockPos focusedPos = parseBlockTargetPos(target);
        if (focusedPos == null) return null;
        BlockHitResult directHit = resolveManualFocusBlock(mc, range);
        if (directHit != null && directHit.getType() != HitResult.Type.MISS && focusedPos.equals(directHit.getBlockPos())) return directHit;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 anchor = new Vec3(target.getWorldX(), target.getWorldY(), target.getWorldZ());
        Vec3 direction = anchor.subtract(eye);
        double length = direction.length();
        if (length <= 0.001) return null;
        Vec3 end = eye.add(direction.scale(Math.min(range, length + 0.25) / length));
        BlockHitResult anchoredHit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (anchoredHit != null && anchoredHit.getType() != HitResult.Type.MISS && focusedPos.equals(anchoredHit.getBlockPos())) return anchoredHit;
        return null;
    }

    private MrManualFocusTargetData rebuildBlockTargetFromStoredAnchor(Minecraft mc, MrManualFocusTargetData target) {
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
        return new MrManualFocusTargetData(MrManualFocusTargetData.TargetType.BLOCK, target.getUuid(), blockId, displayName, relX, relY, relZ, target.getWorldX(), target.getWorldY(), target.getWorldZ(), distance, 0.0f, 0.0f, 0.0f, 0.0f, null, false, true, 1.0f, 0.0f, detail);
    }

    private BlockPos parseBlockTargetPos(MrManualFocusTargetData target) {
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

    private MrManualFocusTargetData buildManualFocusEntityTarget(Minecraft mc, LivingEntity living) {
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
        return new MrManualFocusTargetData(MrManualFocusTargetData.TargetType.ENTITY, uuid, entityId, living.getName().getString(), relX, relY, relZ, worldX, worldY, worldZ, distance, living.getHealth(), living.getMaxHealth(), attackDamage, armorValue, mainHandItemId, living instanceof Enemy, true, living.getBbHeight(), living.getEyeHeight(), detailText);
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

    private MrManualFocusTargetData buildManualFocusBlockTarget(Minecraft mc, BlockHitResult blockHit) {
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
        return new MrManualFocusTargetData(MrManualFocusTargetData.TargetType.BLOCK, uuid, blockId, displayName, relX, relY, relZ, hitLocation.x, hitLocation.y, hitLocation.z, distance, 0.0f, 0.0f, 0.0f, 0.0f, null, false, true, 1.0f, 0.0f, detail);
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
