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
import java.util.function.Consumer;

public class AcousticRadarEngine {

    private volatile double RADAR_RANGE = 32.0;
    private volatile double ALERT_RANGE = RADAR_RANGE / 2;

    private static final int DEBOUNCE_TICKS_LV2 = 20;
    private static final int DISAPPEAR_TICKS_LV4 = 60;
    private static final int INDICATOR_DURATION_TICKS = 60;

    private final IEnvironmentAwarenessProvider environment;
    private final IPlayerStateProvider playerState;
    private final IAudioEventProvider audioEvent;
    private final AlertSpeaker alertSpeaker;
    private final AlertTextProvider textProvider;
    private final Consumer<Double> scanRequirementUpdater;

    private volatile boolean isRunning = false;

    private RadarOutput lastOutput = null;

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

    private final Map<String, NearbyEntityData> latestRadarTargetMap = new HashMap<>();
    private final Map<String, Long> indicatorExpiryMap = new HashMap<>();
    private final Map<String, EntityEntryRecord> entityPool = new HashMap<>();
    private final Map<String, Integer> entityOutOfRangeTimers = new HashMap<>();
    private final Set<String> level3PresenceSet = new HashSet<>();

    private boolean hasActiveThreat = false;
    private int alertCooldownTimer = 0;
    private String pendingLevel3Speech = null;
    private String pendingLevel3Type = null;
    private String pendingLevel4Speech = null;
    private boolean isLevel4Interruptive = false;
    private boolean hasBroadcastedThreatList = false;

    private final Set<String> knownThreatUuids = new HashSet<>();
    private final Set<String> historicalBlindSet = new HashSet<>(); 
    private volatile boolean isTTSBusy = false;
    private boolean wasBlindSpotEmpty = true;

