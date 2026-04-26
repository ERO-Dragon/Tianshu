package com.rheinmetal.tianshu.function.AcousticRadar;

import com.rheinmetal.tianshu.provider.IAudioEventProvider;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.provider.IPlayerStateProvider;
import com.rheinmetal.tianshu.snapshot.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class AcousticRadarEngine {

    private static final double RADAR_RANGE = 16.0;
    private static final double ALERT_RANGE = 8.0;
    private static final int DEBOUNCE_TICKS_LV2 = 20;
    private static final int DISAPPEAR_TICKS_LV4 = 60;
    private static final int INDICATOR_DURATION_TICKS = 60;

    private final IEnvironmentAwarenessProvider environment;
    private final IPlayerStateProvider playerState;
    private final IAudioEventProvider audioEvent;
    private final AlertSpeaker alertSpeaker;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Tianshu-Radar-Async");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<RadarOutput> volatileOutput = new AtomicReference<>(null);

    private static final class EntityEntryRecord {
        final NearbyEntityData entity;
        final long entryTick;

        EntityEntryRecord(NearbyEntityData entity, long entryTick) {
            this.entity = entity;
            this.entryTick = entryTick;
        }

        NearbyEntityData getEntity() { return entity; }
        long getEntryTick() { return entryTick; }
    }

    private final Map<String, NearbyEntityData> latestRadarTargetMap = new ConcurrentHashMap<>();
    private final Map<String, Long> indicatorExpiryMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, EntityEntryRecord> entityPool = new ConcurrentHashMap<>();
    private final Map<String, Integer> entityOutOfRangeTimers = new ConcurrentHashMap<>();

    private final Set<String> level3PresenceSet = ConcurrentHashMap.newKeySet();

    private volatile boolean isInAlertState = false;
    private volatile int alertCooldownTimer = 0;

    private volatile long currentTick = 0;
    private volatile boolean running = true;
    private volatile boolean soundHookForceTrigger = false;

    public AcousticRadarEngine(
            IEnvironmentAwarenessProvider environment,
            IPlayerStateProvider playerState,
            IAudioEventProvider audioEvent,
            AlertSpeaker alertSpeaker
    ) {
        this.environment = environment;
        this.playerState = playerState;
        this.audioEvent = audioEvent;
        this.alertSpeaker = alertSpeaker;
    }

    public RadarOutput tickSync(PositionData playerPos) {
        if (playerPos == null) return volatileOutput.get();

        currentTick++;
        final long tickSnapshot = currentTick;
        final double px = playerPos.getX(), py = playerPos.getY(), pz = playerPos.getZ();
        final float yaw = playerPos.getYaw(), pitch = playerPos.getPitch();
        final String dim = playerPos.getDimension();

        executor.submit(() -> {
            if (!running) return;
            try {
                PositionData asyncPos = new PositionData(px, py, pz, yaw, pitch, dim, playerPos.getPlayerUuid());
                RadarOutput result = computeInternal(asyncPos, tickSnapshot);
                volatileOutput.set(result);
            } catch (Exception ignored) {}
        });

        return volatileOutput.get();
    }

    private RadarOutput computeInternal(PositionData playerPos, long tick) {
        List<NearbyEntityData> allEntities = environment.getNearbyHostiles(RADAR_RANGE);

        List<NearbyEntityData> hostilesInRadar = new ArrayList<>();
        List<NearbyEntityData> hostilesInAlert = new ArrayList<>();

        for (NearbyEntityData entity : allEntities) {
            if (!entity.isHostile()) continue;
            double dist = entity.getDistance();
            if (dist <= RADAR_RANGE) {
                hostilesInRadar.add(entity);
            }
            if (dist <= ALERT_RANGE) {
                hostilesInAlert.add(entity);
            }
        }

        processLevel2Radar(hostilesInRadar, tick);
        processSoundHook(playerPos, tick);
        processLevel3Perception(hostilesInAlert, tick);
        processLevel4LockedAlert(hostilesInAlert, tick, playerPos.getPlayerUuid());

        List<RadarIndicator> indicators = buildRadarIndicators(tick);

        return new RadarOutput(indicators, tick);
    }

    private void processLevel2Radar(List<NearbyEntityData> hostilesInRadar, long tick) {
        Set<String> currentEntityUuids = new HashSet<>();
        for (NearbyEntityData entity : hostilesInRadar) {
            String entityUuid = entity.getUuid();
            currentEntityUuids.add(entityUuid);

            if (!entityPool.containsKey(entityUuid)) {
                entityPool.put(entityUuid, new EntityEntryRecord(entity, tick));
                indicatorExpiryMap.put(entityUuid, tick + INDICATOR_DURATION_TICKS);
            }
            entityOutOfRangeTimers.remove(entityUuid);
        }

        for (Map.Entry<String, EntityEntryRecord> entry : entityPool.entrySet()) {
            String entityUuid = entry.getKey();
            if (!currentEntityUuids.contains(entityUuid)) {
                int timer = entityOutOfRangeTimers.getOrDefault(entityUuid, 0) + 1;
                entityOutOfRangeTimers.put(entityUuid, timer);
                if (timer >= DEBOUNCE_TICKS_LV2) {
                    entityPool.remove(entityUuid);
                    entityOutOfRangeTimers.remove(entityUuid);
                    indicatorExpiryMap.remove(entityUuid);
                }
            }
        }

        latestRadarTargetMap.clear();
        for (EntityEntryRecord record : entityPool.values()) {
            if (record.getEntryTick() + INDICATOR_DURATION_TICKS <= tick) continue;

            String type = extractEntityType(record.getEntity());
            NearbyEntityData existing = latestRadarTargetMap.get(type);
            if (existing == null || record.getEntryTick() > getEntityEntryTick(existing)) {
                latestRadarTargetMap.put(type, record.getEntity());
            }
        }
    }

    private long getEntityEntryTick(NearbyEntityData entity) {
        EntityEntryRecord rec = entityPool.get(entity.getUuid());
        return rec != null ? rec.getEntryTick() : 0L;
    }

    private void processLevel3Perception(List<NearbyEntityData> hostilesInAlert, long tick) {
        Set<String> currentTypesIn8 = new HashSet<>();
        for (NearbyEntityData entity : hostilesInAlert) {
            currentTypesIn8.add(extractEntityType(entity));
        }

        for (String type : currentTypesIn8) {
            if (!level3PresenceSet.contains(type)) {
                level3PresenceSet.add(type);
                String displayName = resolveDisplayName(hostilesInAlert, type);
                String speech = "注意，检测到" + displayName + "在附近";
                if (alertSpeaker != null) {
                    alertSpeaker.speakAlert(speech);
                }
            }
        }

        Iterator<String> setIt = level3PresenceSet.iterator();
        while (setIt.hasNext()) {
            String type = setIt.next();
            if (!currentTypesIn8.contains(type)) {
                setIt.remove();
            }
        }
    }

    private void processLevel4LockedAlert(List<NearbyEntityData> hostilesInAlert, long tick, String localPlayerUuid) {
        boolean anyLocking = false;
        for (NearbyEntityData entity : hostilesInAlert) {
            boolean isTargetingMe = entity.getTargetUuid() != null && entity.getTargetUuid().equals(localPlayerUuid);
            if (isTargetingMe && !entity.isLineOfSight()) {
                anyLocking = true;
                break;
            }
        }

        if (anyLocking || soundHookForceTrigger) {
            soundHookForceTrigger = false;
            alertCooldownTimer = 0;
            if (!isInAlertState) {
                isInAlertState = true;
                Map<String, Integer> counts = countHostilesByType(hostilesInAlert);
                String speech = buildLockedAlertSpeech(counts);
                if (alertSpeaker != null) {
                    alertSpeaker.speakAlert(speech);
                }
            }
        } else {
            if (isInAlertState) {
                alertCooldownTimer++;
                if (alertCooldownTimer >= DISAPPEAR_TICKS_LV4) {
                    isInAlertState = false;
                    alertCooldownTimer = 0;
                }
            }
        }
    }

    private Map<String, Integer> countHostilesByType(List<NearbyEntityData> hostilesInAlert) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (NearbyEntityData entity : hostilesInAlert) {
            String type = extractEntityType(entity);
            counts.merge(type, 1, Integer::sum);
        }
        return counts;
    }

    private String buildLockedAlertSpeech(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder("警告，已被锁定，警戒范围内共有：");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) sb.append("，");
            sb.append(entry.getValue()).append("只").append(entry.getKey());
            first = false;
        }
        return sb.toString();
    }

    private void processSoundHook(PositionData playerPos, long tick) {
        List<SoundEventData> sounds = audioEvent.pollRecentSoundEvents();
        for (SoundEventData sound : sounds) {
            String sid = sound.getSoundEventId().toLowerCase();
            if (sid.contains("creeper.primed") || sid.contains("tnt.primed") || sid.contains("generic.explode")) {
                soundHookForceTrigger = true; // 仅置位！
                break;
            }
        }
    }

    private List<RadarIndicator> buildRadarIndicators(long tick) {
        List<RadarIndicator> indicators = new ArrayList<>();
        for (Map.Entry<String, NearbyEntityData> entry : latestRadarTargetMap.entrySet()) {
            String type = entry.getKey();
            NearbyEntityData entity = entry.getValue();
            Long expiry = indicatorExpiryMap.get(entity.getUuid());
            if (expiry != null && tick < expiry) {
                indicators.add(new RadarIndicator(
                        type,
                        entity.getDisplayName(),
                        entity.getHorizontalAngle(),
                        entity.getDistance()
                ));
            }
        }
        return indicators;
    }

    private String extractEntityType(NearbyEntityData entity) {
        String id = entity.getEntityId();
        int colon = id.indexOf(':');
        String raw = colon >= 0 ? id.substring(colon + 1) : id;
        if (raw.contains("creeper")) return "苦力怕";
        if (raw.contains("skeleton") || raw.contains("stray")) return "骷髅";
        if (raw.contains("zombie") || raw.contains("husk") || raw.contains("drowned")) return "僵尸";
        if (raw.contains("spider") || raw.contains("cave_spider")) return "蜘蛛";
        if (raw.contains("enderman")) return "末影人";
        if (raw.contains("witch")) return "女巫";
        if (raw.contains("phantom")) return "幻翼";
        if (raw.contains("warden")) return "监守者";
        if (raw.contains("blaze")) return "烈焰人";
        if (raw.contains("ghast")) return "恶魂";
        return entity.getDisplayName();
    }

    private String resolveDisplayName(List<NearbyEntityData> entities, String type) {
        for (NearbyEntityData e : entities) {
            if (extractEntityType(e).equals(type)) {
                return e.getDisplayName();
            }
        }
        return type;
    }

    private String computeDirectionLabel(double relativeAngle) {
        double abs = Math.abs(relativeAngle);
        if (abs < 22.5) return "正前方";
        else if (abs < 67.5) return relativeAngle > 0 ? "左前方" : "右前方";
        else if (abs < 112.5) return relativeAngle > 0 ? "左方" : "右方";
        else if (abs < 157.5) return relativeAngle > 0 ? "左后方" : "右后方";
        else return "正后方";
    }

    public RadarOutput getLastOutput() {
        return volatileOutput.get();
    }

    public void shutdown() {
        running = false;
        executor.shutdownNow();
    }

    public void reset() {
        running = false;
        executor.shutdownNow();
        latestRadarTargetMap.clear();
        indicatorExpiryMap.clear();
        entityPool.clear();
        entityOutOfRangeTimers.clear();
        level3PresenceSet.clear();
        isInAlertState = false;
        alertCooldownTimer = 0;
        volatileOutput.set(null);
        currentTick = 0;
    }
}
