package com.rheinmetal.tianshu.client.settings.module.asr;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.client.api.settings.SettingsListCard;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.api.settings.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.host.ClientScheduler;
import com.rheinmetal.tianshu.client.host.ClientUiHost;
import com.rheinmetal.tianshu.client.presence.PresenceTextProvider;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;
import com.rheinmetal.tianshu.function.asr.AsrModelService;
import com.rheinmetal.tianshu.function.asr.AsrModuleRuntimeControl;
import com.rheinmetal.tianshu.function.asr.settings.AsrSettingsApplier;
import com.rheinmetal.tianshu.function.asr.settings.AsrSettingsRuntimeActions;
import com.rheinmetal.tianshu.function.asr.settings.AsrSettingsSnapshot;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.ModelDownloadProgress;
import com.rheinmetal.tianshu.model.ModelDownloadStage;
import com.rheinmetal.tianshu.model.AsrModelManager;
import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AsrSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static final String MODULE_ID = "module.asr";
    private static final UiText TITLE = asr("title");
    private static final UiText DESCRIPTION = asr("description");

    private static UiText asr(String key, Object... args) {
        return UiText.key("tianshu.gui.asr." + key, args);
    }

    private static UiText common(String key, Object... args) {
        return UiText.key("tianshu.gui.common." + key, args);
    }

    static SettingsValidationResult validateModelSelection(boolean enabled, String selectedModelName, AsrModelInfo selected, boolean installed) {
        if (!enabled) {
            return SettingsValidationResult.successful();
        }
        if (selectedModelName == null || selectedModelName.isBlank()) {
            return SettingsValidationResult.failure(asr("validation.model_required"));
        }
        if (selected == null) {
            return SettingsValidationResult.failure(asr("validation.invalid_model"));
        }
        if (!installed) {
            return SettingsValidationResult.failure(asr("validation.model_not_installed"));
        }
        return SettingsValidationResult.successful();
    }

    private final TianshuCoreManager coreManager;
    private final AsrSettingsAccess config;
    private final IAudioBridge audioBridge;
    private final ClientScheduler scheduler;
    private final ClientUiHost uiHost;
    private final PresenceTextProvider textProvider;

    public AsrSettingsRegistrySource(TianshuCoreManager coreManager, AsrSettingsAccess config, IAudioBridge audioBridge,
                                     ClientScheduler scheduler, ClientUiHost uiHost, PresenceTextProvider textProvider) {
        this.coreManager = coreManager;
        this.config = config;
        this.audioBridge = audioBridge;
        this.scheduler = scheduler;
        this.uiHost = uiHost;
        this.textProvider = textProvider == null ? PresenceTextProvider.NOOP : textProvider;
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null || coreManager == null || config == null || audioBridge == null) {
            return;
        }
        AsrSettingsDraft draft = new AsrSettingsDraft(config, audioBridge, coreManager, context, scheduler, uiHost, textProvider);
        context.settingsSessions().registerOrReplace(draft);
        registry.registerCategory(ModuleSettingsCategory.builder(MODULE_ID)
                .title(TITLE)
                .description(DESCRIPTION)
                .order(20)
                .panel((panel, panelContext) -> buildPanel(panel, panelContext, draft))
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context, AsrSettingsDraft draft) {
        panel.columns("asr.layout", 0.42D, 0.58D, columns -> columns
                .column(0, left -> buildSettingsColumn(left, context, draft))
                .column(1, right -> buildLocalModelResourceColumn(right, context, draft)));
    }

    private void buildSettingsColumn(ModuleSettingsPanel panel, ModuleSettingsContext context, AsrSettingsDraft draft) {
        panel.enable("asr.enabled", asr("enabled"), draft.enabled)
                .toggles("asr.diagnostics", common("section.diagnostics"), group -> group
                        .toggle("asr.diagnostics.enabled", common("option.diagnostics_enabled"), draft.diagnosticsEnabled))
                .options("as.main", asr("section.main"), draft::buildMainOptions)
                .toggles("asr.processing", asr("section.processing"), draft.enabled::get, group -> group
                        .toggle("asr.high_pass", asr("option.high_pass"), draft.highPassFilterEnabled, draft.enabled::get)
                        .toggle("asr.vad", asr("option.vad"), draft.vadEnabled, draft.enabled::get))
                .actions("asr.preview", asr("section.preview"), actions -> actions
                        .button("asr.preview.start", asr("action.preview_start"), () -> draft.startPreview(context), () -> draft.enabled.get() && draft.canPreview())
                        .button("asr.preview.stop", asr("action.preview_stop"), draft::stopPreview, draft::previewRunning))
                .status("asr.status", asr("section.status"), status -> status
                        .row("asr.status.model", asr("row.model"), draft::selectedModelStatus)
                        .row("asr.status.download", asr("row.download_status"), draft::downloadStatus)
                        .row("asr.status.preview.state", asr("row.preview_state"), draft::previewStateStatus)
                        .row("asr.status.preview.result", asr("row.preview_result"), draft::previewResultStatus));
    }

    private void buildLocalModelResourceColumn(ModuleSettingsPanel panel, ModuleSettingsContext context, AsrSettingsDraft draft) {
        panel.options("asr.download.advanced", asr("section.download_advanced"), draft::buildDownloadAdvancedOptions)
                .<AsrModelInfo>catalog("asr.download.catalog", asr("section.download_models"), draft::buildDownloadFilters, list -> list
                        .items(draft::filteredModels)
                        .card(draft::modelCard)
                        .itemActions((model, actions) -> draft.buildDownloadItemActions(context, model, actions))
                        .emptyText(asr("download.empty")));
    }

    private static final class AsrSettingsDraft implements com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession {
        private static final String ALL = "__all__";
        private static final String DEFAULT_MIC = "__default__";

        private final AsrSettingsAccess config;
        private final IAudioBridge audioBridge;
        private final TianshuCoreManager coreManager;
        private final ModuleSettingsContext context;
        private final ClientScheduler scheduler;
        private final ClientUiHost uiHost;
        private final PresenceTextProvider textProvider;
        private final MutableSettingsValue<Boolean> enabled;
        private final MutableSettingsValue<Boolean> diagnosticsEnabled;
        private final MutableSettingsValue<String> selectedMic;
        private final MutableSettingsValue<TriggerMode> triggerMode;
        private final MutableSettingsValue<String> selectedModelName;
        private final MutableSettingsValue<Boolean> highPassFilterEnabled;
        private final MutableSettingsValue<Boolean> vadEnabled;
        private final MutableSettingsValue<String> githubProxyUrl;
        private final MutableSettingsValue<String> languageFilter;
        private final MutableSettingsValue<SortDirection> performanceDirection;
        private final MutableSettingsValue<SortDirection> qualityDirection;
        private final MutableSettingsValue<SortDirection> recommendationDirection;
        private final List<AsrModelInfo> catalog;
        private final AtomicBoolean previewRunning = new AtomicBoolean(false);
        private final AtomicBoolean downloadRefreshQueued = new AtomicBoolean(false);
        private volatile UiText previewStateText = asr("status.idle");
        private volatile UiText previewResultText = common("dash");

        private AsrSettingsDraft(AsrSettingsAccess config, IAudioBridge audioBridge, TianshuCoreManager coreManager, ModuleSettingsContext context,
                                 ClientScheduler scheduler, ClientUiHost uiHost, PresenceTextProvider textProvider) {
            this.config = config;
            this.audioBridge = audioBridge;
            this.coreManager = coreManager;
            this.context = context;
            this.scheduler = scheduler;
            this.uiHost = uiHost;
            this.textProvider = textProvider;
            this.catalog = AsrModelManager.getAllModels();
            this.enabled = new MutableSettingsValue<>(config::isAsrEnabled, config::setAsrEnabled);
            this.diagnosticsEnabled = new MutableSettingsValue<>(config::isAsrDiagnosticsEnabled, config::setAsrDiagnosticsEnabled);
            this.selectedMic = new MutableSettingsValue<>(this::currentMicName, ignored -> {}, Objects::nonNull);
            this.triggerMode = new MutableSettingsValue<>(config::getTriggerMode, config::setTriggerMode, Objects::nonNull);
            this.selectedModelName = new MutableSettingsValue<>(this::currentModelName, ignored -> {}, Objects::nonNull);
            this.highPassFilterEnabled = new MutableSettingsValue<>(config::isAsrHighPassFilterEnabled, config::setAsrHighPassFilterEnabled);
            this.vadEnabled = new MutableSettingsValue<>(config::isAsrVadEnabled, config::setAsrVadEnabled);
            this.githubProxyUrl = new MutableSettingsValue<>(config::getAsrGithubProxyUrl, config::setAsrGithubProxyUrl, Objects::nonNull);
            this.languageFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.performanceDirection = new MutableSettingsValue<>(() -> SortDirection.DESC, ignored -> {}, Objects::nonNull);
            this.qualityDirection = new MutableSettingsValue<>(() -> SortDirection.DESC, ignored -> {}, Objects::nonNull);
            this.recommendationDirection = new MutableSettingsValue<>(() -> SortDirection.DESC, ignored -> {}, Objects::nonNull);
        }

        private void buildMainOptions(com.rheinmetal.tianshu.client.api.settings.OptionTemplate options) {
            options.select("asr.model", asr("option.model"), modelNames(), selectedModelName, this::modelOptionLabel, enabled::get)
                    .select("asr.mic", asr("option.mic"), micNames(), selectedMic, this::micLabel, enabled::get)
                    .select("asr.trigger", asr("option.trigger"), List.of(TriggerMode.values()), triggerMode, this::triggerLabel, enabled::get);
        }

        private void buildDownloadAdvancedOptions(com.rheinmetal.tianshu.client.api.settings.OptionTemplate options) {
            options.text("asr.download.githubProxy", asr("option.github_proxy"), githubProxyUrl);
        }

        private void buildDownloadFilters(com.rheinmetal.tianshu.client.api.settings.OptionTemplate options) {
            options.select("asr.download.lang", asr("option.language"), filterValues(this::languageTags), languageFilter, this::filterOptionLabel)
                    .select("asr.download.performance", asr("option.performance"), List.of(SortDirection.values()), performanceDirection, SortDirection::label)
                    .select("asr.download.quality", asr("option.quality"), List.of(SortDirection.values()), qualityDirection, SortDirection::label)
                    .select("asr.download.recommended", asr("option.recommended"), List.of(SortDirection.values()), recommendationDirection, SortDirection::label);
        }

        private void buildDownloadItemActions(ModuleSettingsContext context, AsrModelInfo info, com.rheinmetal.tianshu.client.api.settings.ItemActionTemplate<AsrModelInfo> actions) {
            actions.button("asr.download.item.start", asr("action.download"), SettingsButtonStyle.PRIMARY, model -> startDownload(context, model), model -> cardState(model).canStartDownload())
                    .button("asr.download.item.pause", asr("action.pause"), this::pauseDownload, model -> cardState(model).canPauseDownload())
                    .button("asr.download.item.resume", asr("action.resume"), this::resumeDownload, model -> cardState(model).canResumeDownload())
                    .button("asr.download.item.cancel", asr("action.cancel"), SettingsButtonStyle.DANGER, this::cancelDownload, model -> cardState(model).canCancelDownload())
                    .button("asr.download.item.delete", asr("action.delete"), SettingsButtonStyle.DANGER, model -> deleteModel(context, model), model -> cardState(model).canDeleteModel());
        }

        @Override
        public String moduleId() {
            return MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty()
                    || diagnosticsEnabled.dirty()
                    || selectedMic.dirty()
                    || triggerMode.dirty()
                    || selectedModelName.dirty()
                    || highPassFilterEnabled.dirty()
                    || vadEnabled.dirty()
                    || githubProxyUrl.dirty();
        }

        @Override
        public SettingsValidationResult validate() {
            AsrModelInfo selected = resolveModel(selectedModelName.get());
            return validateModelSelection(
                    enabled.get(),
                    selectedModelName.get(),
                    selectedModelName.valid() ? selected : null,
                    selected != null && isDownloaded(selected)
            );
        }

        @Override
        public SettingsSaveResult save() {
            AsrSettingsSnapshot before = AsrSettingsSnapshot.from(config);
            enabled.save();
            diagnosticsEnabled.save();
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
            diagnosticsEnabled.reset();
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

        private UiText micLabel(String name) {
            if (name == null || name.isBlank() || DEFAULT_MIC.equals(name)) {
                return asr("option.default_mic");
            }
            return UiText.literal(name);
        }

        private UiText triggerLabel(TriggerMode mode) {
            if (mode == TriggerMode.PUSH_TO_TALK) {
                return asr("trigger.push_to_talk");
            }
            if (mode == TriggerMode.ALWAYS) {
                return asr("trigger.always");
            }
            return UiText.literal(String.valueOf(mode));
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

        private UiText modelOptionLabel(String name) {
            if (name == null || name.isBlank()) {
                return common("not_selected");
            }
            AsrModelInfo info = resolveModel(name);
            return info == null ? UiText.literal(name == null ? "" : name) : UiText.literal(info.getDisplayName());
        }

        private UiText selectedModelStatus() {
            AsrModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) {
                return common("not_selected");
            }
            return asr("model.selected", info.getDisplayName(), common(isDownloaded(info) ? "downloaded" : "not_downloaded"));
        }

        private UiText downloadStatus() {
            AsrModelService.DownloadStatus status = asrModelService().downloadStatus();
            if (status != null && status.downloading()) {
                UiText label = status.cancelling()
                        ? asr("status.cancelling")
                        : status.paused()
                        ? asr("status.paused")
                        : progressLabel(status.progress());
                return asr("download.progress", label, status.progress().percent());
            }
            return asr("status.idle");
        }

        private UiText previewStateStatus() {
            return previewStateText;
        }

        private UiText previewResultStatus() {
            return previewResultText;
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
                        previewResultText = text == null || text.isBlank() ? asr("status.no_recognition_result") : UiText.literal(text);
                    });
                }

                @Override
                public void onError(String message) {
                    runOnClient(() -> {
                        previewStateText = asr("status.preview_failed");
                        previewResultText = message == null || message.isBlank()
                                ? asr("status.preview_failed")
                                : message.startsWith("tianshu.")
                                ? UiText.key(message)
                                : UiText.literal(message);
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
            if (info == null || downloadInProgress()) {
                return;
            }
            String modelKey = info.localKey();
            asrModelService().downloadModel(modelKey, githubProxyUrl.get(), new AsrModelService.DownloadProgressCallback() {
                @Override
                public void onProgress(ModelDownloadProgress progress) {
                    queueDownloadRefresh();
                }

                @Override
                public void onComplete() {
                    runOnClient(() -> {
                        context.showStatus(asr("message.download_complete"), 3000);
                        queueDownloadRefresh();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnClient(() -> {
                        UiText error = localizedKey(message, "status.download_failed");
                        context.showStatus(error, 4000);
                        queueDownloadRefresh();
                    });
                }

                @Override
                public void onCancelled() {
                    runOnClient(() -> {
                        context.showStatus(asr("status.idle"), 1500);
                        queueDownloadRefresh();
                    });
                }
            });
        }

        private void pauseDownload(AsrModelInfo info) {
            if (info == null) {
                return;
            }
            String modelKey = info.localKey();
            asrModelService().pauseDownload(modelKey);
            queueDownloadRefresh();
        }

        private void resumeDownload(AsrModelInfo info) {
            if (info == null) {
                return;
            }
            String modelKey = info.localKey();
            asrModelService().resumeDownload(modelKey);
            queueDownloadRefresh();
        }

        private void cancelDownload(AsrModelInfo info) {
            if (info == null) {
                return;
            }
            String modelKey = info.localKey();
            asrModelService().cancelDownload(modelKey);
            queueDownloadRefresh();
        }

        private boolean downloadInProgress() {
            AsrModelService.DownloadStatus status = asrModelService().downloadStatus();
            return status != null && status.downloading();
        }

        private UiText progressLabel(ModelDownloadProgress progress) {
            ModelDownloadStage stage = progress == null ? ModelDownloadStage.DOWNLOADING : progress.stage();
            return switch (stage) {
                case PAUSED -> asr("status.paused");
                case CANCELLING -> asr("status.cancelling");
                default -> asr("status.downloading");
            };
        }

        private UiText localizedKey(String key, String fallbackSuffix) {
            if (key != null && key.startsWith("tianshu.")) {
                return UiText.key(key);
            }
            return asr(fallbackSuffix);
        }

        private void deleteModel(ModuleSettingsContext context, AsrModelInfo info) {
            if (info == null || downloadInProgress() || asrModelService().isDeleting()) {
                return;
            }
            String deletedKey = info.localKey();
            UiText displayName = UiText.literal(info.getDisplayName());
            asrModelService().deleteModelAsync(info, deleted -> runOnClient(() -> {
                if (deleted) {
                    if (deletedKey.equalsIgnoreCase(selectedModelName.get())) {
                        selectedModelName.set("");
                    }
                    context.showStatus(asr("message.deleted", displayName), 3000);
                    coreManager.refreshRuntime(RuntimeRefreshReason.RESOURCE_CHANGED);
                } else {
                    context.showStatus(asr("message.delete_failed"), 3000);
                }
                queueDownloadRefresh();
            }));
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
            return scoreComparator(AsrModelInfo::getRecommendationScore, recommendationDirection.get())
                    .thenComparing(scoreComparator(AsrModelInfo::getRecognitionQualityScore, qualityDirection.get()))
                    .thenComparing(scoreComparator(AsrModelInfo::getPerformanceScore, performanceDirection.get()))
                    .thenComparing(AsrModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
        }

        private Comparator<AsrModelInfo> scoreComparator(java.util.function.ToIntFunction<AsrModelInfo> mapper, SortDirection direction) {
            Comparator<AsrModelInfo> comparator = Comparator.comparingInt(mapper);
            return direction == SortDirection.ASC ? comparator : comparator.reversed();
        }

        private boolean matchesFilters(AsrModelInfo info) {
            return matches(languageFilter.get(), languageTags(info));
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

        private SettingsListCard modelCard(AsrModelInfo info) {
            if (info == null) {
                return SettingsListCard.text(UiText.literal(""));
            }
            AsrModelCardState state = cardState(info);
            UiText title = UiText.literal(modelTitle(info));
            UiText status = state.statusLabel();
            List<UiText> details = List.of(
                    asr("download.card.lang", languageLabelReadable(info)),
                    asr("download.card.tags", tagLabel(info))
            );
            List<UiText> badges = List.of(
                    scoreBadge(info.getRecognitionQualityScore(), asr("badge.quality")),
                    scoreBadge(info.getPerformanceScore(), asr("badge.performance")),
                    scoreBadge(info.getRecommendationScore(), asr("badge.recommendation"))
            );
            return new SettingsListCard(title, status, details, badges);
        }

        private String modelTitle(AsrModelInfo info) {
            if (info == null) {
                return "";
            }
            String title = info.getDisplayName();
            title = title.replaceAll("(?i)\\s+int8\\b", "");
            title = title.replaceAll("\\s{2,}", " ").trim();
            return title.isBlank() ? info.getDisplayName() : title;
        }

        private UiText languageLabelReadable(AsrModelInfo info) {
            List<String> tags = languageTags(info);
            return tags.isEmpty() ? UiText.literal("-") : readableLanguageNames(tags);
        }

        private UiText readableLanguageNames(List<String> tags) {
            List<UiText> names = tags.stream().map(this::asrLangReadableName).toList();
            return names.isEmpty() ? UiText.literal("-") : UiText.join(", ", names);
        }

        private UiText asrLangReadableName(String code) {
            if (code == null || code.isBlank()) {
                return UiText.literal("-");
            }
            String normalized = code.toLowerCase(Locale.ROOT);
            String key = "tianshu.gui.asr.lang." + normalized;
            return textProvider.exists(key) ? UiText.key(key) : UiText.literal(code);
        }

        private UiText scoreBadge(int score, UiText label) {
            return UiText.key("tianshu.gui.settings.badge.score", label, score);
        }

        private boolean isDownloaded(AsrModelInfo info) {
            return AsrModelManager.isModelDownloaded(info, config.getAsrBasePath().resolve("model"));
        }

        private AsrModelCardState cardState(AsrModelInfo info) {
            AsrModelService.DownloadStatus status = asrModelService().downloadStatus();
            boolean downloading = status != null && status.downloading();
            boolean activeDownload = downloading && info != null && sameModel(info, resolveModel(status.activeModelKey()));
            boolean paused = activeDownload && status.paused();
            boolean cancelling = activeDownload && status.cancelling();
            boolean installed = info != null && isDownloaded(info);
            boolean operationActive = downloading || asrModelService().isDeleting();
            return new AsrModelCardState(info, installed, operationActive, activeDownload, paused, cancelling);
        }

        private List<String> filterValues(java.util.function.Function<AsrModelInfo, List<String>> mapper) {
            Set<String> values = new LinkedHashSet<>();
            values.add(ALL);
            for (AsrModelInfo info : catalog) {
                values.addAll(mapper.apply(info));
            }
            return List.copyOf(values);
        }

        private List<String> languageTags(AsrModelInfo info) {
            return info == null ? List.of() : info.getLang().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .toList();
        }

        private UiText filterOptionLabel(String value) {
            return ALL.equals(value) ? common("all") : asrLangReadableName(value);
        }

        private UiText tagLabel(AsrModelInfo info) {
            List<String> tags = modelTags(info);
            if (tags.isEmpty()) {
                return UiText.literal("-");
            }
            return UiText.join(", ", tags.stream().map(this::readableTagName).toList());
        }

        private List<String> modelTags(AsrModelInfo info) {
            if (info == null) {
                return List.of();
            }
            LinkedHashSet<String> tags = new LinkedHashSet<>();
            for (String tag : info.getTags()) {
                String normalized = normalizeTag(tag);
                if (!normalized.isBlank()) {
                    tags.add(normalized);
                }
            }
            if (info.isStreamingModel() || containsToken(info.localKey(), "streaming") || containsToken(info.remoteRepoId(), "streaming")) {
                tags.add("streaming");
            }
            if (containsToken(info.getDisplayName(), "int8") || containsToken(info.localKey(), "int8") || containsToken(info.remoteRepoId(), "int8") || info.getAllRequiredFiles().stream().anyMatch(file -> containsToken(file, "int8"))) {
                tags.add("int8");
            }
            return List.copyOf(tags);
        }

        private String normalizeTag(String tag) {
            return tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        }

        private boolean containsToken(String value, String token) {
            return value != null && token != null && value.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
        }

        private UiText readableTagName(String tag) {
            String normalized = normalizeTag(tag);
            if (normalized.isBlank()) {
                return UiText.literal("-");
            }
            String key = "tianshu.gui.asr.tag." + normalized;
            if (textProvider.exists(key)) {
                return UiText.key(key);
            }
            return UiText.literal(prettyTag(normalized));
        }

        private String prettyTag(String tag) {
            String[] parts = tag.split("_+");
            StringBuilder builder = new StringBuilder();
            for (String part : parts) {
                if (part.isBlank()) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append(' ');
                }
                builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return builder.isEmpty() ? tag : builder.toString();
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
                    coreManager.refreshRuntime(reason);
                }
            }, audioBridge);
        }

        private void runOnClient(Runnable runnable) {
            scheduler.execute(runnable);
        }

        private void refreshSettingsScreen() {
            uiHost.requestSettingsRefresh();
        }

        private void queueDownloadRefresh() {
            if (!downloadRefreshQueued.compareAndSet(false, true)) {
                return;
            }
            runOnClient(() -> {
                downloadRefreshQueued.set(false);
                refreshSettingsScreen();
            });
        }
    }

    private enum SortDirection {
        DESC("sort.desc"),
        ASC("sort.asc");

        private final String key;

        SortDirection(String key) {
            this.key = key;
        }

        private UiText label() {
            return asr(key);
        }
    }

    private record AsrModelCardState(AsrModelInfo info, boolean installed, boolean anyOperationActive, boolean activeDownload, boolean paused, boolean cancelling) {
        private boolean canStartDownload() {
            return info != null && !installed && !anyOperationActive;
        }

        private boolean canPauseDownload() {
            return activeDownload && !paused && !cancelling;
        }

        private boolean canResumeDownload() {
            return activeDownload && paused && !cancelling;
        }

        private boolean canCancelDownload() {
            return activeDownload && !cancelling;
        }

        private boolean canDeleteModel() {
            return info != null && installed && !anyOperationActive;
        }

        private UiText statusLabel() {
            if (activeDownload) {
                if (cancelling) {
                    return asr("status.cancelling");
                }
                return paused ? asr("status.paused") : asr("status.downloading");
            }
            return common(installed ? "downloaded" : "not_downloaded");
        }
    }

}
