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
import java.util.function.Consumer;

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
    private final AlertTextProvider textProvider;
    private final Consumer<Double> scanRequirementUpdater;
    private volatile double radarRange = 16.0; // 这个值只在设置菜单里改
    private volatile boolean isRunning = false; // 这个值在游戏里动态开关

    
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

    private volatile String pendingLevel3Speech = null;
    private volatile String pendingLevel3Type = null;
    private volatile String pendingLevel4SightSpeech = null;
    private volatile String pendingLevel4ThreatListSpeech = null;
    private volatile boolean hasBroadcastedThreatList = false;

    private final Set<String> knownThreatUuids = ConcurrentHashMap.newKeySet();

    private volatile long currentTick = 0;
    private volatile boolean running = true;

    public AcousticRadarEngine(
            IEnvironmentAwarenessProvider environment,
            IPlayerStateProvider playerState,
            IAudioEventProvider audioEvent,
            AlertSpeaker alertSpeaker,
            AlertTextProvider textProvider,
            Consumer<Double> scanRequirementUpdater // <--- 新增
    ) {
        this.environment = environment;
        this.playerState = playerState;
        this.audioEvent = audioEvent;
        this.alertSpeaker = alertSpeaker;
        this.textProvider = textProvider;
        this.scanRequirementUpdater = scanRequirementUpdater;
    }

    // 游戏内快捷键动态开启
    public void start() {
        this.isRunning = true;
        // 不用管底层是谁，直接通过回调大喊：“我需要 radarRange 这么大的框！”
        notifyRequirementChanged();
    }

    // 游戏内快捷键动态关闭
    public void stop() {
        this.isRunning = false;
        // 大喊：“我不需要框了（传0）！”
        notifyRequirementChanged();
    }

    // 设置菜单里调整范围
    public void setRadarRange(double newRange) {
        this.radarRange = Math.max(4.0, Math.min(32.0, newRange));
        // 只有在雷达处于开启状态时，才需要大喊
        if (this.isRunning) {
            notifyRequirementChanged();
        }
    }

    // 提供给未来 MR 系统查询当前雷达需求的方法（统筹者需要用到）
    public double getRadarRange() {
        return radarRange;
    }

    public boolean isRunning() {
        return isRunning;
    }
    
    // ---------------- 私有辅助方法 ----------------

    /**
     * 封装状态变更通知，避免重复代码
     */
    private void notifyRequirementChanged() {
        if (this.scanRequirementUpdater != null) {
            double requestRadius = this.isRunning ? this.radarRange : 0.0;
            this.scanRequirementUpdater.accept(requestRadius);
        }
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
        processLevel3Perception(hostilesInAlert, tick, playerPos.getPlayerUuid());
        processLevel4LockedAlert(hostilesInAlert, tick, playerPos.getPlayerUuid());

        if (pendingLevel3Speech != null) {
            String toSpeak = pendingLevel3Speech;
            String type = pendingLevel3Type;
            pendingLevel3Speech = null;
            pendingLevel3Type = null;
            level3PresenceSet.add(type);
            if (alertSpeaker != null) {
                if (!isInAlertState) alertSpeaker.speakAlertWithInterrupt(toSpeak);
                else alertSpeaker.speakAlert(toSpeak);
            }
        }

        if (pendingLevel4SightSpeech != null) {
            String sightText = pendingLevel4SightSpeech;
            pendingLevel4SightSpeech = null;
            if (alertSpeaker != null) {
                if (!isInAlertState) alertSpeaker.speakAlertWithInterrupt(sightText);
                else alertSpeaker.speakAlert(sightText);
            }
            isInAlertState = true;
        }

        if (pendingLevel4ThreatListSpeech != null) {
            if (alertSpeaker != null) {
                alertSpeaker.speakAlert(pendingLevel4ThreatListSpeech);
            }
            pendingLevel4ThreatListSpeech = null;
            hasBroadcastedThreatList = true;
        }

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

    private void processLevel3Perception(List<NearbyEntityData> hostilesInAlert, long tick, String localPlayerUuid) {
        Set<String> currentTypesIn8 = new HashSet<>();
        for (NearbyEntityData entity : hostilesInAlert) {
            currentTypesIn8.add(extractEntityType(entity));
        }

        level3PresenceSet.removeIf(type -> !currentTypesIn8.contains(type));

        if (pendingLevel3Speech != null) return;

        for (NearbyEntityData entity : hostilesInAlert) {
            String type = extractEntityType(entity);

            boolean isTargetingMe = entity.getTargetUuid() != null && entity.getTargetUuid().equals(localPlayerUuid);
            if (isTargetingMe) continue;

            if (!level3PresenceSet.contains(type)) {
                String displayName = resolveDisplayName(hostilesInAlert, type);
                pendingLevel3Speech = textProvider.getLevel3DetectionText(displayName);
                pendingLevel3Type = type;
                break;
            }
        }
    }

    private void processLevel4LockedAlert(List<NearbyEntityData> hostilesInAlert, long tick, String localPlayerUuid) {
        Set<String> currentBlindLockedUuids = new HashSet<>();
        Set<String> currentAllLockedUuids = new HashSet<>();

        for (NearbyEntityData entity : hostilesInAlert) {
            boolean isTargetingMe = entity.getTargetUuid() != null && entity.getTargetUuid().equals(localPlayerUuid);
            if (!isTargetingMe) continue;

            currentAllLockedUuids.add(entity.getUuid());
            if (!entity.isLineOfSight()) {
                currentBlindLockedUuids.add(entity.getUuid());
            }
        }

        Set<String> newBlindUuids = new HashSet<>(currentBlindLockedUuids);
        newBlindUuids.removeAll(knownThreatUuids);

        if (!newBlindUuids.isEmpty()) {
            alertCooldownTimer = 0;
            pendingLevel3Speech = null;
            pendingLevel3Type = null;
            pendingLevel4SightSpeech = null;

            double sumAngle = 0;
            int blindCount = 0;
            Set<String> blindTypes = new HashSet<>();
            for (NearbyEntityData entity : hostilesInAlert) {
                if (newBlindUuids.contains(entity.getUuid())) {
                    sumAngle += entity.getHorizontalAngle();
                    blindCount++;
                    blindTypes.add(extractEntityType(entity));
                }
            }
            String blindDirection = "未知";
            String blindType = "敌人";
            if (blindCount > 0) {
                blindDirection = computeDirectionLabel(sumAngle / blindCount);
                if (blindTypes.size() == 1) {
                    blindType = blindTypes.iterator().next();
                }
            }

            if (alertSpeaker != null) {
                alertSpeaker.speakAlertWithInterrupt(textProvider.getLevel4BlindSpotText(blindDirection, blindType));
            }
            knownThreatUuids.addAll(currentAllLockedUuids);

            for (NearbyEntityData entity : hostilesInAlert) {
                boolean isTargetingMe = entity.getTargetUuid() != null && entity.getTargetUuid().equals(localPlayerUuid);
                if (isTargetingMe && entity.isLineOfSight()) {
                    pendingLevel4SightSpeech = textProvider.getLevel4SightEngageText();
                    break;
                }
            }

            if (!hasBroadcastedThreatList) {
                String content = buildThreatListContent(hostilesInAlert, currentAllLockedUuids);
                if (!content.isEmpty()) {
                    pendingLevel4ThreatListSpeech = textProvider.getLevel4ThreatListText(content);
                }
            }

            isInAlertState = true;
            return;
        }

        if (!currentAllLockedUuids.isEmpty()) {
            alertCooldownTimer = 0;

            if (!isInAlertState && pendingLevel4SightSpeech == null) {
                pendingLevel4SightSpeech = textProvider.getLevel4SightEngageText();
            }

            if (!hasBroadcastedThreatList && pendingLevel4ThreatListSpeech == null) {
                String content = buildThreatListContent(hostilesInAlert, currentAllLockedUuids);
                if (!content.isEmpty()) {
                    pendingLevel4ThreatListSpeech = textProvider.getLevel4ThreatListText(content);
                }
            }
        } else {
            if (isInAlertState || pendingLevel4SightSpeech != null) {
                alertCooldownTimer++;
                if (alertCooldownTimer >= DISAPPEAR_TICKS_LV4) {
                    isInAlertState = false;
                    pendingLevel4SightSpeech = null;
                    pendingLevel4ThreatListSpeech = null;
                    hasBroadcastedThreatList = false;
                    alertCooldownTimer = 0;
                    knownThreatUuids.clear();
                }
            }
        }

        knownThreatUuids.retainAll(currentAllLockedUuids);
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

    private String buildThreatListContent(List<NearbyEntityData> hostilesInAlert, Set<String> lockedUuids) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (NearbyEntityData entity : hostilesInAlert) {
            if (lockedUuids.contains(entity.getUuid())) {
                String type = extractEntityType(entity);
                counts.merge(type, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) sb.append("，");
            sb.append(entry.getValue()).append("只").append(entry.getKey());
            first = false;
        }
        return sb.toString();
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
        pendingLevel3Speech = null;
        pendingLevel3Type = null;
        pendingLevel4SightSpeech = null;
        pendingLevel4ThreatListSpeech = null;
        hasBroadcastedThreatList = false;
        knownThreatUuids.clear();
        volatileOutput.set(null);
        currentTick = 0;
    }
}
