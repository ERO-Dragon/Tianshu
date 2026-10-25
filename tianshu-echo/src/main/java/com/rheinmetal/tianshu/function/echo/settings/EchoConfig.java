package com.rheinmetal.tianshu.function.echo.settings;

import com.rheinmetal.tianshu.api.config.ModuleConfigStore;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Echo's own settings.
 *
 * <p>The module owns both halves: the keys it persists and the meaning of their defaults. The kernel
 * only supplies the store, so a new option is added here and nowhere else.</p>
 */
public final class EchoConfig implements EchoConfiguration, EchoSettingsAccess {

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STREAMING = "streamingSynthesis";
    private static final String KEY_PREVIEW_TEXT = "previewText";
    private static final String KEY_GITHUB_PROXY = "githubProxyUrl";
    private static final String KEY_CUSTOM_NAME = "customTtsName";

    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_STREAMING = true;
    private static final String DEFAULT_PREVIEW_TEXT = "";
    private static final String DEFAULT_GITHUB_PROXY = "https://gh-proxy.org/";
    private static final String DEFAULT_CUSTOM_NAME = "";

    private final ModuleConfigStore store;
    private final Path basePath;

    public EchoConfig(ModuleConfigStore store, Path moduleDirectory) {
        this.store = Objects.requireNonNull(store, "store");
        this.basePath = Objects.requireNonNull(moduleDirectory, "moduleDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve("tts");
    }

    @Override
    public boolean isTtsEnabled() {
        return store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    @Override
    public String getCustomTtsName() {
        return store.getString(KEY_CUSTOM_NAME, DEFAULT_CUSTOM_NAME);
    }

    @Override
    public Path getTtsBasePath() {
        return basePath;
    }

    @Override
    public Path getVoiceLibraryPath() {
        return basePath.resolve("voices");
    }

    @Override
    public boolean isTtsStreamingSynthesisEnabled() {
        return store.getBoolean(KEY_STREAMING, DEFAULT_STREAMING);
    }

    @Override
    public void setTtsEnabled(boolean enabled) {
        store.setBoolean(KEY_ENABLED, enabled);
    }

    @Override
    public void setTtsStreamingSynthesisEnabled(boolean enabled) {
        store.setBoolean(KEY_STREAMING, enabled);
    }

    @Override
    public String getTtsPreviewText() {
        return store.getString(KEY_PREVIEW_TEXT, DEFAULT_PREVIEW_TEXT);
    }

    @Override
    public void setTtsPreviewText(String text) {
        store.setString(KEY_PREVIEW_TEXT, text == null ? "" : text.trim());
    }

    @Override
    public String getTtsGithubProxyUrl() {
        return store.getString(KEY_GITHUB_PROXY, DEFAULT_GITHUB_PROXY);
    }

    @Override
    public void setTtsGithubProxyUrl(String url) {
        store.setString(KEY_GITHUB_PROXY, url == null ? "" : url.trim());
    }

    @Override
    public void setCustomTtsName(String name) {
        store.setString(KEY_CUSTOM_NAME, name == null ? "" : name);
    }

    @Override
    public void save() {
        store.save();
    }
}
