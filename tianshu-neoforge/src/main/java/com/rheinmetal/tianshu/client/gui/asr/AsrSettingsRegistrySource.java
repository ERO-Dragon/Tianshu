package com.rheinmetal.tianshu.client.gui.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.gui.settings.api.TextBlockLevel;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.screen.TianshuSettingsScreen;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;
import com.rheinmetal.tianshu.function.asr.AsrModelService;
import com.rheinmetal.tianshu.function.asr.AsrModuleRuntimeControl;
import com.rheinmetal.tianshu.function.asr.settings.AsrSettingsApplier;
import com.rheinmetal.tianshu.function.asr.settings.AsrSettingsRuntimeActions;
import com.rheinmetal.tianshu.function.asr.settings.AsrSettingsSnapshot;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistration;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AsrSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static final String MODULE_ID = "module.asr";
    private static final Component TITLE = asr("title");
    private static final Component DESCRIPTION = asr("description");

    private static Component asr(String key, Object... args) {
        return Component.translatable("tianshu.gui.asr." + key, args);
    }

    private static Component common(String key, Object... args) {
        return Component.translatable("tianshu.gui.common." + key, args);
    }

    private final TianshuCoreManager coreManager;
    private final ClientConfig config;
    private final IAudioBridge audioBridge;

    public AsrSettingsRegistrySource(TianshuCoreManager coreManager, ClientConfig config, IAudioBridge audioBridge) {
        this.coreManager = coreManager;
        this.config = config;
        this.audioBridge = audioBridge;
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null || coreManager == null || config == null || audioBridge == null) {
            return;
        }
        registry.registerCategory(ModuleSettingsCategory.builder(MODULE_ID)
                .title(TITLE)
                .description(DESCRIPTION)
                .order(20)
                .panel(this::buildPanel)
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context) {
        AsrSettingsDraft draft = new AsrSettingsDraft(config, audioBridge, coreManager, context);
        context.settingsSessions().registerOrReplace(draft);

        panel.text("asr.intro", asr("intro"), TextBlockLevel.INFO)
                .enable("asr.enabled", asr("enabled"), draft.enabled)
                .options("as.main", asr("section.main"), draft::buildMainOptions)
                .toggles("asr.processing", asr("section.processing"), draft.enabled::get, group -> group
                        .toggle("asr.high_pass", asr("option.high_pass"), draft.highPassFilterEnabled, draft.enabled::get)
                        .toggle("asr.vad", asr("option.vad"), draft.vadEnabled, draft.enabled::get))
                .status("asr.status", asr("section.status"), status -> status
                        .row("asr.status.model", asr("row.model"), draft::selectedModelStatus)
                        .row("asr.status.download", asr("row.download_status"), draft::downloadStatus)
                        .row("asr.status.preview.state", asr("row.preview_state"), draft::previewStateStatus)
                        .row("asr.status.preview.result", asr("row.preview_result"), draft::previewResultStatus))
                .enable("asr.hotwords.expand", asr("hotwords.expand"), draft.hotwordsExpanded::get, draft.hotwordsExpanded::set)
                .status("asr.hotwords.asr", asr("section.hotwords"), () -> true, draft.hotwordsExpanded::get, status -> status
                        .row("asr.hotwords.modname", asr("row.mod_name"), () -> asr("hotwords.tianshu"))
                        .row("asr.hotwords.count", asr("row.hotword_count"), draft::hotwordCountStatus))
                .status("asr.hotwords.summary", asr("section.hotword_summary"), () -> true, draft.hotwordsExpanded::get, status -> status
                        .row("asr.hotwords.count", asr("row.hotword_count"), draft::hotwordCountStatus)
                        .row("asr.hotwords.duplicates", asr("row.hotword_duplicates"), draft::duplicateHotwordStatus)
                        .row("asr.hotwords.contains", asr("row.hotword_contains"), draft::containedHotwordStatus))
                .<HotwordDiagnostic>list("asr.hotwords.words", asr("section.hotword_words"), () -> true, draft.hotwordsExpanded::get, list -> list
                        .items(draft::hotwordDiagnostics)
                        .label(HotwordDiagnostic::label)
                        .emptyText(asr("hotwords.empty")))
                .actions("asr.preview", asr("section.preview"), actions -> actions
                        .button("asr.preview.start", asr("action.preview_start"), () -> draft.startPreview(context), () -> draft.enabled.get() && draft.canPreview())
                        .button("asr.preview.stop", asr("action.preview_stop"), draft::stopPreview, draft::previewRunning))
                .separator("asr.download.separator")
                .enable("asr.download.expand", asr("download.expand"), draft.downloadExpanded::get, draft.downloadExpanded::set)
                .options("asr.download.advanced", asr("section.download_advanced"), () -> true, draft.downloadExpanded::get, draft::buildDownloadAdvancedOptions)
                .options("asr.download.filters", asr("section.download_filters"), () -> true, draft.downloadExpanded::get, draft::buildDownloadFilters)
                .<AsrModelInfo>list("asr.download.models", asr("section.download_models"), () -> true, draft.downloadExpanded::get, list -> list
                        .items(() -> draft.filteredModels())
                        .label(draft::modelLabel)
                        .itemActions((model, actions) -> draft.buildDownloadItemActions(context, model, actions))
                        .emptyText(asr("download.empty")));
    }

    private static final class AsrSettingsDraft implements com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession {
        private static final String ALL = "__all__";
        private static final String DEFAULT_MIC = "__default__";

        private final ClientConfig config;
        private final IAudioBridge audioBridge;
        private final TianshuCoreManager coreManager;
        private final ModuleSettingsContext context;
        private final MutableSettingsValue<Boolean> enabled;
        private final MutableSettingsValue<String> selectedMic;
        private final MutableSettingsValue<TriggerMode> triggerMode;
        private final MutableSettingsValue<String> selectedModelName;
        private final MutableSettingsValue<Boolean> highPassFilterEnabled;
        private final MutableSettingsValue<Boolean> vadEnabled;
        private final MutableSettingsValue<Boolean> hotwordsExpanded;
        private final MutableSettingsValue<Boolean> downloadExpanded;
        private final MutableSettingsValue<String> githubProxyUrl;
        private final MutableSettingsValue<String> languageFilter;
        private final MutableSettingsValue<String> performanceFilter;
        private final MutableSettingsValue<String> qualityFilter;
        private final MutableSettingsValue<String> recommendedFilter;
        private final MutableSettingsValue<SortMode> sortMode;
        private final List<AsrModelInfo> catalog;
        private final AtomicBoolean previewRunning = new AtomicBoolean(false);
        private final AtomicBoolean downloading = new AtomicBoolean(false);
        private volatile AsrModelInfo activeDownloadModel;
        private volatile Component downloadLabel = asr("status.idle");
        private volatile int downloadProgress = 0;
        private volatile Component previewStateText = asr("status.idle");
        private volatile Component previewResultText = common("dash");

        private AsrSettingsDraft(ClientConfig config, IAudioBridge audioBridge, TianshuCoreManager coreManager, ModuleSettingsContext context) {
            this.config = config;
            this.audioBridge = audioBridge;
            this.coreManager = coreManager;
            this.context = context;
            this.catalog = AsrModelManager.getAllModels();
            this.enabled = new MutableSettingsValue<>(config::isAsrEnabled, config::setAsrEnabled);
            this.selectedMic = new MutableSettingsValue<>(this::currentMicName, ignored -> {}, Objects::nonNull);
            this.triggerMode = new MutableSettingsValue<>(config::getTriggerMode, config::setTriggerMode, Objects::nonNull);
            this.selectedModelName = new MutableSettingsValue<>(this::currentModelName, ignored -> {}, Objects::nonNull);
            this.highPassFilterEnabled = new MutableSettingsValue<>(config::isAsrHighPassFilterEnabled, config::setAsrHighPassFilterEnabled);
            this.vadEnabled = new MutableSettingsValue<>(config::isAsrVadEnabled, config::setAsrVadEnabled);
            this.hotwordsExpanded = new MutableSettingsValue<>(() -> false, ignored -> {});
            this.downloadExpanded = new MutableSettingsValue<>(() -> false, ignored -> {});
            this.githubProxyUrl = new MutableSettingsValue<>(config::getAsrGithubProxyUrl, config::setAsrGithubProxyUrl, Objects::nonNull);
            this.languageFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.performanceFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.qualityFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.recommendedFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.sortMode = new MutableSettingsValue<>(() -> SortMode.RECOMMENDED, ignored -> {}, Objects::nonNull);
            restoreDownloadState();
        }

        private void buildMainOptions(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("asr.model", asr("option.model"), modelNames(), selectedModelName, this::modelOptionLabel, enabled::get)
                    .select("asr.mic", asr("option.mic"), micNames(), selectedMic, this::micLabel, enabled::get)
                    .select("asr.trigger", asr("option.trigger"), List.of(TriggerMode.values()), triggerMode, this::triggerLabel, enabled::get);
        }

        private void buildDownloadAdvancedOptions(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.text("asr.download.githubProxy", asr("option.github_proxy"), githubProxyUrl);
        }

        private void buildDownloadFilters(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("asr.download.lang", asr("option.language"), filterValues(this::languageTags), languageFilter, this::filterOptionLabel)
                    .select("asr.download.performance", asr("option.performance"), scoreFilterValues(AsrModelInfo::getPerformanceScore), performanceFilter, this::scoreFilterLabel)
                    .select("asr.download.quality", asr("option.quality"), scoreFilterValues(AsrModelInfo::getRecognitionQualityScore), qualityFilter, this::scoreFilterLabel)
                    .select("asr.download.recommended", asr("option.recommended"), scoreFilterValues(AsrModelInfo::getRecommendationScore), recommendedFilter, this::scoreFilterLabel)
                    .select("asr.download.sort", asr("option.sort"), List.of(SortMode.values()), sortMode, SortMode::label);
        }

        private void buildDownloadItemActions(ModuleSettingsContext context, AsrModelInfo info, com.rheinmetal.tianshu.client.gui.settings.api.ItemActionTemplate<AsrModelInfo> actions) {
            actions.button("asr.download.item.start", asr("action.download"), SettingsButtonStyle.PRIMARY, model -> startDownload(context, model), model -> model != null && !downloading.get() && !isDownloaded(model))
                    .button("asr.download.item.pause", asr("action.pause"), model -> pauseDownload(), model -> isActiveDownload(model) && !asrModelService().isDownloadPaused())
                    .button("asr.download.item.resume", asr("action.resume"), model -> resumeDownload(), model -> isActiveDownload(model) && asrModelService().isDownloadPaused())
                    .button("asr.download.item.cancel", asr("action.cancel"), SettingsButtonStyle.DANGER, model -> cancelDownload(), this::isActiveDownload)
                    .button("asr.download.item.delete", asr("action.delete"), SettingsButtonStyle.DANGER, model -> deleteModel(context, model), model -> model != null && !downloading.get() && isDownloaded(model));
        }

        @Override
        public String moduleId() {
            return MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty()
                    || selectedMic.dirty()
                    || triggerMode.dirty()
                    || selectedModelName.dirty()
                    || highPassFilterEnabled.dirty()
                    || vadEnabled.dirty()
                    || githubProxyUrl.dirty();
        }

        @Override
        public SettingsValidationResult validate() {
            if (enabled.get() && selectedModelName.get() != null && !selectedModelName.get().isBlank() && (!selectedModelName.valid() || resolveModel(selectedModelName.get()) == null)) {
                return SettingsValidationResult.failure(asr("validation.invalid_model"));
            }
            AsrModelInfo selected = resolveModel(selectedModelName.get());
            if (enabled.get() && selected != null && !isDownloaded(selected)) {
                return SettingsValidationResult.failure(asr("validation.model_not_installed"));
            }
            return SettingsValidationResult.successful();
        }

        @Override
        public SettingsSaveResult save() {
            AsrSettingsSnapshot before = AsrSettingsSnapshot.from(config);
            enabled.save();
            triggerMode.save();
            highPassFilterEnabled.save();
            vadEnabled.save();
            githubProxyUrl.save();
            String mic = selectedMic.get();
            config.setSelectedMicName(DEFAULT_MIC.equals(mic) ? "" : mic);
            selectedMic.save();
            config.setCustomAsrName(selectedModelName.get());
            selectedModelName.save();
            config.save();
            AsrSettingsSnapshot after = AsrSettingsSnapshot.from(config);
            settingsApplier().apply(before, after);
            return SettingsSaveResult.success(asr("message.saved"), false, true);
        }

        @Override
        public void reset() {
            enabled.reset();
            selectedMic.reset();
            triggerMode.reset();
            selectedModelName.reset();
            highPassFilterEnabled.reset();
            vadEnabled.reset();
            githubProxyUrl.reset();
        }

        private List<String> micNames() {
            List<String> names = new ArrayList<>();
            names.add(DEFAULT_MIC);
            for (String name : audioBridge.getAvailableMicNames()) {
                if (name != null && !name.isBlank() && !names.contains(name)) {
                    names.add(name);
                }
            }
            return names;
        }

        private Component micLabel(String name) {
            if (name == null || name.isBlank() || DEFAULT_MIC.equals(name)) {
                return asr("option.default_mic");
            }
            return Component.literal(name);
        }

        private Component triggerLabel(TriggerMode mode) {
            if (mode == TriggerMode.PUSH_TO_TALK) {
                return asr("trigger.push_to_talk");
            }
            if (mode == TriggerMode.ALWAYS) {
                return asr("trigger.always");
            }
            return Component.literal(String.valueOf(mode));
        }

        private String currentMicName() {
            String configured = config.getSelectedMicName();
            return configured == null || configured.isBlank() ? DEFAULT_MIC : configured;
        }

        private String currentModelName() {
            String custom = config.getCustomAsrName();
            if (custom != null && !custom.isBlank()) {
                AsrModelInfo customModel = resolveModel(custom);
                if (customModel != null) {
                    return custom;
                }
            }
            return "";
        }

        private List<String> modelNames() {
            List<String> downloaded = catalog.stream()
                    .filter(this::isDownloaded)
                    .sorted(Comparator.comparing(AsrModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                    .map(AsrModelInfo::localKey)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
            List<String> values = new ArrayList<>(downloaded.size() + 2);
            values.add("");
            String configured = config.getCustomAsrName();
            if (configured != null && !configured.isBlank() && resolveModel(configured) != null && downloaded.stream().noneMatch(name -> name.equalsIgnoreCase(configured))) {
                values.add(configured.trim());
            }
            values.addAll(downloaded);
            return values;
        }

        private AsrModelInfo resolveModel(String name) {
            return AsrModelManager.getModelByLocalKey(name);
        }

        private Component modelOptionLabel(String name) {
            if (name == null || name.isBlank()) {
                return common("not_selected");
            }
            AsrModelInfo info = resolveModel(name);
            return info == null ? Component.literal(name == null ? "" : name) : Component.literal(info.getDisplayName());
        }

        private Component selectedModelStatus() {
            AsrModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) {
                return common("not_selected");
            }
            return asr("model.selected", info.getDisplayName(), common(isDownloaded(info) ? "downloaded" : "not_downloaded"));
        }

        private Component downloadStatus() {
            if (downloading.get()) {
                return asr("download.progress", downloadLabel, downloadProgress);
            }
            return downloadLabel;
        }

        private Component previewStateStatus() {
            return previewStateText;
        }

        private Component previewResultStatus() {
            return previewResultText;
        }

        private Component hotwordCountStatus() {
            List<HotwordDiagnostic> diagnostics = hotwordDiagnostics();
            long warningCount = diagnostics.stream().filter(HotwordDiagnostic::warning).count();
            return warningCount > 0 ? asr("hotwords.count_with_warnings", diagnostics.size(), warningCount) : asr("hotwords.count", diagnostics.size());
        }

        private Component duplicateHotwordStatus() {
            long duplicateCount = hotwordDiagnostics().stream().filter(HotwordDiagnostic::duplicate).count();
            return duplicateCount == 0 ? asr("hotwords.none_found") : asr("hotwords.duplicate_count", duplicateCount);
        }

        private Component containedHotwordStatus() {
            long containedCount = hotwordDiagnostics().stream().filter(HotwordDiagnostic::contained).count();
            return containedCount == 0 ? asr("hotwords.none_found") : asr("hotwords.contained_count", containedCount);
        }

        private List<HotwordDiagnostic> hotwordDiagnostics() {
            VoiceTriggerRegistry registry = coreManager.protocolRuntime().voiceTriggers();
            List<VoiceTriggerRegistration> registrations = registry.registrations();
            List<HotwordEntry> entries = new ArrayList<>();
            for (VoiceTriggerRegistration registration : registrations) {
                addRegisteredWords(entries, registration.moduleId(), "hotwords.kind.hotword", registration.wakeWords());
                addRegisteredWords(entries, registration.moduleId(), "hotwords.kind.extra_word", registration.extraWords());
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (HotwordEntry entry : entries) {
                counts.merge(entry.normalized(), 1, Integer::sum);
            }
            List<HotwordDiagnostic> diagnostics = new ArrayList<>();
            for (HotwordEntry entry : entries) {
                boolean duplicate = counts.getOrDefault(entry.normalized(), 0) > 1;
                boolean contained = entries.stream().anyMatch(other -> !other.normalized().equals(entry.normalized()) && other.normalized().contains(entry.normalized()));
                diagnostics.add(new HotwordDiagnostic(entry.moduleId(), entry.kindKey(), entry.word(), duplicate, contained));
            }
            return diagnostics.stream()
                    .sorted(Comparator.comparing(HotwordDiagnostic::warning).reversed()
                            .thenComparing(HotwordDiagnostic::moduleId, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(HotwordDiagnostic::word, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        private void addRegisteredWords(List<HotwordEntry> entries, String moduleId, String kind, List<String> words) {
            for (String word : words) {
                addHotword(entries, moduleId, kind, word);
            }
        }

        private void addHotword(List<HotwordEntry> entries, String moduleId, String kind, String word) {
            String normalized = normalizeHotword(word);
            if (!normalized.isBlank()) {
                entries.add(new HotwordEntry(moduleId, kind, word.trim(), normalized));
            }
        }

        private String normalizeHotword(String word) {
            return word == null ? "" : word.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        }

        private boolean canPreview() {
            AsrModelInfo info = resolveModel(selectedModelName.get());
            return info != null && isDownloaded(info) && !previewRunning.get();
        }

        private boolean previewRunning() {
            return previewRunning.get() || asrModelService().isPreviewRunning();
        }

        private void startPreview(ModuleSettingsContext context) {
            AsrModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) {
                context.showStatus(asr("validation.invalid_model"), 3000);
                return;
            }
            if (!isDownloaded(info)) {
                context.showStatus(asr("message.model_not_downloaded"), 3000);
                return;
            }
            previewStateText = asr("status.preview_preparing");
            previewResultText = common("dash");
            previewRunning.set(true);
            asrModelService().preview(info, new AsrModelService.PreviewCallback() {
                @Override
                public void onReady() {
                    runOnClient(() -> previewStateText = asr("status.recording"));
                }

                @Override
                public void onResult(String text) {
                    runOnClient(() -> {
                        previewStateText = asr("status.recognition_complete");
                        previewResultText = text == null || text.isBlank() ? asr("status.no_recognition_result") : Component.literal(text);
                    });
                }

                @Override
                public void onError(String message) {
                    runOnClient(() -> {
                        previewStateText = asr("status.preview_failed");
                        previewResultText = message == null ? asr("status.preview_failed") : Component.literal(message);
                        context.showStatus(previewResultText, 4000);
                    });
                }

                @Override
                public void onFinish() {
                    runOnClient(() -> previewRunning.set(false));
                }
            });
        }

        private void stopPreview() {
            asrModelService().stopPreview();
            previewRunning.set(false);
            previewStateText = asr("status.stopped");
        }

        private void startDownload(ModuleSettingsContext context, AsrModelInfo info) {
            if (info == null || downloading.get()) {
                return;
            }
            activeDownloadModel = info;
            downloading.set(true);
            downloadLabel = asr("status.download_preparing");
            downloadProgress = 0;
            asrModelService().downloadModel(info, githubProxyUrl.get(), new AsrModelService.DownloadProgressCallback() {
                @Override
                public void onProgress(String label, int percent) {
                    runOnClient(() -> {
                        downloadLabel = label == null ? asr("status.downloading") : Component.literal(label);
                        downloadProgress = percent;
                        refreshSettingsScreen();
                    });
                }

                @Override
                public void onComplete() {
                    runOnClient(() -> {
                        downloading.set(false);
                        activeDownloadModel = null;
                        downloadLabel = asr("status.download_complete");
                        downloadProgress = 100;
                        context.showStatus(asr("message.download_complete"), 3000);
                        coreManager.refreshRuntimeAsync(RuntimeRefreshReason.RESOURCE_CHANGED, null);
                        refreshSettingsScreen();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnClient(() -> {
                        downloading.set(false);
                        activeDownloadModel = null;
                        downloadLabel = message == null ? asr("status.download_failed") : Component.literal(message);
                        context.showStatus(downloadLabel, 4000);
                        refreshSettingsScreen();
                    });
                }
            });
        }

        private void restoreDownloadState() {
            AsrModelService.DownloadStatus status = asrModelService().downloadStatus();
            if (status == null) {
                activeDownloadModel = null;
                downloading.set(false);
                return;
            }
            if (!status.downloading()) {
                activeDownloadModel = null;
                downloading.set(false);
                if (status.label() != null && !status.label().isBlank()) {
                    downloadLabel = Component.literal(status.label());
                    downloadProgress = Math.max(0, Math.min(100, status.progress()));
                }
                return;
            }
            activeDownloadModel = resolveModel(status.activeModelKey());
            downloading.set(true);
            downloadLabel = status.paused()
                    ? asr("status.paused")
                    : status.label() == null || status.label().isBlank() ? asr("status.downloading") : Component.literal(status.label());
            downloadProgress = Math.max(0, Math.min(100, status.progress()));
        }

        private void pauseDownload() {
            asrModelService().pauseDownload();
            downloadLabel = asr("status.paused");
        }

        private void resumeDownload() {
            asrModelService().resumeDownload();
            downloadLabel = asr("status.downloading");
        }

        private void cancelDownload() {
            asrModelService().cancelDownload();
            downloading.set(false);
            activeDownloadModel = null;
            downloadLabel = asr("status.cancelling");
            downloadProgress = 0;
        }

        private boolean isActiveDownload(AsrModelInfo info) {
            AsrModelService.DownloadStatus status = asrModelService().downloadStatus();
            if (status != null && status.downloading()) {
                return info != null && sameModel(info, resolveModel(status.activeModelKey()));
            }
            return info != null && downloading.get() && sameModel(info, activeDownloadModel);
        }

        private void deleteModel(ModuleSettingsContext context, AsrModelInfo info) {
            if (info == null || downloading.get()) {
                return;
            }
            asrModelService().deleteModel(info);
            downloadLabel = asr("message.deleted", info.getDisplayName());
            context.showStatus(downloadLabel, 3000);
            coreManager.refreshRuntimeAsync(RuntimeRefreshReason.RESOURCE_CHANGED, null);
        }

        private boolean sameModel(AsrModelInfo left, AsrModelInfo right) {
            if (left == right) {
                return true;
            }
            if (left == null || right == null) {
                return false;
            }
            return left.localKey().equalsIgnoreCase(right.localKey());
        }

        private List<AsrModelInfo> filteredModels() {
            return catalog.stream()
                    .filter(this::matchesFilters)
                    .sorted(modelComparator())
                    .toList();
        }

        private Comparator<AsrModelInfo> modelComparator() {
            SortMode mode = sortMode.get();
            if (mode == SortMode.QUALITY) {
                return Comparator.comparingInt(AsrModelInfo::getQualityScore).reversed()
                        .thenComparing(Comparator.comparingInt(AsrModelInfo::getPerformanceScore).reversed())
                        .thenComparing(AsrModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            }
            if (mode == SortMode.PERFORMANCE) {
                return Comparator.comparingInt(AsrModelInfo::getPerformanceScore).reversed()
                        .thenComparing(Comparator.comparingInt(AsrModelInfo::getQualityScore).reversed())
                        .thenComparing(AsrModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            }
            if (mode == SortMode.NAME) {
                return Comparator.comparing(AsrModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            }
            return Comparator.comparingInt(AsrModelInfo::getValueScore).reversed()
                    .thenComparing(Comparator.comparingInt(AsrModelInfo::getRecommendationScore).reversed())
                    .thenComparing(Comparator.comparingInt(AsrModelInfo::getQualityScore).reversed())
                    .thenComparing(Comparator.comparingInt(AsrModelInfo::getPerformanceScore).reversed())
                    .thenComparing(AsrModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
        }

        private boolean matchesFilters(AsrModelInfo info) {
            return matches(languageFilter.get(), languageTags(info))
                    && matchesScore(performanceFilter.get(), info.getPerformanceScore())
                    && matchesScore(qualityFilter.get(), info.getRecognitionQualityScore())
                    && matchesScore(recommendedFilter.get(), info.getRecommendationScore());
        }

        private boolean matches(String filter, List<String> values) {
            if (filter == null || ALL.equals(filter)) {
                return true;
            }
            for (String value : values) {
                if (filter.equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }

        private Component modelLabel(AsrModelInfo info) {
            if (info == null) {
                return Component.empty();
            }
            return asr("download.card", info.getDisplayName(), common(isDownloaded(info) ? "downloaded" : "not_downloaded"), tagLabel(info), languageLabel(info), scoreLabel(info.getRecognitionQualityScore()), scoreLabel(info.getPerformanceScore()), scoreLabel(info.getRecommendationScore()));
        }

        private boolean isDownloaded(AsrModelInfo info) {
            return AsrModelManager.isModelDownloaded(info, config.getAsrBasePath().resolve("model"));
        }

        private List<String> filterValues(java.util.function.Function<AsrModelInfo, List<String>> mapper) {
            Set<String> values = new LinkedHashSet<>();
            values.add(ALL);
            for (AsrModelInfo info : catalog) {
                values.addAll(mapper.apply(info));
            }
            return List.copyOf(values);
        }

        private List<String> scoreFilterValues(java.util.function.ToIntFunction<AsrModelInfo> mapper) {
            Set<String> values = new LinkedHashSet<>();
            values.add(ALL);
            for (AsrModelInfo info : catalog) {
                values.add(String.valueOf(mapper.applyAsInt(info)));
            }
            return List.copyOf(values);
        }

        private List<String> languageTags(AsrModelInfo info) {
            return info == null ? List.of() : info.getLang().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .toList();
        }

        private Component filterOptionLabel(String value) {
            return ALL.equals(value) ? common("all") : Component.literal(value);
        }

        private Component scoreFilterLabel(String value) {
            return ALL.equals(value) ? common("all") : asr("score.at_least", value);
        }

        private boolean matchesScore(String filter, int score) {
            if (filter == null || ALL.equals(filter)) {
                return true;
            }
            try {
                return score >= Integer.parseInt(filter);
            } catch (NumberFormatException e) {
                return true;
            }
        }

        private String scoreLabel(int score) {
            return score + "/10";
        }

        private String languageLabel(AsrModelInfo info) {
            List<String> tags = languageTags(info);
            return tags.isEmpty() ? "-" : String.join(",", tags);
        }

        private String tagLabel(AsrModelInfo info) {
            List<String> tags = info == null ? List.of() : info.getTags();
            return tags.isEmpty() ? "-" : String.join(", ", tags);
        }

        private AsrModelService asrModelService() {
            return coreManager.requireService(AsrModelService.class);
        }

        private AsrSettingsApplier settingsApplier() {
            return new AsrSettingsApplier(new AsrSettingsRuntimeActions() {
                @Override
                public void releaseVoiceInputResources() {
                    coreManager.findService(AsrModuleRuntimeControl.class).ifPresent(AsrModuleRuntimeControl::releaseInputResources);
                }

                @Override
                public void reconfigureAudioPipeline() {
                    coreManager.findService(AsrModuleRuntimeControl.class).ifPresent(AsrModuleRuntimeControl::reconfigureAudioPipeline);
                }

                @Override
                public void restartRuntime(com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason reason) {
                    coreManager.restartRuntimeAsync(reason, null);
                }
            }, audioBridge);
        }

        private void runOnClient(Runnable runnable) {
            Minecraft.getInstance().execute(runnable);
        }

        private void refreshSettingsScreen() {
            if (Minecraft.getInstance().screen instanceof TianshuSettingsScreen settingsScreen) {
                settingsScreen.rebuildCurrentPage();
            }
        }
    }

    private enum SortMode {
        RECOMMENDED("sort.recommended"),
        QUALITY("sort.quality"),
        PERFORMANCE("sort.performance"),
        NAME("sort.name");

        private final String key;

        SortMode(String key) {
            this.key = key;
        }

        private Component label() {
            return asr(key);
        }
    }

    private record HotwordEntry(String moduleId, String kindKey, String word, String normalized) {
        private HotwordEntry {
            moduleId = moduleId == null || moduleId.isBlank() ? "unknown" : moduleId;
            kindKey = kindKey == null || kindKey.isBlank() ? "hotwords.kind.hotword" : kindKey;
            word = word == null ? "" : word.trim();
            normalized = normalized == null ? "" : normalized;
        }
    }

    private record HotwordDiagnostic(String moduleId, String kindKey, String word, boolean duplicate, boolean contained) {
        private boolean warning() {
            return duplicate || contained;
        }

        private Component label() {
            return asr("hotwords.diagnostic", marker(), moduleId, asr(kindKey), word);
        }

        private Component marker() {
            if (duplicate && contained) {
                return asr("hotwords.marker.duplicate_contained");
            }
            if (duplicate) {
                return asr("hotwords.marker.duplicate");
            }
            if (contained) {
                return asr("hotwords.marker.contained");
            }
            return asr("hotwords.marker.normal");
        }
    }
}
