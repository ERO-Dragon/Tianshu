package com.rheinmetal.tianshu.function.AcousticRadar;

public interface AlertTextProvider {
    String getLevel3DetectionText(String displayName);

    /**
     * 盲区遇袭：需要带方向和类型
     * @param direction 方位（如"右后方"）
     * @param entityType 敌人类型（如"苦力怕"），如果种类大于1则为"敌人"
     */
    String getLevel4BlindSpotText(String direction, String entityType);

    /**
     * 正面接敌
     */
    String getLevel4SightEngageText();

    /**
     * 战况通报：接敌后的详细数量播报
     * @param content 格式化好的内容，如"1只僵尸，2只苦力怕"
     */
    String getLevel4ThreatListText(String content);
}
