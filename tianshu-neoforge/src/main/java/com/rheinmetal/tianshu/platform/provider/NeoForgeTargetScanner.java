package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.ITargetScannerProvider;
import com.rheinmetal.tianshu.snapshot.CrosshairTargetData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class NeoForgeTargetScanner implements ITargetScannerProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_NBT_NODES = 30;

    @Override
    public CrosshairTargetData getCrosshairTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return CrosshairTargetData.forVoid("unknown", "未知");
        }

        HitResult hitResult = mc.hitResult;
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
            return resolveBiomeFallback(mc);
        }

        if (hitResult instanceof BlockHitResult blockHit) {
            return resolveBlockTarget(mc, blockHit);
        }

        if (hitResult instanceof EntityHitResult entityHit) {
            return resolveEntityTarget(entityHit);
        }

        return resolveBiomeFallback(mc);
    }

    private CrosshairTargetData resolveBlockTarget(Minecraft mc, BlockHitResult blockHit) {
        BlockPos pos = blockHit.getBlockPos();
        BlockState blockState = mc.level.getBlockState(pos);

        String blockId = blockState.getBlockHolder().getRegisteredName();
        String displayName = LocalizationHelper.safeGetDisplayName(blockState.getBlock().getName().getString());

        Map<String, String> stateProperties = new LinkedHashMap<>();
        for (Property<?> property : blockState.getProperties()) {
            Comparable<?> value = blockState.getValue(property);
            stateProperties.put(property.getName(), value.toString());
        }

        BlockEntity blockEntity = mc.level.getBlockEntity(pos);
        boolean hasBlockEntity = blockEntity != null;

        Map<String, String> blockEntityData = Collections.emptyMap();
        if (hasBlockEntity) {
            blockEntityData = extractBlockEntityData(blockEntity, mc);
        }

        return CrosshairTargetData.forBlock(blockId, displayName, stateProperties, hasBlockEntity, blockEntityData);
    }

    private Map<String, String> extractBlockEntityData(BlockEntity blockEntity, Minecraft mc) {
        Map<String, String> data = new LinkedHashMap<>();
        try {
            CompoundTag tag = blockEntity.saveWithoutMetadata(mc.level.registryAccess());
            if (tag != null && !tag.isEmpty()) {
                tag.remove("x");
                tag.remove("y");
                tag.remove("z");
                flattenTag(tag, "be", data);
            }
        } catch (Exception e) {
            LOGGER.warn("提取 BlockEntity 数据失败: {}", e.getMessage());
            data.put("be.error", e.getMessage());
        }
        return data;
    }

    private CrosshairTargetData resolveEntityTarget(EntityHitResult entityHit) {
        Entity entity = entityHit.getEntity();
        String entityId = entity.getType().toString();
        String displayName = LocalizationHelper.safeGetDisplayName(entity.getName().getString());

        String mainHandItemId = null;
        String offHandItemId = null;
        float health = 0f;

        if (entity instanceof LivingEntity living) {
            health = living.getHealth();
            mainHandItemId = extractItemId(living.getItemBySlot(EquipmentSlot.MAINHAND));
            offHandItemId = extractItemId(living.getItemBySlot(EquipmentSlot.OFFHAND));
        }

        return CrosshairTargetData.forEntity(entityId, displayName, mainHandItemId, offHandItemId, health);
    }

    private CrosshairTargetData resolveBiomeFallback(Minecraft mc) {
        try {
            Holder<Biome> biomeHolder = mc.level.getBiome(mc.player.blockPosition());
            String biomeId = biomeHolder.unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("unknown");
            String biomeDisplayName = biomeHolder.unwrapKey()
                    .map(key -> LocalizationHelper.safeGetDisplayName(
                            Component.translatable(key.location().toLanguageKey("biome")).getString()))
                    .orElse(biomeId);
            return CrosshairTargetData.forBiome(biomeId, biomeDisplayName);
        } catch (Exception e) {
            LOGGER.warn("获取生物群系信息失败", e);
            return CrosshairTargetData.forVoid("unknown", "未知");
        }
    }

    private String extractItemId(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return stack.getItemHolder().getRegisteredName();
    }

    private void flattenTag(Tag tag, String prefix, Map<String, String> output) {
        if (output.size() >= MAX_NBT_NODES) {
            if (!output.containsKey("_truncated")) {
                output.put("_truncated", "true");
            }
            return;
        }
        try {
            if (tag instanceof CompoundTag compound) {
                for (String key : compound.getAllKeys()) {
                    if (output.size() >= MAX_NBT_NODES) {
                        output.put("_truncated", "true");
                        return;
                    }
                    String childPath = prefix + "." + key;
                    Tag child = compound.get(key);
                    if (child != null) {
                        flattenTag(child, childPath, output);
                    }
                }
            } else if (tag instanceof CollectionTag<?> collection) {
                output.put(prefix, "[list:" + collection.size() + "]");
            } else {
                output.put(prefix, tag.toString());
            }
        } catch (Exception e) {
            output.put(prefix, "[error]");
        }
    }
}
