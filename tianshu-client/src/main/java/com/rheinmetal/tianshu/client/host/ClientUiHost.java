package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.api.text.UiText;

public interface ClientUiHost {
    void openSettings();

    void requestSettingsRefresh();

    void showStatus(UiText text, long durationMillis);
}
