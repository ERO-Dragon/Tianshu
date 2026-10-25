package com.rheinmetal.tianshu.function.intake.settings;

import com.rheinmetal.tianshu.api.config.ModuleConfigStore;
import com.rheinmetal.tianshu.function.intake.model.AsrModelInfo;
import com.rheinmetal.tianshu.function.intake.model.AsrModelManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Intake's own settings.
 *
 * <p>This module owns the keys, their defaults, where its models live, and — importantly — the policy
 * for which model to use when the player never picked one. That policy used to sit in the platform
 * config host, which meant the kernel grew a rule for every feature; it belongs where the models are.</p>
 */
public final class IntakeConfig implements IntakeConfiguration, IntakeSettingsAccess {

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SELECTED_MIC = "selectedMicName";
    private static final String KEY_GITHUB_PROXY = "githubProxyUrl";
    private static final String KEY_HIGH_PASS = "highPassFilterEnabled";
    private static final String KEY_RNNOISE = "rnnoiseEnabled";
    private static final String KEY_CUSTOM_MODEL = "customModelName";

    private static final boolean DEFAULT_ENABLED = true;
    private static final String DEFAULT_SELECTED_MIC = "";
    private static final String DEFAULT_GITHUB_PROXY = "https://gh-proxy.org/";
    private static final boolean DEFAULT_HIGH_PASS = true;
    private static final boolean DEFAULT_RNNOISE = false;

    private final ModuleConfigStore store;
    private final Path basePath;
    private final Supplier<String> languageCode;

    public IntakeConfig(ModuleConfigStore store, Path moduleDirectory, Supplier<String> languageCode) {
        this.store = Objects.requireNonNull(store, "store");
        this.basePath = Objects.requireNonNull(moduleDirectory, "moduleDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve("asr");
        this.languageCode = languageCode == null ? () -> "" : languageCode;
    }

    @Override
    public boolean isAsrEnabled() {
        return store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    @Override
    public void setAsrEnabled(boolean enabled) {
        store.setBoolean(KEY_ENABLED, enabled);
    }

    @Override
    public String getSelectedMicName() {
        return store.getString(KEY_SELECTED_MIC, DEFAULT_SELECTED_MIC);
    }

    @Override
    public void setSelectedMicName(String name) {
        store.setString(KEY_SELECTED_MIC, name == null ? "" : name);
    }

    @Override
    public String getAsrGithubProxyUrl() {
        return store.getString(KEY_GITHUB_PROXY, DEFAULT_GITHUB_PROXY);
    }

    @Override
    public void setAsrGithubProxyUrl(String url) {
        store.setString(KEY_GITHUB_PROXY, url == null ? "" : url.trim());
    }

    @Override
    public boolean isAsrHighPassFilterEnabled() {
        return store.getBoolean(KEY_HIGH_PASS, DEFAULT_HIGH_PASS);
    }

    @Override
    public void setAsrHighPassFilterEnabled(boolean enabled) {
        store.setBoolean(KEY_HIGH_PASS, enabled);
    }

    @Override
    public boolean isAsrRnnoiseEnabled() {
        return store.getBoolean(KEY_RNNOISE, DEFAULT_RNNOISE);
    }

    public void setAsrRnnoiseEnabled(boolean enabled) {
        store.setBoolean(KEY_RNNOISE, enabled);
    }

    @Override
    public Path getAsrBasePath() {
        return basePath;
    }

    @Override
    public String getCustomAsrName() {
        String normalized = normalizeModelName(store.getString(KEY_CUSTOM_MODEL, ""));
        if (!normalized.isBlank()) {
            return normalized;
        }
        // Nothing was ever selected. Reporting "not configured" forever is what made the module look
        // unattached, so fall back the same way the embedding lane does and name a real catalog entry.
        return defaultModelName();
    }

    @Override
    public void setCustomAsrName(String name) {
        store.setString(KEY_CUSTOM_MODEL, normalizeModelName(name));
    }

    @Override
    public void save() {
        store.save();
    }

    /**
     * The model the speech lane uses when the player never picked one.
     *
     * <p>Preference order, and it matters: an already-downloaded model first (so a fallback never
     * silently replaces a model the player actually installed), then the catalog entry for the current
     * language, then the first catalog entry. The last two may still name a model that is not on disk on
     * purpose — the engine then reports "not installed", which the player can act on, instead of the
     * dead-end "not configured".
     */
    private String defaultModelName() {
        Path modelRoot = modelRoot();
        String language = languageCode.get();
        AsrModelInfo downloaded = firstDownloadedModel(modelRoot, language);
        if (downloaded == null) {
            downloaded = firstDownloadedModel(modelRoot, null);
        }
        if (downloaded != null) {
            return downloaded.localKey();
        }
        AsrModelInfo named = firstModelForLanguage(language);
        return named == null ? "" : named.localKey();
    }

    private Path modelRoot() {
        return basePath.resolve("model");
    }

    private AsrModelInfo firstDownloadedModel(Path modelRoot, String language) {
        for (AsrModelInfo info : catalogForLanguage(language)) {
            if (AsrModelManager.isModelDownloaded(info, modelRoot)) {
                return info;
            }
        }
        return null;
    }

    private AsrModelInfo firstModelForLanguage(String language) {
        List<AsrModelInfo> candidates = catalogForLanguage(language);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** The catalog filtered by language, falling back to the whole catalog when nothing matches. */
    private List<AsrModelInfo> catalogForLanguage(String language) {
        List<AsrModelInfo> all = AsrModelManager.getAllModels();
        String prefix = languagePrefix(language);
        if (prefix.isEmpty()) {
            return all;
        }
        List<AsrModelInfo> matching = all.stream()
                .filter(info -> listsLanguage(info, prefix))
                .toList();
        return matching.isEmpty() ? all : matching;
    }

    /** Catalog entries store bare codes ("zh"), while the game reports a locale ("zh_cn"). */
    private static boolean listsLanguage(AsrModelInfo info, String prefix) {
        for (String lang : info.getLang()) {
            if (lang != null && lang.trim().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String languagePrefix(String language) {
        if (language == null || language.isBlank()) {
            return "";
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return normalized.length() >= 2 ? normalized.substring(0, 2) : normalized;
    }

    private static String normalizeModelName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        AsrModelInfo info = AsrModelManager.getModelByLocalKey(name.trim());
        return info == null ? "" : info.localKey();
    }
}
