package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.ui.UiText;

public interface ClientUiHost {
    void openSettings();

    void requestSettingsRefresh();

    void showStatus(UiText text, long durationMillis);
}
