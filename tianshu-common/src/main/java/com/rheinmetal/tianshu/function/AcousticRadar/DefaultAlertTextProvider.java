package com.rheinmetal.tianshu.function.AcousticRadar;

public class DefaultAlertTextProvider implements AlertTextProvider {
    @Override
    public String getLevel3DetectionText(String displayName) {
        return "检测到" + displayName + "进入警戒区域";
    }

    @Override
    public String getLevel4BlindSpotText(String direction, String entityType) {
        return direction + entityType + "接近";
    }

    @Override
    public String getLevel4SightEngageText() {
        return  "接敌";
    }

    @Override
    public String getLevel4ThreatListText(String content) {
        return "警戒范围内有" + content;
    }
}
