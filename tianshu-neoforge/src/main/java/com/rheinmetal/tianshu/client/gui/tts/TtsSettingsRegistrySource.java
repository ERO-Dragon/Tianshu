package com.rheinmetal.tianshu.client.gui.tts;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;
import com.rheinmetal.tianshu.function.tts.TtsModelService;
import com.rheinmetal.tianshu.function.tts.TtsModuleService;
import com.rheinmetal.tianshu.function.tts.TtsVoiceLibraryService;
import com.rheinmetal.tianshu.function.tts.runtime.TtsOperationResult;
import com.rheinmetal.tianshu.function.tts.runtime.TtsVoiceProfile;
import com.rheinmetal.tianshu.function.tts.settings.TtsSettingsApplier;
import com.rheinmetal.tianshu.function.tts.settings.TtsSettingsRuntimeActions;
import com.rheinmetal.tianshu.function.tts.settings.TtsSettingsSnapshot;
import com.rheinmetal.tianshu.model.ModelSettings;
import com.rheinmetal.tianshu.model.TtsModelInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TtsSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static final String MODULE_ID = "module.tts";
    private static final Component TITLE = tts("title");
    private static final Component DESCRIPTION = tts("description");

    private static Component tts(String key, Object... args) {
        return Component.translatable("tianshu.gui.tts." + key, args);
    }

    private static Component common(String key, Object... args) {
        return Component.translatable("tianshu.gui.common." + key, args);
    }

    private final TianshuCoreManager coreManager;
    private final ClientConfig config;

    public TtsSettingsRegistrySource(TianshuCoreManager coreManager, ClientConfig config) {
        this.coreManager = coreManager;
        this.config = config;
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null || coreManager == null || config == null) {
            return;
        }
        registry.registerCategory(ModuleSettingsCategory.builder(MODULE_ID)
                .title(TITLE)
                .description(DESCRIPTION)
                .order(30)
                .panel(this::buildPanel)
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context) {
        TtsSettingsDraft draft = new TtsSettingsDraft(config, coreManager, context);
        context.settingsSessions().registerOrReplace(draft);

        panel.enable("tts.enabled", tts("enabled"), draft.enabled)
                .options("tts.main", tts("section.main"), draft::buildMainOptions)
                .actions("tts.preview", tts("section.preview"), actions -> actions
                        .button("tts.preview.start", tts("action.preview_start"), () -> draft.startPreview(context), () -> draft.enabled.get() && draft.canPreview())
                        .button("tts.preview.stop", tts("action.preview_stop"), draft::stopPreview, draft::previewRunning))
                .status("tts.model.status", tts("section.model_status"), status -> status
                        .row("tts.model.name", tts("row.model"), draft::selectedModelNameStatus)
                        .row("tts.model.description", tts("row.description"), draft::selectedModelDescriptionStatus)
                        .row("tts.model.meta", tts("row.meta"), draft::selectedModelMetaStatus)
                        .row("tts.model.install", tts("row.install"), draft::selectedModelInstallStatus)
                        .row("tts.preview.status", tts("row.preview_status"), draft::previewStatus)
                        .row("tts.download.status", tts("row.download_status"), draft::downloadStatus))
                .options("tts.voice", tts("section.voice"), draft::buildVoiceOptions)
                .actions("tts.voice.actions", tts("section.voice_actions"), actions -> actions
                        .button("tts.voice.import", tts("action.voice_import"), () -> draft.importVoiceSample(context), () -> draft.enabled.get() && draft.supportsVoiceClone())
                        .button("tts.voice.folder", tts("action.voice_folder"), draft::openVoiceLibraryFolder, draft.enabled::get))
                .status("tts.voice.status", tts("section.voice_status"), () -> true, draft::supportsVoiceClone, status -> status
                        .row("tts.voice.selected", tts("row.voice_selected"), draft::selectedVoiceStatus)
                        .row("tts.voice.path", tts("row.voice_library"), draft::voiceLibraryPathStatus))
                .separator("tts.download.separator")
                .enable("tts.download.expand", tts("download.expand"), draft.downloadExpanded::get, draft.downloadExpanded::set)
                .options("tts.download.advanced", tts("section.download_advanced"), () -> true, draft.downloadExpanded::get, draft::buildDownloadAdvancedOptions)
                .options("tts.download.filters", tts("section.download_filters"), () -> true, draft.downloadExpanded::get, draft::buildDownloadFilters)
                .<TtsModelInfo>list("tts.download.models", tts("section.download_models"), () -> true, draft.downloadExpanded::get, list -> list
                        .items(draft::filteredModels)
                        .label(draft::downloadModelLabel)
                        .selected(draft::selectedDownloadModel)
                        .onSelect(draft::selectDownloadModel)
                        .itemActions((model, actions) -> draft.buildDownloadItemActions(context, model, actions))
                        .emptyText(tts("download.empty")))
                .status("tts.download.model.status", tts("section.selected_download_model"), () -> true, draft.downloadExpanded::get, status -> status
                        .row("tts.download.model.name", tts("row.name"), draft::selectedDownloadName)
                        .row("tts.download.model.meta", tts("row.tags"), draft::selectedDownloadMeta)
                        .row("tts.download.model.files", tts("row.files"), draft::selectedDownloadFiles));
    }

    private static final class TtsSettingsDraft implements com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession {
        private static final String NO_VOICE_SAMPLE = "__none__";
        private static final String ALL = "__all__";
        private static final String SUPPORTS = "supports";
        private static final String UNSUPPORTED = "unsupported";

        private final ClientConfig config;
        private final TianshuCoreManager coreManager;
        private final ModuleSettingsContext context;
        private final MutableSettingsValue<Boolean> enabled;
        private final MutableSettingsValue<String> selectedModelName;
        private final MutableSettingsValue<String> previewText;
        private final MutableSettingsValue<Double> speed;
        private final MutableSettingsValue<String> selectedVoiceSample;
        private final MutableSettingsValue<Boolean> downloadExpanded;
        private final MutableSettingsValue<String> githubProxyUrl;
        private final MutableSettingsValue<String> modelTypeFilter;
        private final MutableSettingsValue<String> performanceFilter;
        private final MutableSettingsValue<String> qualityFilter;
        private final MutableSettingsValue<String> recommendedFilter;
        private final MutableSettingsValue<String> voiceCloneFilter;
        private final MutableSettingsValue<SortMode> sortMode;
        private final List<TtsModelInfo> catalog;
        private final AtomicBoolean previewRunning = new AtomicBoolean(false);
        private final AtomicBoolean downloading = new AtomicBoolean(false);
        private volatile TtsModelInfo selectedDownloadModel;
        private volatile Component downloadLabel = tts("status.idle");
        private volatile int downloadProgress = 0;
        private volatile Component previewStatus = tts("status.idle");

        private TtsSettingsDraft(ClientConfig config, TianshuCoreManager coreManager, ModuleSettingsContext context) {
            this.config = config;
            this.coreManager = coreManager;
            this.context = context;
            this.catalog = ttsModelService(coreManager).catalog().stream()
                    .filter(Objects::nonNull)
                    .filter(info -> info.name != null && !info.name.isBlank())
                    .sorted(Comparator.comparing(info -> info.name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            this.enabled = new MutableSettingsValue<>(config::isTtsEnabled, config::setTtsEnabled);
            this.selectedModelName = new MutableSettingsValue<>(this::currentModelName, ignored -> {}, value -> value != null && !value.isBlank());
            ModelSettings.TtsSettings settings = modelSettings(resolveModel(this.selectedModelName.get()));
            this.previewText = new MutableSettingsValue<>(config::getTtsPreviewText, config::setTtsPreviewText, value -> value != null && !value.isBlank());
            this.speed = new MutableSettingsValue<>(() -> settings.speed, ignored -> {}, value -> value != null && value >= 0.1D && value <= 5.0D);
            this.selectedVoiceSample = new MutableSettingsValue<>(() -> normalizeVoiceSample(settings.selectedVoiceSample), ignored -> {});
            this.downloadExpanded = new MutableSettingsValue<>(() -> false, ignored -> {});
            this.githubProxyUrl = new MutableSettingsValue<>(config::getTtsGithubProxyUrl, config::setTtsGithubProxyUrl, Objects::nonNull);
            this.modelTypeFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.performanceFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.qualityFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.recommendedFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.voiceCloneFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.sortMode = new MutableSettingsValue<>(() -> SortMode.RECOMMENDED, ignored -> {}, Objects::nonNull);
            this.selectedDownloadModel = resolveModel(this.selectedModelName.get());
        }

        private void buildMainOptions(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("tts.model", tts("option.model"), modelNames(), selectedModelName, Component::literal, enabled::get)
                    .text("tts.preview.text", tts("option.preview_text"), previewText, enabled::get)
                    .slider("tts.speed", tts("option.speed"), speed, 0.5D, 2.0D, enabled::get);
        }

        private void buildVoiceOptions(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("tts.voice.sample", tts("option.voice_sample"), voiceSampleOptions(), selectedVoiceSample, this::voiceSampleOptionLabel, () -> enabled.get() && supportsVoiceClone());
        }

        private void buildDownloadAdvancedOptions(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.text("tts.download.githubProxy", tts("option.github_proxy"), githubProxyUrl);
        }

        private void buildDownloadFilters(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("tts.download.modelType", tts("option.model_type"), filterValues(info -> List.of(modelTypeValue(info))), modelTypeFilter, this::modelTypeOptionLabel)
                    .select("tts.download.performance", tts("option.performance"), tierValues(this::performanceTier), performanceFilter, this::tierOptionLabel)
                    .select("tts.download.quality", tts("option.quality"), tierValues(this::qualityTier), qualityFilter, this::tierOptionLabel)
                    .select("tts.download.recommended", tts("option.recommended"), tierValues(this::recommendedTier), recommendedFilter, this::tierOptionLabel)
                    .select("tts.download.voiceClone", tts("option.voice_clone"), List.of(ALL, SUPPORTS, UNSUPPORTED), voiceCloneFilter, this::voiceCloneOptionLabel)
                    .select("tts.download.sort", tts("option.sort"), List.of(SortMode.values()), sortMode, SortMode::label);
        }

        @Override
        public String moduleId() {
            return MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty()
                    || selectedModelName.dirty()
                    || previewText.dirty()
                    || speed.dirty()
                    || selectedVoiceSample.dirty()
                    || githubProxyUrl.dirty();
        }

        @Override
        public SettingsValidationResult validate() {
            if (!selectedModelName.valid() || resolveModel(selectedModelName.get()) == null) {
                return SettingsValidationResult.failure(tts("validation.invalid_model"));
            }
            if (!previewText.valid()) {
                return SettingsValidationResult.failure(tts("validation.preview_empty"));
            }
            if (!speed.valid()) {
                return SettingsValidationResult.failure(tts("validation.invalid_speed"));
            }
            if (supportsVoiceClone() && voiceSampleOptions().size() > 1 && NO_VOICE_SAMPLE.equals(selectedVoiceSample.get())) {
                return SettingsValidationResult.failure(tts("validation.voice_required"));
            }
            return SettingsValidationResult.successful();
        }

        @Override
        public SettingsSaveResult save() {
            TtsSettingsSnapshot before = TtsSettingsSnapshot.from(config);
            enabled.save();
            previewText.save();
            githubProxyUrl.save();
            config.setCustomTtsName(selectedModelName.get());
            selectedModelName.save();
            saveModelSettings();
            config.save();
            TtsSettingsSnapshot after = TtsSettingsSnapshot.from(config);
            settingsApplier().apply(before, after);
            return SettingsSaveResult.success(tts("message.saved"), true, true);
        }

        @Override
        public void reset() {
            enabled.reset();
            selectedModelName.reset();
            previewText.reset();
            speed.reset();
            selectedVoiceSample.reset();
            githubProxyUrl.reset();
        }

        private List<String> modelNames() {
            return catalog.stream()
                    .map(info -> info.name)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
        }

        private String currentModelName() {
            String configured = config.getCustomTtsName();
            if (configured != null && !configured.isBlank()) {
                return configured;
            }
            TtsModelInfo current = ttsModelService().resolveCurrentModelInfo();
            if (current != null && current.name != null && !current.name.isBlank()) {
                return current.name;
            }
            return modelNames().isEmpty() ? "" : modelNames().get(0);
        }

        private TtsModelInfo resolveModel(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            for (TtsModelInfo info : catalog) {
                if (info.name != null && info.name.equalsIgnoreCase(name.trim())) {
                    return info;
                }
            }
            return null;
        }

        private ModelSettings.TtsSettings modelSettings(TtsModelInfo info) {
            return ttsModelService().loadSettings(info);
        }

        private void saveModelSettings() {
            TtsModelInfo info = resolveModel(selectedModelName.get());
            ModelSettings.TtsSettings settings = modelSettings(info);
            settings.speed = speed.get();
            settings.selectedVoiceSample = NO_VOICE_SAMPLE.equals(selectedVoiceSample.get()) ? "" : selectedVoiceSample.get();
            ttsModelService().saveSettings(info, settings);
            speed.save();
            selectedVoiceSample.save();
        }

        private boolean canPreview() {
            TtsModelInfo info = resolveModel(selectedModelName.get());
            return info != null && ttsModelService().hasModelContent(info) && !previewRunning.get();
        }

        private boolean previewRunning() {
            return previewRunning.get();
        }

        private void startPreview(ModuleSettingsContext context) {
            TtsModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) {
                context.showStatus(tts("validation.invalid_model"), 3000);
                return;
            }
            if (!ttsModelService().hasModelContent(info)) {
                context.showStatus(tts("message.model_not_installed"), 3000);
                return;
            }
            previewRunning.set(true);
            previewStatus = tts("status.preview_preparing");
            TtsOperationResult result = ttsModuleService().previewDraftModel(selectedModelName.get(), previewText.get(), voiceProfile(info), () -> runOnClient(() -> {
                previewRunning.set(false);
                previewStatus = tts("status.preview_complete");
            }), failure -> runOnClient(() -> {
                previewRunning.set(false);
                previewStatus = failure == null || failure.message().isBlank() ? tts("status.preview_failed") : Component.literal(failure.message());
                context.showStatus(previewStatus, 4000);
            }));
            if (!result.accepted()) {
                previewRunning.set(false);
                previewStatus = result.failure() == null ? tts("status.preview_failed") : Component.literal(result.failure().message());
                context.showStatus(previewStatus, 4000);
            }
        }

        private void stopPreview() {
            ttsModuleService().stopPreview("preview stopped");
            previewRunning.set(false);
            previewStatus = tts("status.stopped");
        }

        private TtsVoiceProfile voiceProfile(TtsModelInfo info) {
            String sample = "";
            if (info != null && info.supportsVoiceClone() && !NO_VOICE_SAMPLE.equals(selectedVoiceSample.get())) {
                Path resolved = voiceLibraryService().resolveVoiceSamplePath(selectedVoiceSample.get());
                sample = resolved == null ? "" : resolved.toString();
            }
            return new TtsVoiceProfile("", speed.get().floatValue(), 0, sample);
        }

        private boolean supportsVoiceClone() {
            TtsModelInfo info = resolveModel(selectedModelName.get());
            return info != null && info.supportsVoiceClone();
        }

        private List<String> voiceSampleOptions() {
            List<String> samples = new ArrayList<>();
            samples.add(NO_VOICE_SAMPLE);
            samples.addAll(voiceLibraryService().listVoiceSamples());
            return samples.stream().distinct().toList();
        }

        private void importVoiceSample(ModuleSettingsContext context) {
            Path selected = chooseWavFile();
            if (selected == null) {
                return;
            }
            String imported = voiceLibraryService().importVoiceSample(selected);
            if (imported == null || imported.isBlank()) {
                context.showStatus(tts("message.voice_import_failed"), 3000);
                return;
            }
            selectedVoiceSample.set(imported);
            context.showStatus(tts("message.voice_imported", imported), 3000);
        }

        private Path chooseWavFile() {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(I18n.get("tianshu.gui.tts.dialog.voice_import"));
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new FileNameExtensionFilter(I18n.get("tianshu.gui.tts.dialog.wav_audio"), "wav"));
            int result = chooser.showOpenDialog(null);
            return result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null ? chooser.getSelectedFile().toPath() : null;
        }

        private void openVoiceLibraryFolder() {
            voiceLibraryService().openVoiceLibraryFolder();
        }

        private Component selectedModelNameStatus() {
            TtsModelInfo info = resolveModel(selectedModelName.get());
            return info == null ? common("not_selected") : Component.literal(info.name);
        }

        private Component selectedModelDescriptionStatus() {
            TtsModelInfo info = resolveModel(selectedModelName.get());
            return info == null ? common("dash") : Component.literal(info.getDescription());
        }

        private Component selectedModelMetaStatus() {
            TtsModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) {
                return common("dash");
            }
            return tts("model.meta", modelTypeLabel(info), languageLabel(info), tierLabel(performanceTier(info)), tierLabel(qualityTier(info)), tierLabel(recommendedTier(info)));
        }

        private Component selectedModelInstallStatus() {
            TtsModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) {
                return common("not_selected");
            }
            return common(ttsModelService().hasModelContent(info) ? "installed" : "not_installed");
        }

        private Component previewStatus() {
            return previewStatus;
        }

        private Component downloadStatus() {
            if (downloading.get()) {
                return tts("download.progress", downloadLabel, downloadProgress);
            }
            return downloadLabel;
        }

        private void buildDownloadItemActions(ModuleSettingsContext context, TtsModelInfo info, com.rheinmetal.tianshu.client.gui.settings.api.ItemActionTemplate<TtsModelInfo> actions) {
            actions.button("tts.download.item.select", tts("action.use_as_draft"), SettingsButtonStyle.NORMAL, this::useDownloadModel, Objects::nonNull)
                    .button("tts.download.item.start", tts("action.download"), SettingsButtonStyle.PRIMARY, model -> startDownload(context, model), model -> model != null && !downloading.get() && !isDownloaded(model))
                    .button("tts.download.item.pause", tts("action.pause"), model -> pauseDownload(), model -> isActiveDownload(model) && !ttsModelService().isDownloadPaused())
                    .button("tts.download.item.resume", tts("action.resume"), model -> resumeDownload(), model -> isActiveDownload(model) && ttsModelService().isDownloadPaused())
                    .button("tts.download.item.cancel", tts("action.cancel"), SettingsButtonStyle.DANGER, model -> cancelDownload(), this::isActiveDownload)
                    .button("tts.download.item.delete", tts("action.delete"), SettingsButtonStyle.DANGER, model -> deleteModel(context, model), model -> model != null && !downloading.get() && isDownloaded(model));
        }

        private void startDownload(ModuleSettingsContext context, TtsModelInfo info) {
            if (info == null || downloading.get()) {
                return;
            }
            selectedDownloadModel = info;
            downloading.set(true);
            downloadLabel = tts("status.download_preparing");
            downloadProgress = 0;
            ttsModelService().downloadModel(info, githubProxyUrl.get(), new TtsModelService.DownloadProgressCallback() {
                @Override
                public void onProgress(String label, int percent) {
                    runOnClient(() -> {
                        downloadLabel = label == null ? tts("status.downloading") : Component.literal(label);
                        downloadProgress = percent;
                    });
                }

                @Override
                public void onComplete() {
                    runOnClient(() -> {
                        downloading.set(false);
                        downloadLabel = tts("status.download_complete");
                        downloadProgress = 100;
                        context.showStatus(tts("message.download_complete"), 3000);
                        coreManager.refreshRuntimeAsync(RuntimeRefreshReason.RESOURCE_CHANGED, null);
                    });
                }

                @Override
                public void onError(String message) {
                    runOnClient(() -> {
                        downloading.set(false);
                        downloadLabel = message == null ? tts("status.download_failed") : Component.literal(message);
                        context.showStatus(downloadLabel, 4000);
                    });
                }
            });
        }

        private void pauseDownload() {
            ttsModelService().pauseDownload();
            downloadLabel = tts("status.paused");
        }

        private void resumeDownload() {
            ttsModelService().resumeDownload();
            downloadLabel = tts("status.downloading");
        }

        private void cancelDownload() {
            ttsModelService().cancelDownload();
            downloadLabel = tts("status.cancelling");
        }

        private boolean isActiveDownload(TtsModelInfo info) {
            return info != null && downloading.get() && sameModel(info, selectedDownloadModel);
        }

        private void deleteModel(ModuleSettingsContext context, TtsModelInfo info) {
            if (info == null || downloading.get()) {
                return;
            }
            ttsModelService().deleteModel(info);
            downloadLabel = tts("message.deleted", info.getDisplayName());
            context.showStatus(downloadLabel, 3000);
            coreManager.refreshRuntimeAsync(RuntimeRefreshReason.RESOURCE_CHANGED, null);
        }

        private boolean isDownloaded(TtsModelInfo info) {
            return ttsModelService().hasModelContent(info);
        }

        private boolean sameModel(TtsModelInfo left, TtsModelInfo right) {
            if (left == right) {
                return true;
            }
            if (left == null || right == null) {
                return false;
            }
            if (left.name != null && right.name != null) {
                return left.name.equalsIgnoreCase(right.name);
            }
            return Objects.equals(left.id, right.id);
        }

        private TtsModelInfo selectedDownloadModel() {
            return selectedDownloadModel;
        }

        private void selectDownloadModel(TtsModelInfo info) {
            selectedDownloadModel = info;
        }

        private void useDownloadModel(TtsModelInfo info) {
            if (info != null && info.name != null && !info.name.isBlank()) {
                selectedDownloadModel = info;
                selectedModelName.set(info.name);
            }
        }

        private Component selectedDownloadName() {
            return selectedDownloadModel == null ? common("not_selected") : Component.literal(selectedDownloadModel.getDisplayName());
        }

        private Component selectedDownloadMeta() {
            TtsModelInfo info = selectedDownloadModel;
            if (info == null) {
                return common("dash");
            }
            return tts("download.meta", modelTypeLabel(info), languageLabel(info), tierLabel(performanceTier(info)), tierLabel(qualityTier(info)), tierLabel(recommendedTier(info)), common(info.supportsVoiceClone() ? "yes" : "no"));
        }

        private Component selectedDownloadFiles() {
            TtsModelInfo info = selectedDownloadModel;
            if (info == null) {
                return common("dash");
            }
            return common(isDownloaded(info) ? "downloaded" : "not_downloaded");
        }

        private List<TtsModelInfo> filteredModels() {
            return catalog.stream()
                    .filter(this::matchesFilters)
                    .sorted(downloadModelComparator())
                    .toList();
        }

        private Comparator<TtsModelInfo> downloadModelComparator() {
            SortMode mode = sortMode.get();
            if (mode == SortMode.QUALITY) {
                return Comparator.comparingInt(this::qualityScore).reversed()
                        .thenComparingInt(this::performanceScore)
                        .thenComparing(TtsModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            }
            if (mode == SortMode.PERFORMANCE) {
                return Comparator.comparingInt(this::performanceScore)
                        .thenComparing(Comparator.comparingInt(this::qualityScore).reversed())
                        .thenComparing(TtsModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            }
            if (mode == SortMode.NAME) {
                return Comparator.comparing(TtsModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            }
            return Comparator.comparingInt(this::recommendedScore).reversed()
                    .thenComparing(Comparator.comparingInt(this::qualityScore).reversed())
                    .thenComparingInt(this::performanceScore)
                    .thenComparing(TtsModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
        }

        private boolean matchesFilters(TtsModelInfo info) {
            return info != null
                    && matches(modelTypeFilter.get(), List.of(modelTypeValue(info)))
                    && matches(performanceFilter.get(), List.of(performanceTier(info)))
                    && matches(qualityFilter.get(), List.of(qualityTier(info)))
                    && matches(recommendedFilter.get(), List.of(recommendedTier(info)))
                    && matchesVoiceClone(info);
        }

        private boolean matchesVoiceClone(TtsModelInfo info) {
            String filter = voiceCloneFilter.get();
            return filter == null || ALL.equals(filter) || (SUPPORTS.equals(filter) == info.supportsVoiceClone());
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

        private Component downloadModelLabel(TtsModelInfo info) {
            if (info == null) {
                return Component.empty();
            }
            return tts("download.card", info.getDisplayName(), languageLabel(info), modelTypeLabel(info), tierLabel(performanceTier(info)), tierLabel(qualityTier(info)), tierLabel(recommendedTier(info)), common(info.supportsVoiceClone() ? "yes" : "no"), common(isDownloaded(info) ? "downloaded" : "not_downloaded"));
        }

        private List<String> filterValues(java.util.function.Function<TtsModelInfo, List<String>> mapper) {
            Set<String> values = new LinkedHashSet<>();
            values.add(ALL);
            for (TtsModelInfo info : catalog) {
                for (String value : mapper.apply(info)) {
                    if (value != null && !value.isBlank()) {
                        values.add(value);
                    }
                }
            }
            return List.copyOf(values);
        }

        private List<String> tierValues(java.util.function.Function<TtsModelInfo, String> mapper) {
            Set<String> values = new LinkedHashSet<>();
            values.add(ALL);
            for (TtsModelInfo info : catalog) {
                String value = mapper.apply(info);
                if (value != null && !value.isBlank()) {
                    values.add(value.toUpperCase(Locale.ROOT));
                }
            }
            return List.copyOf(values);
        }

        private Component modelTypeOptionLabel(String value) {
            if (ALL.equals(value)) {
                return common("all");
            }
            return modelTypeLabel(value);
        }

        private Component modelTypeLabel(TtsModelInfo info) {
            return modelTypeLabel(modelTypeValue(info));
        }

        private Component modelTypeLabel(String value) {
            if ("moss".equalsIgnoreCase(value)) {
                return tts("model_type.moss");
            }
            if ("sherpa_onnx".equalsIgnoreCase(value)) {
                return tts("model_type.sherpa_onnx");
            }
            return common("unknown_model");
        }

        private String modelTypeValue(TtsModelInfo info) {
            if (info == null) {
                return "unknown";
            }
            return "moss".equalsIgnoreCase(info.getEngineType()) ? "moss" : "sherpa_onnx";
        }

        private Component tierOptionLabel(String value) {
            if (ALL.equals(value)) {
                return common("all");
            }
            return tierLabel(value);
        }

        private Component tierLabel(String tier) {
            if ("HIGH".equalsIgnoreCase(tier)) {
                return common("high");
            }
            if ("LOW".equalsIgnoreCase(tier)) {
                return common("low");
            }
            return common("mid");
        }

        private Component voiceCloneOptionLabel(String value) {
            if (ALL.equals(value)) {
                return common("all");
            }
            if (SUPPORTS.equals(value)) {
                return common("supported");
            }
            return common("unsupported");
        }

        private Component voiceSampleOptionLabel(String value) {
            return NO_VOICE_SAMPLE.equals(value) ? common("not_selected") : Component.literal(value);
        }

        private String languageLabel(TtsModelInfo info) {
            if (info == null || info.lang == null || info.lang.isEmpty()) {
                return I18n.get("tianshu.gui.common.unknown");
            }
            return String.join(",", info.lang.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .toList());
        }

        private String performanceTier(TtsModelInfo info) {
            if (info == null) {
                return "MID";
            }
            return switch (info.getPerformance()) {
                case TtsModelInfo.PERF_LOW -> "LOW";
                case TtsModelInfo.PERF_HIGH -> "HIGH";
                default -> "MID";
            };
        }

        private String qualityTier(TtsModelInfo info) {
            if (info == null) {
                return "MID";
            }
            if ("premium".equalsIgnoreCase(info.getTier())) {
                return "HIGH";
            }
            if (info.getRating() >= 5) {
                return "HIGH";
            }
            if (info.getRating() >= 3) {
                return "MID";
            }
            return "LOW";
        }

        private String recommendedTier(TtsModelInfo info) {
            if (info == null) {
                return "MID";
            }
            if (info.pinned || info.getRating() >= 5) {
                return "HIGH";
            }
            if (info.getRating() >= 3) {
                return "MID";
            }
            return "LOW";
        }

        private int qualityScore(TtsModelInfo info) {
            return tierScore(qualityTier(info));
        }

        private int recommendedScore(TtsModelInfo info) {
            return tierScore(recommendedTier(info));
        }

        private int performanceScore(TtsModelInfo info) {
            return tierScore(performanceTier(info));
        }

        private int tierScore(String tier) {
            if ("HIGH".equalsIgnoreCase(tier)) {
                return 3;
            }
            if ("LOW".equalsIgnoreCase(tier)) {
                return 1;
            }
            return 2;
        }

        private Component selectedVoiceStatus() {
            if (!supportsVoiceClone()) {
                return tts("status.voice_clone_not_required");
            }
            return NO_VOICE_SAMPLE.equals(selectedVoiceSample.get()) ? common("not_selected") : Component.literal(selectedVoiceSample.get());
        }

        private Component voiceLibraryPathStatus() {
            return Component.literal(config.getVoiceLibraryPath().toString());
        }

        private String normalizeVoiceSample(String value) {
            return value == null || value.isBlank() ? NO_VOICE_SAMPLE : value;
        }

        private TtsModuleService ttsModuleService() {
            return coreManager.requireService(TtsModuleService.class);
        }

        private TtsModelService ttsModelService() {
            return ttsModelService(coreManager);
        }

        private static TtsModelService ttsModelService(TianshuCoreManager coreManager) {
            return coreManager.requireService(TtsModelService.class);
        }

        private TtsVoiceLibraryService voiceLibraryService() {
            return coreManager.requireService(TtsVoiceLibraryService.class);
        }

        private TtsSettingsApplier settingsApplier() {
            return new TtsSettingsApplier(new TtsSettingsRuntimeActions() {
                @Override
                public void stopPlaybackResources() {
                    coreManager.findService(TtsModuleService.class).ifPresent(service -> service.stopAll("tts disabled"));
                }

                @Override
                public void restartRuntime(RuntimeRefreshReason reason) {
                    coreManager.restartRuntimeAsync(reason, null);
                }
            });
        }

        private void runOnClient(Runnable runnable) {
            Minecraft.getInstance().execute(runnable);
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
            return tts(key);
        }
    }
}
