package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IPlayerStateProvider;
import com.rheinmetal.tianshu.snapshot.DeathContextData;
import com.rheinmetal.tianshu.snapshot.GameSettingsData;
import com.rheinmetal.tianshu.snapshot.NavigationInfo;
import com.rheinmetal.tianshu.snapshot.PositionData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;

import java.util.Optional;

public class NeoForgePlayerStateProvider implements IPlayerStateProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    private volatile DeathContextData cachedDeathContext;

    public NeoForgePlayerStateProvider() {
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
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
            if (source.getEntity() != null) {
                killerEntityId = source.getEntity().getType().toString();
            }

            cachedDeathContext = new DeathContextData(
                    damageSourceId, deathMessage,
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    resolveDimensionId(mc), killerEntityId
            );

            LOGGER.info("已缓存玩家死亡上下文: {}", damageSourceId);
        } catch (Exception e) {
            LOGGER.warn("记录死亡上下文失败: {}", e.getMessage());
        }
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
                        0f, 0f, deathDim
                );
            }
        } catch (Exception e) {
            LOGGER.warn("获取上次死亡位置失败: {}", e.getMessage());
        }

        return new NavigationInfo(current, lastDeathPoint, null);
    }

    @Override
    public GameSettingsData getClientGameSettings() {
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;

        float gamma = 0f;
        float masterVolume = 1f;
        int renderDistance = 8;
        String language = "en_us";

        try {
            gamma = options.gamma().get().floatValue();
        } catch (Exception ignored) {}

        try {
            masterVolume = options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER);
        } catch (Exception ignored) {}

        try {
            renderDistance = options.renderDistance().get();
        } catch (Exception ignored) {}

        try {
            language = options.languageCode;
        } catch (Exception ignored) {}

        return new GameSettingsData(gamma, masterVolume, renderDistance, language);
    }

    @Override
    public DeathContextData getLastDeathContext() {
        return cachedDeathContext;
    }

    private PositionData toPositionData(Player player) {
        return new PositionData(
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                resolveDimensionId(Minecraft.getInstance())
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
