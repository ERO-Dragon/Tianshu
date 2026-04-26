package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IAudioEventProvider;
import com.rheinmetal.tianshu.snapshot.SoundEventData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NeoForgeAudioEventProvider implements IAudioEventProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_EVENTS = 20;

    private static final Set<String> HIGH_THREAT_SOUND_PATTERNS = Set.of(
            "entity.creeper.primed",
            "entity.creeper.hurt",
            "entity.enderman.scream",
            "entity.ender_dragon.growl",
            "entity.warden.heartbeat",
            "entity.warden.agitated",
            "entity.warden.angry",
            "entity.tnt.primed",
            "entity.generic.explode"
    );

    private final Queue<SoundEventData> recentEvents = new ConcurrentLinkedQueue<>();

    public NeoForgeAudioEventProvider() {
        NeoForge.EVENT_BUS.addListener(this::onPlaySound);
    }

    private void onPlaySound(PlayLevelSoundEvent event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;

            var sound = event.getSound();
            if (sound == null) return;

            String soundId = sound.unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("");

            boolean isHighThreat = false;
            for (String pattern : HIGH_THREAT_SOUND_PATTERNS) {
                if (soundId.contains(pattern)) {
                    isHighThreat = true;
                    break;
                }
            }
            if (!isHighThreat) return;

            double sourceX = mc.player.getX();
            double sourceY = mc.player.getY();
            double sourceZ = mc.player.getZ();

            if (event instanceof PlayLevelSoundEvent.AtPosition posEvent) {
                Vec3 pos = posEvent.getPosition();
                if (pos != null) {
                    sourceX = pos.x;
                    sourceY = pos.y;
                    sourceZ = pos.z;
                }
            } else if (event instanceof PlayLevelSoundEvent.AtEntity entityEvent) {
                Entity entity = entityEvent.getEntity();
                if (entity != null) {
                    sourceX = entity.getX();
                    sourceY = entity.getY();
                    sourceZ = entity.getZ();
                }
            }

            LocalPlayer player = mc.player;
            double relX = sourceX - player.getX();
            double relZ = sourceZ - player.getZ();

            double distance = Math.sqrt(relX * relX + relZ * relZ);
            double relativeAngle = Math.toDegrees(Math.atan2(-relX, relZ))
                    - player.getYRot();
            while (relativeAngle > 180) relativeAngle -= 360;
            while (relativeAngle < -180) relativeAngle += 360;

            long gameTick = mc.level.getGameTime();

            SoundEventData eventData = new SoundEventData(
                    soundId, sourceX, sourceY, sourceZ,
                    relativeAngle, distance, gameTick
            );

            recentEvents.add(eventData);
            while (recentEvents.size() > MAX_EVENTS) {
                recentEvents.poll();
            }
        } catch (Exception e) {
            LOGGER.warn("处理声音事件失败: {}", e.getMessage());
        }
    }

    @Override
    public List<SoundEventData> pollRecentSoundEvents() {
        List<SoundEventData> result = new ArrayList<>();
        while (!recentEvents.isEmpty()) {
            SoundEventData event = recentEvents.poll();
            if (event != null) {
                result.add(event);
            }
        }
        return result;
    }
}
