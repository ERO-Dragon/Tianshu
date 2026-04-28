package com.rheinmetal.tianshu.function.AcousticRadar;

import com.rheinmetal.tianshu.core.FeatureManager;
import com.rheinmetal.tianshu.provider.IAudioEventProvider;
import com.rheinmetal.tianshu.provider.IEnvironmentAwarenessProvider;
import com.rheinmetal.tianshu.provider.IPlayerStateProvider;
import com.rheinmetal.tianshu.snapshot.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class AcousticRadarEngine {

    private volatile double RADAR_RANGE =32.0;// 这个值只在设置菜单里改
    private volatile double ALERT_RANGE = RADAR_RANGE/2;
    private static final int DEBOUNCE_TICKS_LV2 = 20;
    private static final int DISAPPEAR_TICKS_LV4 = 60;
    private static final int INDICATOR_DURATION_TICKS = 60;

    private final IEnvironmentAwarenessProvider environment;
    private final IPlayerStateProvider playerState;
    private final IAudioEventProvider audioEvent;
    private final AlertSpeaker alertSpeaker;
    private final AlertTextProvider textProvider;
    private final Consumer<Double> scanRequirementUpdater;
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

    private volatile boolean hasActiveThreat = false;
    private volatile int alertCooldownTimer = 0;

    private volatile String pendingLevel3Speech = null;
    private volatile String pendingLevel3Type = null;
    private volatile String pendingLevel4SightSpeech = null;
    private volatile String pendingLevel4BlindSpeech = null;
    private volatile String pendingLevel4ThreatListSpeech = null;
    private volatile boolean hasBroadcastedThreatList = false;

    private final Set<String> knownThreatUuids = ConcurrentHashMap.newKeySet();

    private volatile long currentTick = 0;
    private volatile boolean engineRunning = true;
//Debug Log
private static void debugLog(String msg) {
    try {
        Path logPath = Paths.get("logs", "radar_debug.txt");
        String line = "[" + System.currentTimeMillis() + "] " + msg + "\n";
        Files.write(logPath, line.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
        e.printStackTrace();
    }
}
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
        // 不用管底层是谁，直接通过回调大喊：“我需要 RADAR_RANGE 这么大的框！”
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
        this.RADAR_RANGE = Math.max(4.0, Math.min(32.0, newRange));
        this.ALERT_RANGE = this.RADAR_RANGE/2;
        // 只有在雷达处于开启状态时，才需要大喊
        if (this.isRunning) {
            notifyRequirementChanged();
        }
    }

    // 提供给未来 MR 系统查询当前雷达需求的方法（统筹者需要用到）
    public double getRadarRange() {
        return RADAR_RANGE;
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
            double requestRadius = this.isRunning ? this.RADAR_RANGE : 0.0;
            this.scanRequirementUpdater.accept(requestRadius);
        }
    }
    public RadarOutput tickSync(PositionData playerPos) {
        if (playerPos == null || !isRunning) return volatileOutput.get();

        currentTick++;
        final long tickSnapshot = currentTick;
        final double px = playerPos.getX(), py = playerPos.getY(), pz = playerPos.getZ();
        final float yaw = playerPos.getYaw(), pitch = playerPos.getPitch();
        final String dim = playerPos.getDimension();

        executor.submit(() -> {
            if (!engineRunning || !isRunning) return;
            try {
                PositionData asyncPos = new PositionData(px, py, pz, yaw, pitch, dim, playerPos.getPlayerUuid());
                RadarOutput result = computeInternal(asyncPos, tickSnapshot);
                volatileOutput.set(result);
            } catch (Exception ignored) {}
        });

        dispatchPendingSpeech();

        return volatileOutput.get();
    }

    private void dispatchPendingSpeech() {
        if (pendingLevel4BlindSpeech != null) {
            String blindText = pendingLevel4BlindSpeech;
            pendingLevel4BlindSpeech = null;
            if (alertSpeaker != null) {
                alertSpeaker.speakAlertWithInterrupt(blindText);
            }
        } else if (pendingLevel4SightSpeech != null) {
            String sightText = pendingLevel4SightSpeech;
            pendingLevel4SightSpeech = null;
            if (alertSpeaker != null) {
                if (!hasActiveThreat) {
                    alertSpeaker.speakAlertWithInterrupt(sightText);
                } else {
                    alertSpeaker.speakAlert(sightText);
                }
            }
            hasActiveThreat = true;
        }

        if (pendingLevel3Speech != null) {
            String toSpeak = pendingLevel3Speech;
            String type = pendingLevel3Type;
            pendingLevel3Speech = null;
            pendingLevel3Type = null;
            if (alertSpeaker != null) {
                if (!hasActiveThreat) {
                    alertSpeaker.speakAlertWithInterrupt(toSpeak);
                } else {
                    alertSpeaker.speakAlert(toSpeak);
                }
            }
        }

        if (pendingLevel4ThreatListSpeech != null) {
            if (alertSpeaker != null) {
                alertSpeaker.speakAlert(pendingLevel4ThreatListSpeech);
            }
            pendingLevel4ThreatListSpeech = null;
            hasBroadcastedThreatList = true;
        }
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

        // for (NearbyEntityData entity : hostilesInAlert) {
        //     // 只打印前3个，防止刷屏
        //     if (hostilesInAlert.indexOf(entity) > 2) break;
        //     String target = entity.getTargetUuid() != null ? entity.getTargetUuid() : "NULL(无目标)";
        //     debugLog("[雷达诊断] Tick:{} | 怪物:{} | 距离:{} | 视线:{} | 锁定的UUID:{}"+
        //         tick+
        //         entity.getDisplayName()+
        //         String.format("%.1f", entity.getDistance())+
        //         entity.isLineOfSight()+"      "+
        //         target
        //     );
        // }
        boolean highPrecision = FeatureManager.isHighPrecisionModeAllowed();
debugLog("[雷达诊断] Tick=" + tick + " 高精度=" + highPrecision + " 雷达范围=" + RADAR_RANGE + " 警戒范围=" + ALERT_RANGE + " 实体数=" + hostilesInAlert.size());
        if (highPrecision) {
            Set<UUID> serverLocks = RadarLockState.getServerLockedUuids();
debugLog("[雷达诊断] 服务端锁定实体数=" + serverLocks.size() + " UUIDs=" + serverLocks);
        }
        processLevel2Radar(hostilesInRadar, tick);
        processLevel3Perception(hostilesInAlert, tick, playerPos.getPlayerUuid(), highPrecision);
        processLevel4LockedAlert(hostilesInAlert, tick, playerPos.getPlayerUuid(), highPrecision);

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
    private void processLevel3Perception(List<NearbyEntityData> hostilesInAlert, long tick, String localPlayerUuid, boolean isHighPrecisionMode) {
        Set<String> currentTypesIn8 = new HashSet<>();
        for (NearbyEntityData entity : hostilesInAlert) {
            currentTypesIn8.add(extractEntityType(entity));
        }
        level3PresenceSet.removeIf(type -> !currentTypesIn8.contains(type));

        if (pendingLevel3Speech != null) return;

        for (NearbyEntityData entity : hostilesInAlert) {
            boolean isThreat = false;
            if (isHighPrecisionMode) {
                isThreat = RadarLockState.isLockedByServer(UUID.fromString(entity.getUuid()));
            } else {
                // 降级模式：只有被墙挡住，才视为“没锁定”，归入 L3
                isThreat = entity.isLineOfSight();
            }

            // 如果是 L4 级威胁，跳过它
            if (isThreat) continue;

            // 剩下的全是 L3
            String type = extractEntityType(entity);
            if (!level3PresenceSet.contains(type)) {
                String displayName = resolveDisplayName(hostilesInAlert, type);
                pendingLevel3Speech = textProvider.getLevel3DetectionText(displayName);
                pendingLevel3Type = type;
                level3PresenceSet.add(type);
                break;
            }
        }
    }
    private void processLevel4LockedAlert(List<NearbyEntityData> hostilesInAlert, long tick, String localPlayerUuid, boolean isHighPrecisionMode) {
        Set<String> currentBlindUuids = new HashSet<>();
        Set<String> currentFrontalUuids = new HashSet<>();
        Set<String> currentAllThreatUuids = new HashSet<>();

        float fovThreshold = getCurrentDynamicFov() / 2.0f;
        
        NearbyEntityData primaryNewFrontal = null;
        double minFrontalDist = Double.MAX_VALUE;
        NearbyEntityData primaryNewBlind = null;
        double minBlindDist = Double.MAX_VALUE;

        for (NearbyEntityData entity : hostilesInAlert) {
            boolean isLocked = false;
            if (isHighPrecisionMode) {
                isLocked = RadarLockState.isLockedByServer(UUID.fromString(entity.getUuid()));
debugLog("[雷达诊断-L4] 实体=" + entity.getDisplayName() + " UUID=" + entity.getUuid() + " 服务端锁定=" + isLocked + " 距离=" + String.format("%.1f", entity.getDistance()) + " 角度=" + String.format("%.1f", entity.getHorizontalAngle()));
            } else {
                // 降级模式：无遮挡即锁定
                isLocked = entity.isLineOfSight();
            }

            if (isLocked) {
                currentAllThreatUuids.add(entity.getUuid());
                double absAngle = Math.abs(entity.getHorizontalAngle());
                boolean isNew = !knownThreatUuids.contains(entity.getUuid());
                
                // 用动态 FOV 替代原版的 120.0 硬编码
                if (absAngle > fovThreshold) {
                    currentBlindUuids.add(entity.getUuid());
                    if (isNew && entity.getDistance() < minBlindDist) {
                        minBlindDist = entity.getDistance();
                        primaryNewBlind = entity;
                    }
                } else {
                    currentFrontalUuids.add(entity.getUuid());
                    if (isNew && entity.getDistance() < minFrontalDist) {
                        minFrontalDist = entity.getDistance();
                        primaryNewFrontal = entity;
                    }
                }
            }
        }

        Set<String> newBlindUuids = new HashSet<>(currentBlindUuids);
        newBlindUuids.removeAll(knownThreatUuids);

        if (!newBlindUuids.isEmpty()) {
            alertCooldownTimer = 0;
            pendingLevel3Speech = null;
            pendingLevel3Type = null;
            pendingLevel4SightSpeech = null;

            String blindDirection = "未知";
            String blindType = "敌人";
            if (primaryNewBlind != null) {
                blindDirection = computeDirectionLabel(primaryNewBlind.getHorizontalAngle());
                blindType = extractEntityType(primaryNewBlind);
            }
            pendingLevel4BlindSpeech = textProvider.getLevel4BlindSpotText(blindDirection, blindType);

            if (!hasBroadcastedThreatList) {
                String content = buildThreatListContent(hostilesInAlert, currentAllThreatUuids);
                if (!content.isEmpty()) {
                    pendingLevel4ThreatListSpeech = textProvider.getLevel4ThreatListText(content);
                }
            }
            hasActiveThreat = true;
            knownThreatUuids.addAll(currentBlindUuids);
            return;
        }

debugLog("[雷达诊断-L4] 当前威胁=" + currentAllThreatUuids.size() + " 盲区=" + currentBlindUuids.size() + " 前方=" + currentFrontalUuids.size() + " known=" + knownThreatUuids.size());

        if (!currentAllThreatUuids.isEmpty()) {
            alertCooldownTimer = 0;
            
            // 只有前方新威胁时的处理（带 8 向）
            Set<String> newFrontalOnlyUuids = new HashSet<>(currentFrontalUuids);
            newFrontalOnlyUuids.removeAll(knownThreatUuids);
debugLog("[雷达诊断-L4] newFrontalOnly=" + newFrontalOnlyUuids.size() + " hasActiveThreat=" + hasActiveThreat + " primaryNewFrontal=" + (primaryNewFrontal != null));
            
            if (pendingLevel4SightSpeech == null && !newFrontalOnlyUuids.isEmpty() && primaryNewFrontal != null) {
                String frontalDirection = computeDirectionLabel(primaryNewFrontal.getHorizontalAngle());
                pendingLevel4SightSpeech = frontalDirection+textProvider.getLevel4SightEngageText();
debugLog("[雷达诊断-L4] 触发前方接敌播报: " + frontalDirection);
            }

            if (!hasBroadcastedThreatList && pendingLevel4ThreatListSpeech == null) {
                String content = buildThreatListContent(hostilesInAlert, currentAllThreatUuids);
                if (!content.isEmpty()) {
                    pendingLevel4ThreatListSpeech = textProvider.getLevel4ThreatListText(content);
debugLog("[雷达诊断-L4] 触发威胁列表: " + content);
                }
            }
        } else {
            if (hasActiveThreat || pendingLevel4SightSpeech != null) {
                alertCooldownTimer++;
                if (alertCooldownTimer >= DISAPPEAR_TICKS_LV4) {
                    hasActiveThreat = false;
                    pendingLevel4SightSpeech = null;
                    pendingLevel4ThreatListSpeech = null;
                    hasBroadcastedThreatList = false;
                    alertCooldownTimer = 0;
                    knownThreatUuids.clear();
                }
            }
        }
        knownThreatUuids.addAll(currentAllThreatUuids);
        knownThreatUuids.retainAll(currentAllThreatUuids);
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
        if (abs < 22.5) return "前方";
        else if (abs < 67.5) return relativeAngle > 0 ? "右前方" : "左前方";
        else if (abs < 112.5) return relativeAngle > 0 ? "右方" : "左方";
        else if (abs < 157.5) return relativeAngle > 0 ? "右后方" : "左后方";
        else return "后方";
    }

    private float getCurrentDynamicFov() {
        try {
            return this.playerState.getCurrentDynamicFov();
        } catch (Exception ignored) {}
        return 70.0f;
    }

    public RadarOutput getLastOutput() {
        return volatileOutput.get();
    }

    public void shutdown() {
        engineRunning = false;
        executor.shutdownNow();
    }

    public void reset() {
        engineRunning = false;
        executor.shutdownNow();
        latestRadarTargetMap.clear();
        indicatorExpiryMap.clear();
        entityPool.clear();
        entityOutOfRangeTimers.clear();
        level3PresenceSet.clear();
        hasActiveThreat = false;
        alertCooldownTimer = 0;
        pendingLevel3Speech = null;
        pendingLevel3Type = null;
        pendingLevel4SightSpeech = null;
        pendingLevel4BlindSpeech = null;
        pendingLevel4ThreatListSpeech = null;
        hasBroadcastedThreatList = false;
        knownThreatUuids.clear();
        volatileOutput.set(null);
        currentTick = 0;
    }
}