    private long currentTick = 0;

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
            Consumer<Double> scanRequirementUpdater
    ) {
        this.environment = environment;
        this.playerState = playerState;
        this.audioEvent = audioEvent;
        this.alertSpeaker = alertSpeaker;
        this.textProvider = textProvider;
        this.scanRequirementUpdater = scanRequirementUpdater;
    }

    public void start() {
        this.isRunning = true;
        notifyRequirementChanged();
    }

    public void stop() {
        this.isRunning = false;
        notifyRequirementChanged();
    }

    public void setRadarRange(double newRange) {
        this.RADAR_RANGE = Math.max(4.0, Math.min(32.0, newRange));
        this.ALERT_RANGE = this.RADAR_RANGE / 2;
        if (this.isRunning) {
            notifyRequirementChanged();
        }
    }

    public double getRadarRange() { return RADAR_RANGE; }
    public boolean isRunning() { return isRunning; }

    private void notifyRequirementChanged() {
        if (this.scanRequirementUpdater != null) {
            double requestRadius = this.isRunning ? this.RADAR_RANGE : 0.0;
            this.scanRequirementUpdater.accept(requestRadius);
        }
    }

    public void onTtsPlaybackFinished() {
        isTTSBusy = false;
    }

    public RadarOutput tickSync(PositionData playerPos) {
        if (playerPos == null || !isRunning) return lastOutput;

        currentTick++;
        final long tickSnapshot = currentTick;
        final double px = playerPos.getX(), py = playerPos.getY(), pz = playerPos.getZ();
        final float yaw = playerPos.getYaw(), pitch = playerPos.getPitch();
        final String dim = playerPos.getDimension();

        PositionData posData = new PositionData(px, py, pz, yaw, pitch, dim, playerPos.getPlayerUuid());
        lastOutput = computeInternal(posData, tickSnapshot);
        dispatchPendingSpeech();

        return lastOutput;
    }

    private void dispatchPendingSpeech() {
        if (pendingLevel4Speech != null) {
            String text = pendingLevel4Speech;
            boolean needInterrupt = isLevel4Interruptive;

            pendingLevel4Speech = null;
            isLevel4Interruptive = false;

            if (alertSpeaker != null) {
                isTTSBusy = true;
                if (needInterrupt) {
                    alertSpeaker.speakAlertWithInterrupt(text);
                    hasActiveThreat = true;
                } else {
                    alertSpeaker.speakAlert(text);
                }
            }
            return;
        }

        if (pendingLevel3Speech != null) {
            if (hasActiveThreat) {
                pendingLevel3Speech = null;
                pendingLevel3Type = null;
                return;
            }
            String toSpeak = pendingLevel3Speech;
            pendingLevel3Speech = null;
            pendingLevel3Type = null;
            if (alertSpeaker != null) {
                isTTSBusy = true;
                alertSpeaker.speakAlertWithInterrupt(toSpeak);
            }
        }
    }

    private RadarOutput computeInternal(PositionData playerPos, long tick) {
        List<NearbyEntityData> allEntities = environment.getNearbyHostiles(RADAR_RANGE);
        List<NearbyEntityData> hostilesInRadar = new ArrayList<>();
        List<NearbyEntityData> hostilesInAlert = new ArrayList<>();

        for (NearbyEntityData entity : allEntities) {
            if (!entity.isHostile()) continue;
            double dist = entity.getDistance();
            if (dist <= RADAR_RANGE) hostilesInRadar.add(entity);
            if (dist <= ALERT_RANGE) hostilesInAlert.add(entity);
        }

        boolean highPrecision = FeatureManager.isHighPrecisionModeAllowed();
        debugLog("[雷达诊断] Tick=" + tick + " 高精度=" + highPrecision + " 雷达范围=" + RADAR_RANGE + " 警戒范围=" + ALERT_RANGE + " 实体数=" + hostilesInAlert.size());

        if (highPrecision) {
            Set<UUID> serverLocks = RadarLockState.getServerLockedUuids();
            debugLog("[雷达诊断] 服务端锁定实体数=" + serverLocks.size());
        }

        processLevel2Radar(hostilesInRadar, tick);
        processLevel3Perception(hostilesInAlert, tick, playerPos.getPlayerUuid(), highPrecision);
        processLevel4LockedAlert(hostilesInAlert, hostilesInRadar, tick, playerPos.getPlayerUuid(), highPrecision);

        return new RadarOutput(buildRadarIndicators(tick), tick);
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
        Iterator<Map.Entry<String, EntityEntryRecord>> it = entityPool.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, EntityEntryRecord> entry = it.next();
            String entityUuid = entry.getKey();
            if (!currentEntityUuids.contains(entityUuid)) {
                int timer = entityOutOfRangeTimers.getOrDefault(entityUuid, 0) + 1;
                entityOutOfRangeTimers.put(entityUuid, timer);
                if (timer >= DEBOUNCE_TICKS_LV2) {
                    it.remove();
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

        if (isTTSBusy) return;
        if (pendingLevel3Speech != null) return;

        for (NearbyEntityData entity : hostilesInAlert) {
            boolean isThreat = false;
            if (isHighPrecisionMode) {
                isThreat = RadarLockState.isLockedByServer(UUID.fromString(entity.getUuid()));
            } else {
                isThreat = entity.isLineOfSight();
            }
            if (isThreat) continue;

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

    private void processLevel4LockedAlert(List<NearbyEntityData> hostilesInAlert, List<NearbyEntityData> hostilesInRadar, long tick, String localPlayerUuid, boolean isHighPrecisionMode) {
        Set<String> currentAllThreatUuids = new HashSet<>();
        Set<String> currentFrontalUuids = new HashSet<>();
        
        // 【关键点1】每帧新建一个纯瞬态快照，不碰历史池！
        Set<String> currentBlindSnapshot = new HashSet<>();

        float rawFov = getCurrentDynamicFov();
        if (rawFov <= 10.0f || rawFov > 180.0f) rawFov = 70.0f;
        float fovThreshold = rawFov / 2.0f;

        // === 第一阶段：只收集，不写 knownThreatUuids ===
        for (NearbyEntityData entity : hostilesInAlert) {
            boolean isLocked = false;
            if (isHighPrecisionMode) {
                isLocked = RadarLockState.isLockedByServer(UUID.fromString(entity.getUuid()));
            } else {
                isLocked = entity.isLineOfSight();
            }
            if (isLocked) {
                currentAllThreatUuids.add(entity.getUuid());
                double absAngle = Math.abs(entity.getHorizontalAngle());
                
                if (absAngle > fovThreshold) {
                    // 在盲区：加入瞬态快照用于跃迁计算
                    currentBlindSnapshot.add(entity.getUuid());
                    // 不在已知历史里，才加入历史盲区池（用于转正）
                    if (!knownThreatUuids.contains(entity.getUuid())) {
                        historicalBlindSet.add(entity.getUuid());
                    }
                } else {
                    // 在前方：加入前方集合
                    currentFrontalUuids.add(entity.getUuid());
                    // 【关键点2】真正的盲区转正！从历史池中剔除，代表已被视线捕获
                    historicalBlindSet.remove(entity.getUuid());
                }
            }
        }

        // === 第二阶段：基于瞬态快照计算跃迁 ===
        boolean isBlindSpotNowEmpty = currentBlindSnapshot.isEmpty();
        boolean blindSpotBecameNonEmpty = wasBlindSpotEmpty && !isBlindSpotNowEmpty;
        wasBlindSpotEmpty = isBlindSpotNowEmpty;

        // === 第三阶段：计算新前方怪（必须在修改 known 之前） ===
        Set<String> newFrontalUuids = new HashSet<>(currentFrontalUuids);
        newFrontalUuids.removeAll(knownThreatUuids);

        // === 第四阶段：统一更新历史记录 ===
        knownThreatUuids.addAll(currentFrontalUuids);
        // 本帧确认存在的盲区怪，也拉入已知（防止下帧转头看到时被误判为前方新怪）
        knownThreatUuids.addAll(historicalBlindSet);
        
        // 【关键点3】历史池保洁：敌意信号消失的怪，如果还在历史池里，立刻扫地出门，防止僵尸数据
        historicalBlindSet.retainAll(currentAllThreatUuids);

        boolean hasNewFrontal = !newFrontalUuids.isEmpty();
        boolean shouldTrigger = false;
        boolean isPeaceFirstStrike = false;

        if (!hasActiveThreat) {
            if (blindSpotBecameNonEmpty || hasNewFrontal) {
                shouldTrigger = true;
                isPeaceFirstStrike = true;
            }
        } else {
            if (blindSpotBecameNonEmpty) {
                shouldTrigger = true;
            }
        }

        debugLog("[L4诊断] Tick=" + tick + " | knownSize=" + knownThreatUuids.size() + " | histBlindSize=" + historicalBlindSet.size() + " | snapshotSize=" + currentBlindSnapshot.size() + " | blindBecameNonEmpty=" + blindSpotBecameNonEmpty + " | newFrontal=" + hasNewFrontal + " | shouldTrigger=" + shouldTrigger);

        // L4 拥有绝对打断权，无视 isTTSBusy 直接赋值
        if (shouldTrigger) {
            alertCooldownTimer = 0;
            pendingLevel3Speech = null;
            pendingLevel3Type = null;
            String mainText = null;

            // 优先播报盲区（注意这里要遍历瞬态快照找最近的）
            if (!currentBlindSnapshot.isEmpty()) {
                NearbyEntityData primaryBlind = null;
                double minDist = Double.MAX_VALUE;
                for (NearbyEntityData entity : hostilesInAlert) {
                    if (currentBlindSnapshot.contains(entity.getUuid()) && entity.getDistance() < minDist) {
                        minDist = entity.getDistance();
                        primaryBlind = entity;
                    }
                }
                if (primaryBlind != null) {
                    String blindDirection = computeDirectionLabel(primaryBlind.getHorizontalAngle());
                    String blindType = extractEntityType(primaryBlind);
                    mainText = textProvider.getLevel4BlindSpotText(blindDirection, blindType);
                }
            } else if (hasNewFrontal) {
                NearbyEntityData primaryFrontal = null;
                double minDist = Double.MAX_VALUE;
                for (NearbyEntityData entity : hostilesInAlert) {
                    if (newFrontalUuids.contains(entity.getUuid()) && entity.getDistance() < minDist) {
                        minDist = entity.getDistance();
                        primaryFrontal = entity;
                    }
                }
                if (primaryFrontal != null) {
                    String frontalDirection = computeDirectionLabel(primaryFrontal.getHorizontalAngle());
                    mainText = frontalDirection + textProvider.getLevel4SightEngageText();
                }
            }

            if (mainText != null) {
                if (isPeaceFirstStrike && !hasBroadcastedThreatList) {
                    String content = buildInsightThreatListContent(hostilesInRadar);
                    if (!content.isEmpty()) {
                        mainText += "。" + textProvider.getLevel4ThreatListText(content);
                        hasBroadcastedThreatList = true;
                    }
                }
                pendingLevel4Speech = mainText;
                isLevel4Interruptive = true;
            }
        }

        // 脱战重置
        if (!currentAllThreatUuids.isEmpty()) {
            alertCooldownTimer = 0;
        } else {
            if (hasActiveThreat || pendingLevel4Speech != null) {
                alertCooldownTimer++;
                if (alertCooldownTimer >= DISAPPEAR_TICKS_LV4) {
                    hasActiveThreat = false;
                    pendingLevel4Speech = null;
                    isLevel4Interruptive = false;
                    hasBroadcastedThreatList = false;
                    alertCooldownTimer = 0;
                    knownThreatUuids.clear();
                    historicalBlindSet.clear(); // 记得改名
                    wasBlindSpotEmpty = true;
                }
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
                indicators.add(new RadarIndicator(type, entity.getDisplayName(), entity.getHorizontalAngle(), entity.getDistance()));
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

    private String buildInsightThreatListContent(List<NearbyEntityData> hostilesInRadar) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (NearbyEntityData entity : hostilesInRadar) {
            if (entity.isLineOfSight()) {
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
            if (extractEntityType(e).equals(type)) return e.getDisplayName();
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
        try { return this.playerState.getCurrentDynamicFov(); } catch (Exception ignored) {}
        return 70.0f;
    }

    public RadarOutput getLastOutput() { return lastOutput; }

    public void shutdown() {
    }

    public void reset() {
        latestRadarTargetMap.clear();
        indicatorExpiryMap.clear();
        entityPool.clear();
        entityOutOfRangeTimers.clear();
        level3PresenceSet.clear();
        hasActiveThreat = false;
        alertCooldownTimer = 0;
        pendingLevel3Speech = null;
        pendingLevel3Type = null;
        pendingLevel4Speech = null;
        hasBroadcastedThreatList = false;
        knownThreatUuids.clear();
        historicalBlindSet.clear();
        wasBlindSpotEmpty = true;
        isTTSBusy = false;
        lastOutput = null;
        currentTick = 0;
    }
}
