package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IPlayerStateProvider;
import com.rheinmetal.tianshu.snapshot.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.Optional;

public class NeoForgePlayerStateProvider implements IPlayerStateProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    private volatile DeathContextData cachedDeathContext;
    private volatile long lastDamageGameTick = -1;
    private volatile PositionData cachedBedSpawn;
    private volatile String cachedBedDimension;

    public NeoForgePlayerStateProvider() {
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(this::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
    }

    private void onLivingDamage(LivingDamageEvent.Post event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (!event.getEntity().getUUID().equals(mc.player.getUUID())) return;
            lastDamageGameTick = mc.player.level().getGameTime();
        } catch (Exception e) {
            LOGGER.warn("记录受击事件失败: {}", e.getMessage());
        }
    }

    private void onLivingDeath(LivingDeathEvent event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            LivingEntity entity = event.getEntity();
            if (!entity.getUUID().equals(mc.player.getUUID())) return;

            DamageSource source = event.getSource();
            String damageSourceId = source.typeHolder().unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("unknown");

            String deathMessage = source.getLocalizedDeathMessage(mc.player).getString();

            String killerEntityId = null;
            String killerDisplayName = null;
            if (source.getEntity() != null) {
                killerEntityId = source.getEntity().getType().toString();
                killerDisplayName = LocalizationHelper.safeGetDisplayName(source.getEntity().getName().getString());
            }

            cachedDeathContext = new DeathContextData(
                    damageSourceId, deathMessage,
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    resolveDimensionId(mc), killerEntityId, killerDisplayName
            );

            LOGGER.info("已缓存玩家死亡上下文: {}", damageSourceId);
        } catch (Exception e) {
            LOGGER.warn("记录死亡上下文失败: {}", e.getMessage());
        }
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            if (!event.getEntity().getUUID().equals(mc.player.getUUID())) return;

            Optional<BlockPos> sleepPos = mc.player.getSleepingPos();
            if (sleepPos.isPresent()) {
                BlockPos pos = sleepPos.get();
                cachedBedSpawn = new PositionData(
                        pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
                        0f, 0f, resolveDimensionId(mc), null
                );
                cachedBedDimension = resolveDimensionId(mc);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public NavigationInfo getPlayerNavigationInfo() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return new NavigationInfo(null, null, null);
        }

        Player player = mc.player;
        PositionData current = toPositionData(player);

        PositionData lastDeathPoint = null;
        try {
            Optional<GlobalPos> lastDeath = player.getLastDeathLocation();
            if (lastDeath.isPresent()) {
                GlobalPos globalPos = lastDeath.get();
                BlockPos deathPos = globalPos.pos();
                String deathDim = globalPos.dimension().location().toString();
                lastDeathPoint = new PositionData(
                        deathPos.getX() + 0.5, deathPos.getY(), deathPos.getZ() + 0.5,
                        0f, 0f, deathDim, null
                );
            }
        } catch (Exception e) {
            LOGGER.warn("获取上次死亡位置失败: {}", e.getMessage());
        }

        PositionData spawnPoint = getSpawnPoint();

        return new NavigationInfo(current, lastDeathPoint, spawnPoint);
    }

    @Override
    public String getCurrentDimensionDisplayName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return "unknown";
        try {
            String dimensionId = mc.level.dimension().location().toString();
            String displayName = Component.translatable(mc.level.dimension().location().toLanguageKey("dimension")).getString();
            displayName = LocalizationHelper.safeGetDisplayName(displayName);
            return displayName == null || displayName.isBlank() ? dimensionId : displayName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public GameSettingsData getClientGameSettings() {
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;

        float gamma = 0f;
        float masterVolume = 1f;
        int renderDistance = 8;
        String language = "en_us";
        float fov = 70f;
        String difficulty = "normal";
        float musicVolume = 1f;
        float soundVolume = 1f;

        try { gamma = options.gamma().get().floatValue(); } catch (Exception ignored) {}
        try { masterVolume = options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER); } catch (Exception ignored) {}
        try { renderDistance = options.renderDistance().get(); } catch (Exception ignored) {}
        try { language = options.languageCode; } catch (Exception ignored) {}
        try { fov = options.fov().get().floatValue(); } catch (Exception ignored) {}
        try { musicVolume = options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MUSIC); } catch (Exception ignored) {}
        try { soundVolume = options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.BLOCKS); } catch (Exception ignored) {}

        try {
            if (mc.level != null) {
                difficulty = mc.level.getDifficulty().getKey();
            }
        } catch (Exception ignored) {}

        return new GameSettingsData(gamma, masterVolume, renderDistance, language, fov, difficulty, musicVolume, soundVolume);
    }

    @Override
    public DeathContextData getLastDeathContext() {
        return cachedDeathContext;
    }

    @Override
    public PlayerStatusData getPlayerStatus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return new PlayerStatusData(0, 0, 0, 0, 0, "survival", -1, null, 0, 0);
        }

        Player player = mc.player;
        String gameMode = "survival";
        try {
            if (mc.gameMode != null) {
                gameMode = mc.gameMode.getPlayerMode().getName();
            }
        } catch (Exception ignored) {}

        String lastDamageSourceId = null;
        if (cachedDeathContext != null) {
            lastDamageSourceId = cachedDeathContext.getDamageSourceId();
        }

        int airSupply = 0;
        int maxAirSupply = 0;
        try {
            airSupply = player.getAirSupply();
            maxAirSupply = player.getMaxAirSupply();
        } catch (Exception ignored) {}

        return new PlayerStatusData(
                player.getHealth(),
                player.getMaxHealth(),
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel(),
                player.experienceLevel,
                gameMode,
                lastDamageGameTick,
                lastDamageSourceId,
                airSupply,
                maxAirSupply
        );
    }

    @Override
    public PositionData getSpawnPoint() {
        if (cachedBedSpawn != null) {
            return cachedBedSpawn;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;

        try {
            BlockPos spawnPos = mc.level.getSharedSpawnPos();
            String dimension = resolveDimensionId(mc);
            return new PositionData(
                    spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                    0f, 0f, dimension, null
            );
        } catch (Exception e) {
            LOGGER.warn("获取出生点失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public long getLastDamageTick() {
        return lastDamageGameTick;
    }

    @Override
    public float getCurrentDynamicFov() {
        try {
            Minecraft mc = Minecraft.getInstance();
            float baseFov = mc.options.fov().get().floatValue();
            float modifier = mc.player != null ? mc.player.getFieldOfViewModifier() : 1.0f;
            if (modifier <= 0.0f || modifier > 4.0f) modifier = 1.0f;
            return baseFov * modifier;
        } catch (Exception ignored) {}
        return 70.0f;
    }

    private PositionData toPositionData(Player player) {
        return new PositionData(
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                resolveDimensionId(Minecraft.getInstance()),
                player.getUUID().toString()
        );
    }

    private String resolveDimensionId(Minecraft mc) {
        if (mc.level == null) return "unknown";
        try {
            return mc.level.dimension().location().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
