package com.rheinmetal.tianshu.client.gui.tts;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsListCard;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.screen.TianshuSettingsScreen;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.core.runtime.RuntimeRefreshReason;
import com.rheinmetal.tianshu.function.tts.TtsModelService;
import com.rheinmetal.tianshu.function.tts.TtsModuleService;
import com.rheinmetal.tianshu.function.tts.TtsVoiceLibraryService;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailure;
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
    private static final long DOWNLOAD_REFRESH_INTERVAL_MILLIS = 250L;
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
        TtsSettingsDraft draft = new TtsSettingsDraft(config, coreManager, context);
        context.settingsSessions().registerOrReplace(draft);
        registry.registerCategory(ModuleSettingsCategory.builder(MODULE_ID)
                .title(TITLE)
                .description(DESCRIPTION)
                .order(30)
                .panel((panel, panelContext) -> buildPanel(panel, panelContext, draft))
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context, TtsSettingsDraft draft) {
        panel.columns("tts.layout", 0.42D, 0.58D, columns -> columns
                .column(0, left -> buildSettingsColumn(left, context, draft))
                .column(1, right -> buildDownloadColumn(right, context, draft)));
    }

    private void buildSettingsColumn(ModuleSettingsPanel panel, ModuleSettingsContext context, TtsSettingsDraft draft) {
        panel.enable("tts.enabled", tts("enabled"), draft.enabled)
                .options("tts.main", tts("section.main"), draft::buildMainOptions)
                .actions("tts.preview", tts("section.preview"), actions -> actions
                        .button("tts.preview.start", tts("action.preview_start"), () -> draft.startPreview(context), () -> draft.enabled.get() && draft.canPreview())
                        .button("tts.preview.stop", tts("action.preview_stop"), draft::stopPreview, draft::previewRunning))
                .status("tts.model.status", tts("section.model_status"), status -> status
                        .row("tts.model.name", tts("row.model"), draft::selectedModelNameStatus)
                        .row("tts.model.description", tts("row.description"), draft::selectedModelDescriptionStatus)
                        .row("tts.model.meta", tts("row.meta"), draft::selectedModelMetaStatus)
                        .row("tts.preview.status", tts("row.preview_status"), draft::previewStatus)
                        .row("tts.download.status", tts("row.download_status"), draft::downloadStatus))
                .options("tts.voice", tts("section.voice"), draft::buildVoiceOptions)
                .actions("tts.voice.actions", tts("section.voice_actions"), actions -> actions
                        .button("tts.voice.import", tts("action.voice_import"), () -> draft.importVoiceSample(context), () -> draft.enabled.get() && draft.supportsVoiceClone())
                        .button("tts.voice.folder", tts("action.voice_folder"), draft::openVoiceLibraryFolder, draft.enabled::get))
                .status("tts.voice.status", tts("section.voice_status"), () -> true, draft::supportsVoiceClone, status -> status
                        .row("tts.voice.selected", tts("row.voice_selected"), draft::selectedVoiceStatus)
                        .row("tts.voice.path", tts("row.voice_library"), draft::voiceLibraryPathStatus));
    }

    private void buildDownloadColumn(ModuleSettingsPanel panel, ModuleSettingsContext context, TtsSettingsDraft draft) {
        panel.options("tts.download.advanced", tts("section.download_advanced"), draft::buildDownloadAdvancedOptions)
                .<TtsModelInfo>catalog("tts.download.catalog", tts("section.download_models"), draft::buildDownloadFilters, list -> list
                        .items(draft::filteredModels)
                        .card(draft::downloadModelCard)
                        .itemActions((model, actions) -> draft.buildDownloadItemActions(context, model, actions))
                        .emptyText(tts("download.empty")));
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
        private final MutableSettingsValue<String> githubProxyUrl;
        private final MutableSettingsValue<String> performanceFilter;
        private final MutableSettingsValue<String> qualityFilter;
        private final MutableSettingsValue<String> recommendedFilter;
        private final MutableSettingsValue<String> voiceCloneFilter;
        private final MutableSettingsValue<SortMode> sortMode;
        private final List<TtsModelInfo> catalog;
        private final AtomicBoolean previewRunning = new AtomicBoolean(false);
        private final AtomicBoolean downloadRefreshQueued = new AtomicBoolean(false);
        private volatile long lastDownloadRefreshMillis;
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
            this.selectedModelName = new MutableSettingsValue<>(this::currentModelName, ignored -> {}, Objects::nonNull);
            ModelSettings.TtsSettings settings = modelSettings(resolveModel(this.selectedModelName.get()));
            this.previewText = new MutableSettingsValue<>(config::getTtsPreviewText, config::setTtsPreviewText, value -> value != null && !value.isBlank());
            this.speed = new MutableSettingsValue<>(() -> settings.speed, ignored -> {}, value -> value != null && value >= 0.1D && value <= 5.0D);
            this.selectedVoiceSample = new MutableSettingsValue<>(() -> normalizeVoiceSample(settings.selectedVoiceSample), ignored -> {});
            this.githubProxyUrl = new MutableSettingsValue<>(config::getTtsGithubProxyUrl, config::setTtsGithubProxyUrl, Objects::nonNull);
            this.performanceFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.qualityFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.recommendedFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.voiceCloneFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.sortMode = new MutableSettingsValue<>(() -> SortMode.RECOMMENDED, ignored -> {}, Objects::nonNull);
        }

        private void buildMainOptions(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("tts.model", tts("option.model"), modelNames(), selectedModelName, this::modelOptionLabel, enabled::get)
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
            options.select("tts.download.performance", tts("option.performance"), scoreFilterValues(this::performanceScore), performanceFilter, this::scoreFilterLabel)
                    .select("tts.download.quality", tts("option.quality"), scoreFilterValues(this::qualityScore), qualityFilter, this::scoreFilterLabel)
                    .select("tts.download.recommended", tts("option.recommended"), scoreFilterValues(this::recommendedScore), recommendedFilter, this::scoreFilterLabel)
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
            TtsModelInfo selected = resolveModel(selectedModelName.get());
            if (enabled.get() && selectedModelName.get() != null && !selectedModelName.get().isBlank() && (!selectedModelName.valid() || selected == null)) {
                return SettingsValidationResult.failure(tts("validation.invalid_model"));
            }
            if (enabled.get() && selected != null && !isDownloaded(selected)) {
                return SettingsValidationResult.failure(tts("validation.model_not_installed"));
            }
            if (enabled.get() && !previewText.valid()) {
                return SettingsValidationResult.failure(tts("validation.preview_empty"));
            }
            if (enabled.get() && !speed.valid()) {
                return SettingsValidationResult.failure(tts("validation.invalid_speed"));
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
            List<String> downloaded = catalog.stream()
                    .filter(this::isDownloaded)
                    .map(info -> info.name)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
            List<String> values = new ArrayList<>(downloaded.size() + 2);
            values.add("");
            String configured = config.getCustomTtsName();
            if (configured != null && !configured.isBlank() && resolveModel(configured) != null && downloaded.stream().noneMatch(name -> name.equalsIgnoreCase(configured))) {
                values.add(configured.trim());
            }
            values.addAll(downloaded);
            return values;
        }

        private String currentModelName() {
            String configured = config.getCustomTtsName();
            if (configured != null && !configured.isBlank()) {
                TtsModelInfo configuredModel = resolveModel(configured);
                if (configuredModel != null) {
                    return configured;
                }
            }
            return "";
        }

        private Component modelOptionLabel(String modelName) {
            return modelName == null || modelName.isBlank() ? common("not_selected") : Component.literal(modelName);
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
            if (info == null) {
                return;
            }
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
            return previewRunning.get() || "preview".equalsIgnoreCase(ttsModuleService().snapshot().activeSource());
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
            TtsOperationResult result = ttsModuleService().previewModel(selectedModelName.get(), previewText.get(), voiceProfile(info), () -> runOnClient(() -> {
                previewRunning.set(false);
                previewStatus = tts("status.preview_complete");
            }), failure -> runOnClient(() -> {
                previewRunning.set(false);
                previewStatus = failureStatus(failure);
                context.showStatus(previewStatus, 4000);
            }));
            if (!result.accepted()) {
                previewRunning.set(false);
                previewStatus = failureStatus(result.failure());
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
            } else if (info != null && info.supportsVoiceClone()) {
                Path resolved = ttsModelService().resolveVoiceSamplePath(info, "");
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
            return tts("model.meta", languageLabel(info), sizeLabel(info.size), scoreLabel(info.getPerformanceScore()), scoreLabel(info.getSynthesisQualityScore()), scoreLabel(info.getRecommendationScore()));
        }

        private Component previewStatus() {
            return previewStatus;
        }

        private Component failureStatus(TtsFailure failure) {
            if (failure == null || failure.code() == null) {
                return tts("status.preview_failed");
            }
            return tts("failure." + failure.code().name().toLowerCase(Locale.ROOT));
        }

        private Component localizedKey(String key, String fallbackSuffix) {
            if (key != null && key.startsWith("tianshu.gui.tts.")) {
                return Component.translatable(key);
            }
            return tts(fallbackSuffix);
        }

        private Component downloadStatus() {
            TtsModelService.DownloadStatus status = ttsModelService().downloadStatus();
            if (status != null && status.downloading()) {
                Component label = status.cancelling()
                        ? tts("status.cancelling")
                        : status.paused()
                        ? tts("status.paused")
                        : status.label() == null || status.label().isBlank() ? tts("status.downloading") : localizedKey(status.label(), "status.downloading");
                return tts("download.progress", label, Math.max(0, Math.min(100, status.progress())));
            }
            return tts("status.idle");
        }

        private void buildDownloadItemActions(ModuleSettingsContext context, TtsModelInfo info, com.rheinmetal.tianshu.client.gui.settings.api.ItemActionTemplate<TtsModelInfo> actions) {
            actions.button("tts.download.item.start", tts("action.download"), SettingsButtonStyle.PRIMARY, model -> startDownload(context, model), model -> cardState(model).canStartDownload())
                    .button("tts.download.item.pause", tts("action.pause"), this::pauseDownload, model -> cardState(model).canPauseDownload())
                    .button("tts.download.item.resume", tts("action.resume"), this::resumeDownload, model -> cardState(model).canResumeDownload())
                    .button("tts.download.item.cancel", tts("action.cancel"), SettingsButtonStyle.DANGER, this::cancelDownload, model -> cardState(model).canCancelDownload())
                    .button("tts.download.item.delete", tts("action.delete"), SettingsButtonStyle.DANGER, model -> deleteModel(context, model), model -> cardState(model).canDeleteModel());
        }

        private void startDownload(ModuleSettingsContext context, TtsModelInfo info) {
            if (info == null || downloadInProgress()) {
                return;
            }
            ttsModelService().downloadModel(info, githubProxyUrl.get(), new TtsModelService.DownloadProgressCallback() {
                @Override
                public void onProgress(String label, int percent) {
                    requestDownloadRefresh();
                }

                @Override
                public void onComplete() {
                    runOnClient(() -> {
                        context.showStatus(tts("message.download_complete"), 3000);
                        coreManager.refreshRuntime(RuntimeRefreshReason.RESOURCE_CHANGED);
                        refreshSettingsScreen();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnClient(() -> {
                        Component downloadLabel = localizedKey(message, "status.download_failed");
                        context.showStatus(downloadLabel, 4000);
                        refreshSettingsScreen();
                    });
                }

                @Override
                public void onCancelled() {
                    runOnClient(() -> {
                        context.showStatus(tts("status.cancelled"), 1500);
                        refreshSettingsScreen();
                    });
                }
            });
        }

        private void pauseDownload(TtsModelInfo info) {
            if (!cardState(info).canPauseDownload()) {
                return;
            }
            ttsModelService().pauseDownload();
            refreshSettingsScreen();
        }

        private void resumeDownload(TtsModelInfo info) {
            if (!cardState(info).canResumeDownload()) {
                return;
            }
            ttsModelService().resumeDownload();
            refreshSettingsScreen();
        }

        private void cancelDownload(TtsModelInfo info) {
            if (!cardState(info).canCancelDownload()) {
                return;
            }
            ttsModelService().cancelDownload();
            refreshSettingsScreen();
        }

        private boolean downloadInProgress() {
            TtsModelService.DownloadStatus status = ttsModelService().downloadStatus();
            return status != null && status.downloading();
        }

        private TtsModelCardState cardState(TtsModelInfo info) {
            TtsModelService.DownloadStatus status = ttsModelService().downloadStatus();
            boolean downloading = status != null && status.downloading();
            boolean activeDownload = downloading && info != null && sameModel(info, resolveModel(status.activeModelName()));
            boolean paused = activeDownload && status.paused();
            boolean cancelling = activeDownload && status.cancelling();
            boolean installed = info != null && isDownloaded(info);
            boolean operationActive = downloading || ttsModelService().isDeleting();
            return new TtsModelCardState(info, installed, operationActive, activeDownload, paused, cancelling);
        }

        private void deleteModel(ModuleSettingsContext context, TtsModelInfo info) {
            if (info == null || downloadInProgress() || ttsModelService().isDeleting()) {
                return;
            }
            String deletedModelName = info.name == null ? "" : info.name;
            Component displayName = Component.literal(info.getDisplayName());
            ttsModelService().deleteModelAsync(info, deleted -> runOnClient(() -> {
                if (deleted) {
                    if (!deletedModelName.isBlank() && deletedModelName.equalsIgnoreCase(selectedModelName.get())) {
                        selectedModelName.set("");
                    }
                    context.showStatus(tts("message.deleted", displayName), 3000);
                    coreManager.refreshRuntime(RuntimeRefreshReason.RESOURCE_CHANGED);
                } else {
                    context.showStatus(tts("message.delete_failed"), 3000);
                }
                refreshSettingsScreen();
            }));
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
                        .thenComparing(Comparator.comparingInt(this::performanceScore).reversed())
                        .thenComparing(TtsModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            }
            if (mode == SortMode.PERFORMANCE) {
                return Comparator.comparingInt(this::performanceScore).reversed()
                        .thenComparing(Comparator.comparingInt(this::qualityScore).reversed())
                        .thenComparing(TtsModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            }
            if (mode == SortMode.NAME) {
                return Comparator.comparing(TtsModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
            }
            return Comparator.comparingInt(this::recommendedScore).reversed()
                    .thenComparing(Comparator.comparingInt(this::qualityScore).reversed())
                    .thenComparing(Comparator.comparingInt(this::performanceScore).reversed())
                    .thenComparing(TtsModelInfo::getDisplayName, String.CASE_INSENSITIVE_ORDER);
        }

        private boolean matchesFilters(TtsModelInfo info) {
            return info != null
                    && matchesScore(performanceFilter.get(), info.getPerformanceScore())
                    && matchesScore(qualityFilter.get(), info.getSynthesisQualityScore())
                    && matchesScore(recommendedFilter.get(), info.getRecommendationScore())
                    && matchesVoiceClone(info);
        }

        private boolean matchesVoiceClone(TtsModelInfo info) {
            String filter = voiceCloneFilter.get();
            return filter == null || ALL.equals(filter) || (SUPPORTS.equals(filter) == info.supportsVoiceClone());
        }

        private SettingsListCard downloadModelCard(TtsModelInfo info) {
            if (info == null) {
                return SettingsListCard.text(Component.empty());
            }
            TtsModelCardState state = cardState(info);
            Component title = Component.literal(info.getDisplayName());
            Component status = state.statusLabel();
            List<Component> details = List.of(
                    tts("download.card.language", languageLabel(info)),
                    tts("download.card.size", sizeLabel(info.size)),
                    tts("download.card.voice_clone", common(info.supportsVoiceClone() ? "yes" : "no"))
            );
            List<Component> badges = List.of(
                    scoreBadge(info.getPerformanceScore(), tts("badge.performance")),
                    scoreBadge(info.getSynthesisQualityScore(), tts("badge.quality")),
                    scoreBadge(info.getRecommendationScore(), tts("badge.recommendation"))
            );
            return new SettingsListCard(title, status, details, badges);
        }

        private List<String> scoreFilterValues(java.util.function.ToIntFunction<TtsModelInfo> mapper) {
            Set<String> values = new LinkedHashSet<>();
            values.add(ALL);
            for (TtsModelInfo info : catalog) {
                values.add(String.valueOf(mapper.applyAsInt(info)));
            }
            return List.copyOf(values);
        }

        private Component scoreFilterLabel(String value) {
            return ALL.equals(value) ? common("all") : tts("score.at_least", value);
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

        private Component scoreBadge(int score, Component label) {
            return Component.translatable("tianshu.gui.settings.badge.score", label, score);
        }

        private String sizeLabel(long bytes) {
            if (bytes <= 0L) {
                return "-";
            }
            double value = bytes;
            String[] units = {"B", "KB", "MB", "GB"};
            int unit = 0;
            while (value >= 1024.0D && unit < units.length - 1) {
                value /= 1024.0D;
                unit++;
            }
            return unit == 0 ? String.format(Locale.ROOT, "%.0f %s", value, units[unit]) : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
        }

        private int qualityScore(TtsModelInfo info) {
            return info == null ? 0 : info.getSynthesisQualityScore();
        }

        private int recommendedScore(TtsModelInfo info) {
            return info == null ? 0 : info.getRecommendationScore();
        }

        private int performanceScore(TtsModelInfo info) {
            return info == null ? 0 : info.getPerformanceScore();
        }

        private Component selectedVoiceStatus() {
            if (!supportsVoiceClone()) {
                return tts("status.voice_clone_not_required");
            }
            if (!NO_VOICE_SAMPLE.equals(selectedVoiceSample.get())) {
                return Component.literal(selectedVoiceSample.get());
            }
            TtsModelInfo info = resolveModel(selectedModelName.get());
            Path defaultSample = ttsModelService().resolveVoiceSamplePath(info, "");
            return defaultSample == null ? common("not_selected") : tts("status.voice_model_default", defaultSample.getFileName().toString());
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
                    coreManager.refreshRuntime(reason);
                }
            });
        }

        private void runOnClient(Runnable runnable) {
            Minecraft.getInstance().execute(runnable);
        }

        private void requestDownloadRefresh() {
            long now = System.currentTimeMillis();
            if (now - lastDownloadRefreshMillis < DOWNLOAD_REFRESH_INTERVAL_MILLIS) {
                return;
            }
            if (!downloadRefreshQueued.compareAndSet(false, true)) {
                return;
            }
            runOnClient(() -> {
                try {
                    lastDownloadRefreshMillis = System.currentTimeMillis();
                    refreshSettingsScreen();
                } finally {
                    downloadRefreshQueued.set(false);
                }
            });
        }

        private void refreshSettingsScreen() {
            if (Minecraft.getInstance().screen instanceof TianshuSettingsScreen settingsScreen) {
                settingsScreen.rebuildCurrentPage();
            }
        }
    }

    private record TtsModelCardState(TtsModelInfo info, boolean installed, boolean operationActive, boolean activeDownload, boolean paused, boolean cancelling) {
        private boolean canStartDownload() {
            return info != null && !installed && !operationActive;
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
            return info != null && installed && !operationActive;
        }

        private Component statusLabel() {
            if (activeDownload) {
                if (cancelling) {
                    return tts("status.cancelling");
                }
                return paused ? tts("status.paused") : tts("status.downloading");
            }
            return common(installed ? "downloaded" : "not_downloaded");
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
