package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IWorldDataProvider;
import com.rheinmetal.tianshu.snapshot.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class NeoForgeWorldDataProvider implements IWorldDataProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_NBT_NODES = 30;

    @Override
    public BlockTargetData getBlockAt(BlockPosValue pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || pos == null) return null;

        try {
            BlockPos blockPos = new BlockPos(pos.x, pos.y, pos.z);
            BlockState blockState = mc.level.getBlockState(blockPos);

            String blockId = blockState.getBlockHolder().getRegisteredName();
            String displayName = LocalizationHelper.safeGetDisplayName(blockState.getBlock().getName().getString());

            Map<String, String> stateProperties = new LinkedHashMap<>();
            for (Property<?> property : blockState.getProperties()) {
                Comparable<?> value = blockState.getValue(property);
                stateProperties.put(property.getName(), value.toString());
            }

            BlockEntity blockEntity = mc.level.getBlockEntity(blockPos);
            boolean hasBlockEntity = blockEntity != null;

            Map<String, String> blockEntityData = Collections.emptyMap();
            if (hasBlockEntity) {
                blockEntityData = extractBlockEntityData(blockEntity, mc);
            }

            Set<String> blockTags = new HashSet<>();
            try {
                blockState.getTags().forEach(tag ->
                        blockTags.add(tag.location().toString()));
            } catch (Exception ignored) {}

            return new BlockTargetData(
                    blockId, displayName, stateProperties,
                    hasBlockEntity, blockEntityData, blockTags
            );
        } catch (Exception e) {
            LOGGER.warn("获取方块数据失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Set<String> getBlockTags(String blockId) {
        if (blockId == null) return Collections.emptySet();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return Collections.emptySet();

        try {
            var registry = mc.level.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
            var resourceKey = net.minecraft.resources.ResourceLocation.tryParse(blockId);
            if (resourceKey == null) return Collections.emptySet();

            var holder = registry.get(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.BLOCK, resourceKey));
            if (holder.isEmpty()) return Collections.emptySet();

            Set<String> tags = new HashSet<>();
            holder.get().tags().forEach(tag ->
                    tags.add(tag.location().toString()));
            return tags;
        } catch (Exception e) {
            LOGGER.warn("获取方块标签失败: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public boolean isLineOfSightBlocked(double x1, double y1, double z1, double x2, double y2, double z2) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return true;

        try {
            net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(x1, y1, z1);
            net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(x2, y2, z2);

            BlockHitResult hitResult = mc.level.clip(new ClipContext(
                    from, to,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    (net.minecraft.world.entity.Entity) null
            ));

            return hitResult.getType() != HitResult.Type.MISS;
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public BlockTargetData getCrosshairBlockTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;

        try {
            if (mc.hitResult instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos();
                BlockPosValue bpv = new BlockPosValue(pos.getX(), pos.getY(), pos.getZ());
                return getBlockAt(bpv);
            }
        } catch (Exception e) {
            LOGGER.warn("获取准星方块目标失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public Set<BlockPosValue> getDirtyChunkSlice(int radius, BlockPosValue lastPlayerPos, int threshold) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return Collections.emptySet();

        try {
            BlockPos current = mc.player.blockPosition();
            if (lastPlayerPos != null) {
                double dx = current.getX() - lastPlayerPos.getX();
                double dz = current.getZ() - lastPlayerPos.getZ();
                if (Math.sqrt(dx * dx + dz * dz) < threshold) {
                    return Collections.emptySet();
                }
            }

            Set<BlockPosValue> dirty = new HashSet<>();
            int cx = current.getX();
            int cy = current.getY();
            int cz = current.getZ();

            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    for (int y = cy - radius; y <= cy + radius; y++) {
                        BlockPos bp = new BlockPos(x, y, z);
                        BlockState bs = mc.level.getBlockState(bp);
                        if (!bs.isAir()) {
                            dirty.add(new BlockPosValue(x, y, z));
                        }
                    }
                }
            }

            return dirty;
        } catch (Exception e) {
            LOGGER.warn("获取脏区块切片失败: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    private Map<String, String> extractBlockEntityData(BlockEntity blockEntity, Minecraft mc) {
        Map<String, String> data = new LinkedHashMap<>();
        try {
            net.minecraft.nbt.CompoundTag tag = blockEntity.saveWithoutMetadata(mc.level.registryAccess());
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

    private void flattenTag(net.minecraft.nbt.Tag tag, String prefix, Map<String, String> output) {
        if (output.size() >= MAX_NBT_NODES) {
            if (!output.containsKey("_truncated")) {
                output.put("_truncated", "true");
            }
            return;
        }
        try {
            if (tag instanceof net.minecraft.nbt.CompoundTag compound) {
                for (String key : compound.getAllKeys()) {
                    if (output.size() >= MAX_NBT_NODES) {
                        output.put("_truncated", "true");
                        return;
                    }
                    String childPath = prefix + "." + key;
                    net.minecraft.nbt.Tag child = compound.get(key);
                    if (child != null) {
                        flattenTag(child, childPath, output);
                    }
                }
            } else if (tag instanceof net.minecraft.nbt.CollectionTag<?> collection) {
                output.put(prefix, "[list:" + collection.size() + "]");
            } else {
                output.put(prefix, tag.toString());
            }
        } catch (Exception e) {
            output.put(prefix, "[error]");
        }
    }
}
