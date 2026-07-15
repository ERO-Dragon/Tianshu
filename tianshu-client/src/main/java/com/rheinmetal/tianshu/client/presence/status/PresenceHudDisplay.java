package com.rheinmetal.tianshu.client.presence.status;

import com.rheinmetal.tianshu.client.presence.model.PresenceSeverity;
import com.rheinmetal.tianshu.client.presence.model.PresenceStatusType;

public record PresenceHudDisplay(
        boolean visible,
        String text,
        PresenceSeverity severity,
        PresenceStatusType statusType,
        String sourceModuleId
) {
    public static final PresenceHudDisplay HIDDEN = new PresenceHudDisplay(false, "", PresenceSeverity.INFO, PresenceStatusType.IDLE, "");

    public PresenceHudDisplay {
        text = text == null ? "" : text.trim();
        severity = severity == null ? PresenceSeverity.INFO : severity;
        statusType = statusType == null ? PresenceStatusType.IDLE : statusType;
        sourceModuleId = sourceModuleId == null ? "" : sourceModuleId.trim();
        visible = visible && !text.isBlank();
    }
}
