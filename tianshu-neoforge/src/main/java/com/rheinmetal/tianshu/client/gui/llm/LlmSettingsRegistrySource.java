package com.rheinmetal.tianshu.client.gui.llm;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.screen.TianshuSettingsScreen;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.llm.LlmModelService;
import com.rheinmetal.tianshu.function.llm.LlmModuleService;
import com.rheinmetal.tianshu.function.llm.runtime.LlmControlResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.model.LlmModelInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

public final class LlmSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static final String MODULE_ID = "module.llm";
    private static final String ALL = "all";
    private static final String RECOMMENDED = "recommended";
    private static final String SMALL = "small";
    private static final String MEDIUM = "medium";
    private static final String LARGE = "large";
    private static final String DOWNLOADED = "downloaded";
    private static final String NOT_DOWNLOADED = "not_downloaded";
    private static final Component TITLE = llm("title");
    private static final Component DESCRIPTION = llm("description");

    private static Component llm(String key, Object... args) {
        return Component.translatable("tianshu.gui.llm." + key, args);
    }

    private static Component common(String key, Object... args) {
        return Component.translatable("tianshu.gui.common." + key, args);
    }

    private final TianshuCoreManager coreManager;
    private final ClientConfig config;

    public LlmSettingsRegistrySource(TianshuCoreManager coreManager, ClientConfig config) {
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
                .order(25)
                .panel(this::buildPanel)
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context) {
        LlmSettingsDraft draft = new LlmSettingsDraft(config, coreManager);
        context.settingsSessions().registerOrReplace(draft);

        panel.enable("llm.enabled", llm("enabled"), draft.enabled)
                .status("llm.device", llm("section.device"), draft::buildDeviceStatus)
                .options("llm.load.settings", llm("section.load_settings"), draft.enabled::get, draft::buildLoadOptions)
                .status("llm.load.info", llm("section.load_settings"), draft.enabled::get, draft::buildModelInfo)
                .actions("llm.load.control", llm("section.load_settings"), draft.enabled::get, actions -> actions
                        .button("llm.load.start", llm("action.load"), SettingsButtonStyle.PRIMARY, () -> draft.startLoad(context), draft::canLoad)
                        .button("llm.load.stop", llm("action.unload"), SettingsButtonStyle.DANGER, () -> draft.stopLoad(context), draft::canUnload))
                .status("llm.load.status", llm("section.load_settings"), draft.enabled::get, draft::buildLoadStatus)
                .separator("llm.download.separator")
                .enable("llm.download.expand", llm("download.expand"), draft.downloadExpanded::get, draft.downloadExpanded::set)
                .options("llm.download.filters", llm("section.download"), () -> true, draft.downloadExpanded::get, draft::buildDownloadFilters)
                .<LlmModelInfo>list("llm.download.models", llm("section.download_models"), () -> true, draft.downloadExpanded::get, list -> list
                        .items(draft::downloadableModels)
                        .label(draft::downloadModelLabel)
                        .itemActions((model, actions) -> draft.buildDownloadItemActions(context, model, actions))
                        .emptyText(llm("download.empty")))
                .status("llm.download.status", llm("section.download_status"), () -> true, draft.downloadExpanded::get, draft::buildDownloadStatus);
    }

    private static final class LlmSettingsDraft implements com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession {
        private final ClientConfig config;
        private final TianshuCoreManager coreManager;
        private final LlmModuleService moduleService;
        private final LlmModelService modelService;
        private final MutableSettingsValue<Boolean> enabled;
        private final MutableSettingsValue<String> selectedModelName;
        private final MutableSettingsValue<Boolean> downloadExpanded;
        private final MutableSettingsValue<String> recommendationFilter;
        private final MutableSettingsValue<String> scaleFilter;
        private final MutableSettingsValue<String> downloadStateFilter;

        private LlmSettingsDraft(ClientConfig config, TianshuCoreManager coreManager) {
            this.config = config;
            this.coreManager = coreManager;
            this.moduleService = coreManager.requireService(LlmModuleService.class);
            this.modelService = coreManager.requireService(LlmModelService.class);
            this.enabled = new MutableSettingsValue<>(config::isLlmEnabled, config::setLlmEnabled);
            this.selectedModelName = new MutableSettingsValue<>(this::currentModelName, ignored -> {}, Objects::nonNull);
            this.downloadExpanded = new MutableSettingsValue<>(() -> false, ignored -> {});
            this.recommendationFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.scaleFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.downloadStateFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
        }

        private void buildDeviceStatus(com.rheinmetal.tianshu.client.gui.settings.api.StatusTemplate status) {
            status.row("llm.device.gpu", llm("row.gpu"), () -> {
                String gpuName = safeGpuName();
                return gpuName == null ? common("unknown") : Component.literal(gpuName);
            });
            status.row("llm.device.vram", llm("row.vram"), () -> {
                long[] vram = safeVramBytes();
                if (vram == null) return common("unknown");
                long totalMb = vram[0] / (1024 * 1024);
                long usedMb = Math.max(0L, vram[1] / (1024 * 1024));
                long llmMb = llmResourceUsageMb();
                long freeMb = Math.max(0L, totalMb - usedMb - llmMb);
                return llm("vram.usage", usedMb, llmMb, freeMb, totalMb);
            });
            status.row("llm.device.memory", llm("row.memory"), () -> {
                Runtime runtime = Runtime.getRuntime();
                long gameMb = Math.max(0L, (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
                long llmMb = llmResourceUsageMb();
                long totalMb = runtime.maxMemory() / (1024 * 1024);
                long freeMb = Math.max(0L, totalMb - gameMb - llmMb);
                return llm("memory.usage", gameMb, llmMb, freeMb, totalMb);
            });
            status.row("llm.device.recommended", llm("row.recommended"), this::recommendedScaleStatus);
        }

        private void buildModelInfo(com.rheinmetal.tianshu.client.gui.settings.api.StatusTemplate status) {
            status.row("llm.info.name", llm("row.model_name"), this::selectedModelNameStatus);
            status.row("llm.info.scale", llm("row.model_scale"), this::selectedModelScaleStatus);
            status.row("llm.info.size", llm("row.model_size"), this::selectedModelSizeStatus);
        }

        private void buildLoadOptions(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("llm.model", llm("option.model"), downloadedModelNames(), selectedModelName, this::modelOptionLabel, enabled::get);
        }

        private void buildDownloadFilters(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("llm.download.recommended", llm("option.recommended"), List.of(ALL, RECOMMENDED), recommendationFilter, this::filterLabel)
                    .select("llm.download.scale", llm("option.scale"), List.of(ALL, SMALL, MEDIUM, LARGE), scaleFilter, this::filterLabel)
                    .select("llm.download.state", llm("option.download_state"), List.of(ALL, DOWNLOADED, NOT_DOWNLOADED), downloadStateFilter, this::filterLabel);
        }

        private void buildLoadStatus(com.rheinmetal.tianshu.client.gui.settings.api.StatusTemplate status) {
            status.row("llm.load.state", llm("row.load_state"), this::loadStateStatus);
        }

        private void buildDownloadStatus(com.rheinmetal.tianshu.client.gui.settings.api.StatusTemplate status) {
            status.row("llm.download.progress", llm("row.download_status"), this::downloadStatusText);
        }

        @Override
        public String moduleId() {
            return MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty()
                    || selectedModelName.dirty();
        }

        @Override
        public SettingsValidationResult validate() {
            LlmModelInfo selected = resolveModel(selectedModelName.get());
            if (enabled.get() && selectedModelName.get() != null && !selectedModelName.get().isBlank() && (!selectedModelName.valid() || selected == null)) {
                return SettingsValidationResult.failure(llm("validation.invalid_model"));
            }
            if (enabled.get() && selected != null && !modelService.hasModelContent(selected)) {
                return SettingsValidationResult.failure(llm("validation.model_not_installed"));
            }
            return SettingsValidationResult.successful();
        }

        @Override
        public SettingsSaveResult save() {
            enabled.save();
            config.setCustomLlmName(selectedModelName.get());
            selectedModelName.save();
            config.save();
            if (!enabled.get()) {
                moduleService.unload();
            }
            return SettingsSaveResult.success(llm("message.saved"), true, true);
        }

        @Override
        public void reset() {
            enabled.reset();
            selectedModelName.reset();
        }

        private List<String> downloadedModelNames() {
            List<String> downloaded = modelService.downloadedModels().stream()
                    .map(info -> info.name)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
            java.util.ArrayList<String> values = new java.util.ArrayList<>(downloaded.size() + 2);
            values.add("");
            String configured = config.getCustomLlmName();
            if (configured != null && !configured.isBlank() && resolveModel(configured) != null && downloaded.stream().noneMatch(name -> name.equalsIgnoreCase(configured))) {
                values.add(configured.trim());
            }
            values.addAll(downloaded);
            return values;
        }

        private String currentModelName() {
            String configured = config.getCustomLlmName();
            if (configured != null && !configured.isBlank() && resolveModel(configured) != null) {
                return configured;
            }
            return "";
        }

        private Component modelOptionLabel(String modelName) {
            return modelName == null || modelName.isBlank() ? common("not_selected") : Component.literal(modelName);
        }

        private LlmModelInfo resolveModel(String name) {
            return modelService.resolveModel(name);
        }

        private Component selectedModelNameStatus() {
            LlmModelInfo info = resolveModel(selectedModelName.get());
            return info == null ? common("not_selected") : Component.literal(info.getDisplayName());
        }

        private Component selectedModelScaleStatus() {
            LlmModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) return common("dash");
            String name = info.name.toLowerCase();
            if (name.contains("0.6b") || name.contains("1b")) return Component.literal("0.6B - 1B");
            if (name.contains("1.5b") || name.contains("2b")) return Component.literal("1.5B - 2B");
            if (name.contains("3b")) return Component.literal("3B");
            if (name.contains("4b")) return Component.literal("4B");
            if (name.contains("7b") || name.contains("8b")) return Component.literal("7B - 8B");
            if (name.contains("14b") || name.contains("13b")) return Component.literal("13B - 14B");
            return Component.literal("-");
        }

        private Component selectedModelSizeStatus() {
            LlmModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) return common("dash");
            if (!modelService.hasModelContent(info)) return common("not_downloaded");
            long sizeBytes = modelService.modelSizeBytes(info);
            if (sizeBytes <= 0L) return common("unknown");
            if (sizeBytes > 1024L * 1024 * 1024) {
                return Component.literal(String.format("%.1f GB", sizeBytes / (1024.0 * 1024 * 1024)));
            }
            return Component.literal(String.format("%.0f MB", sizeBytes / (1024.0 * 1024)));
        }

        private boolean canLoad() {
            if (!enabled.get()) return false;
            LlmRuntimeSnapshot snapshot = moduleService.snapshot();
            if (snapshot.state() == LlmRuntimeState.STARTING || snapshot.state() == LlmRuntimeState.STOPPING || snapshot.running()) return false;
            LlmModelInfo info = resolveModel(selectedModelName.get());
            return info != null && modelService.hasModelContent(info);
        }

        private boolean canUnload() {
            if (!enabled.get()) return false;
            LlmRuntimeSnapshot snapshot = moduleService.snapshot();
            return snapshot.running() || snapshot.state() == LlmRuntimeState.STARTING;
        }

        private void startLoad(ModuleSettingsContext context) {
            LlmModelInfo info = resolveModel(selectedModelName.get());
            if (info == null || !modelService.hasModelContent(info)) {
                context.showStatus(llm("state.not_downloaded"), 3000);
                return;
            }
            config.setCustomLlmName(info.name);
            config.save();
            LlmControlResult result = moduleService.load();
            context.showStatus(result.accepted() ? llm("message.load_started") : Component.literal(result.message()), 3000);
        }

        private void stopLoad(ModuleSettingsContext context) {
            LlmControlResult result = moduleService.unload();
            context.showStatus(result.accepted() ? llm("message.unload_started") : Component.literal(result.message()), 3000);
        }

        private Component loadStateStatus() {
            if (!enabled.get()) return llm("state.disabled");
            LlmModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) return llm("state.no_model");
            if (!modelService.hasModelContent(info)) return llm("state.not_downloaded");
            LlmRuntimeSnapshot snapshot = moduleService.snapshot();
            return switch (snapshot.state()) {
                case DISABLED -> llm("state.disabled");
                case STARTING -> llm("state.loading");
                case RUNNING -> llm("state.loaded");
                case STOPPING -> llm("state.unloading");
                case FAILED -> snapshot.failureMessage().isBlank() ? llm("state.failed") : Component.literal(snapshot.failureMessage());
                case STOPPED -> llm("state.unloaded");
            };
        }

        private Component recommendedScaleStatus() {
            long[] vram = safeVramBytes();
            if (vram == null) return common("unknown");
            long freeMb = Math.max(0L, (vram[0] - vram[1]) / (1024 * 1024));
            if (freeMb < 2048) return llm("recommended.tiny");
            if (freeMb < 4096) return llm("recommended.small");
            if (freeMb < 8192) return llm("recommended.medium");
            if (freeMb < 16384) return llm("recommended.large");
            return llm("recommended.xlarge");
        }

        private long llmResourceUsageMb() {
            LlmRuntimeSnapshot snapshot = moduleService.snapshot();
            return snapshot.running() || snapshot.state() == LlmRuntimeState.STARTING ? Math.max(0L, modelService.modelSizeBytes(resolveModel(selectedModelName.get())) / (1024 * 1024)) : 0L;
        }

        private String safeGpuName() {
            try {
                return GpuInfo.queryGpuName();
            } catch (Exception e) {
                return null;
            }
        }

        private long[] safeVramBytes() {
            try {
                return GpuInfo.queryVramBytes();
            } catch (Exception e) {
                return null;
            }
        }

        private List<LlmModelInfo> downloadableModels() {
            return modelService.allModels().stream()
                    .filter(this::matchesRecommendationFilter)
                    .filter(this::matchesScaleFilter)
                    .filter(this::matchesDownloadStateFilter)
                    .toList();
        }

        private Component downloadModelLabel(LlmModelInfo info) {
            if (info == null) return Component.empty();
            boolean downloaded = modelService.hasModelContent(info);
            return llm("download.card",
                    info.getDisplayName(),
                    modelScaleLabel(info).getString(),
                    modelDownloadSizeLabel(info).getString(),
                    modelRecommendationLabel(info).getString(),
                    common(downloaded ? "downloaded" : "not_downloaded").getString());
        }

        private void buildDownloadItemActions(ModuleSettingsContext context, LlmModelInfo info, com.rheinmetal.tianshu.client.gui.settings.api.ItemActionTemplate<LlmModelInfo> actions) {
            actions.button("llm.download.item.start", llm("action.download"), SettingsButtonStyle.PRIMARY, model -> startDownload(context, model), model -> model != null && !modelService.isDownloading() && !modelService.hasModelContent(model))
                    .button("llm.download.item.cancel", llm("action.cancel"), SettingsButtonStyle.DANGER, model -> cancelDownload(context), this::isActiveDownload)
                    .button("llm.download.item.delete", llm("action.delete"), SettingsButtonStyle.DANGER, model -> deleteModel(context, model), model -> model != null && !modelService.isDownloading() && modelService.hasModelContent(model));
        }

        private void startDownload(ModuleSettingsContext context, LlmModelInfo info) {
            if (info == null || modelService.isDownloading()) return;
            modelService.downloadModel(info, new LlmModelService.DownloadProgressCallback() {
                @Override
                public void onProgress(String label, int percent) {
                    runOnClient(() -> refreshSettingsScreen());
                }

                @Override
                public void onComplete() {
                    runOnClient(() -> {
                        context.showStatus(llm("message.download_complete"), 3000);
                        refreshSettingsScreen();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnClient(() -> {
                        context.showStatus(message == null || message.isBlank() ? llm("status.download_failed") : Component.literal(message), 4000);
                        refreshSettingsScreen();
                    });
                }
            });
        }

        private void cancelDownload(ModuleSettingsContext context) {
            modelService.cancelDownload();
            context.showStatus(llm("status.cancelling"), 3000);
        }

        private boolean isActiveDownload(LlmModelInfo info) {
            LlmModelService.DownloadSnapshot snapshot = modelService.downloadSnapshot();
            return info != null && snapshot.running() && info.name != null && info.name.equalsIgnoreCase(snapshot.modelName());
        }

        private void deleteModel(ModuleSettingsContext context, LlmModelInfo info) {
            if (info == null || modelService.isDownloading()) return;
            if (modelService.deleteModel(info)) {
                context.showStatus(llm("message.deleted", info.getDisplayName()), 3000);
                if (info.name != null && info.name.equalsIgnoreCase(selectedModelName.get())) {
                    selectedModelName.set(currentModelName());
                }
            } else {
                context.showStatus(llm("message.delete_failed"), 3000);
            }
        }

        private Component downloadStatusText() {
            LlmModelService.DownloadSnapshot snapshot = modelService.downloadSnapshot();
            if (snapshot.running()) {
                return llm("download.progress", Component.literal(snapshot.label()), snapshot.percent());
            }
            if (!snapshot.errorMessage().isBlank()) {
                return Component.literal(snapshot.errorMessage());
            }
            return Component.literal(snapshot.label());
        }

        private void runOnClient(Runnable runnable) {
            Minecraft.getInstance().execute(runnable);
        }

        private void refreshSettingsScreen() {
            if (Minecraft.getInstance().screen instanceof TianshuSettingsScreen settingsScreen) {
                settingsScreen.rebuildCurrentPage();
            }
        }

        private Component filterLabel(String value) {
            return switch (value == null ? ALL : value) {
                case RECOMMENDED -> llm("filter.recommended");
                case SMALL -> llm("filter.small");
                case MEDIUM -> llm("filter.medium");
                case LARGE -> llm("filter.large");
                case DOWNLOADED -> common("downloaded");
                case NOT_DOWNLOADED -> common("not_downloaded");
                default -> llm("filter.all");
            };
        }

        private boolean matchesRecommendationFilter(LlmModelInfo info) {
            if (!RECOMMENDED.equals(recommendationFilter.get())) {
                return true;
            }
            long[] vram = safeVramBytes();
            if (vram == null) {
                return true;
            }
            long freeMb = Math.max(0L, (vram[0] - vram[1]) / (1024 * 1024));
            return modelScaleRank(info) <= recommendedScaleRank(freeMb);
        }

        private boolean matchesScaleFilter(LlmModelInfo info) {
            return switch (scaleFilter.get()) {
                case SMALL -> modelScaleRank(info) <= 1;
                case MEDIUM -> modelScaleRank(info) == 2;
                case LARGE -> modelScaleRank(info) >= 3;
                default -> true;
            };
        }

        private boolean matchesDownloadStateFilter(LlmModelInfo info) {
            return switch (downloadStateFilter.get()) {
                case DOWNLOADED -> modelService.hasModelContent(info);
                case NOT_DOWNLOADED -> !modelService.hasModelContent(info);
                default -> true;
            };
        }

        private Component modelScaleLabel(LlmModelInfo info) {
            if (info == null || info.name == null) return common("dash");
            String name = info.name.toLowerCase();
            if (name.contains("0.6b")) return Component.literal("0.6B");
            if (name.contains("1.5b")) return Component.literal("1.5B");
            if (name.contains("1b")) return Component.literal("1B");
            if (name.contains("2b")) return Component.literal("2B");
            if (name.contains("3b")) return Component.literal("3B");
            if (name.contains("4b")) return Component.literal("4B");
            if (name.contains("7b")) return Component.literal("7B");
            if (name.contains("8b")) return Component.literal("8B");
            if (name.contains("13b")) return Component.literal("13B");
            if (name.contains("14b")) return Component.literal("14B");
            return common("dash");
        }

        private Component modelDownloadSizeLabel(LlmModelInfo info) {
            if (info == null) return common("unknown");
            if (modelService.hasModelContent(info)) {
                return selectedModelSizeStatusFor(info);
            }
            return common("unknown");
        }

        private Component modelRecommendationLabel(LlmModelInfo info) {
            return switch (modelScaleRank(info)) {
                case 0 -> llm("download.recommend.tiny");
                case 1 -> llm("download.recommend.small");
                case 2 -> llm("download.recommend.medium");
                case 3 -> llm("download.recommend.large");
                default -> llm("download.recommend.xlarge");
            };
        }

        private Component selectedModelSizeStatusFor(LlmModelInfo info) {
            if (info == null) return common("dash");
            if (!modelService.hasModelContent(info)) return common("not_downloaded");
            long sizeBytes = modelService.modelSizeBytes(info);
            if (sizeBytes <= 0L) return common("unknown");
            if (sizeBytes > 1024L * 1024 * 1024) {
                return Component.literal(String.format("%.1f GB", sizeBytes / (1024.0 * 1024 * 1024)));
            }
            return Component.literal(String.format("%.0f MB", sizeBytes / (1024.0 * 1024)));
        }

        private int modelScaleRank(LlmModelInfo info) {
            if (info == null || info.name == null) return 2;
            String name = info.name.toLowerCase();
            if (name.contains("0.6b") || name.contains("1b")) return 0;
            if (name.contains("1.5b") || name.contains("2b") || name.contains("3b")) return 1;
            if (name.contains("4b") || name.contains("7b") || name.contains("8b")) return 2;
            if (name.contains("13b") || name.contains("14b")) return 3;
            return 2;
        }

        private int recommendedScaleRank(long freeMb) {
            if (freeMb < 4096) return 1;
            if (freeMb < 8192) return 2;
            if (freeMb < 16384) return 3;
            return 4;
        }
    }
}
