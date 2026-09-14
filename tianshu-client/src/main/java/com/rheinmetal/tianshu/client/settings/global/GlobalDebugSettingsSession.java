package com.rheinmetal.tianshu.client.settings.global;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveTransaction;

import java.util.Objects;

public final class GlobalDebugSettingsSession implements ModuleSettingsSession {
    public static final String SESSION_ID = "settings.debug";

    private final GlobalDebugSettingsAccess config;
    private final MutableSettingsValue<Boolean> enabled;

    public GlobalDebugSettingsSession(GlobalDebugSettingsAccess config) {
        this.config = Objects.requireNonNull(config, "config");
        this.enabled = new MutableSettingsValue<>(config::isDebugEnabled, config::setDebugEnabled);
    }

    @Override
    public String moduleId() {
        return SESSION_ID;
    }

    public boolean enabled() {
        return Boolean.TRUE.equals(enabled.get());
    }

    public void toggle() {
        enabled.set(!enabled());
    }

    @Override
    public boolean dirty() {
        return enabled.dirty();
    }

    @Override
    public SettingsSaveResult save() {
        boolean changed = dirty();
        new SettingsSaveTransaction(enabled).commit(config::save);
        return SettingsSaveResult.success(UiText.key("tianshu.gui.settings.debug.saved"), changed, false, false);
    }

    @Override
    public void reset() {
        enabled.reset();
    }
}
