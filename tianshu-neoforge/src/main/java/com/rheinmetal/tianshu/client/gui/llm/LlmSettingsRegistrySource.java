package com.rheinmetal.tianshu.client.gui.llm;

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
import com.rheinmetal.tianshu.function.llm.LlmModelService;
import com.rheinmetal.tianshu.function.llm.LlmModuleService;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.function.llm.runtime.LlmMtpCalibrationRequest;
import com.rheinmetal.tianshu.function.llm.runtime.LlmControlResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.model.LlmModelInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlmSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static final String MODULE_ID = "module.llm";
    private static final String AUTO_DEVICE_ID = "auto";
    private static final String LEGACY_CPU_DEVICE_ID = "cpu";
    private static final String CPU_DEVICE_ID = "cpu:manual";
    private static final String ALL = "all";
    private static final String DOWNLOADED = "downloaded";
    private static final String NOT_DOWNLOADED = "not_downloaded";
    private static final long DOWNLOAD_REFRESH_INTERVAL_MILLIS = 250L;
    private static final Pattern PARAMETER_SCALE_PATTERN = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*b\\b");
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
        LlmSettingsDraft draft = new LlmSettingsDraft(config, coreManager);
        context.settingsSessions().registerOrReplace(draft);
        registry.registerCategory(ModuleSettingsCategory.builder(MODULE_ID)
                .title(TITLE)
                .description(DESCRIPTION)
                .order(25)
                .panel((panel, panelContext) -> buildPanel(panel, panelContext, draft))
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context, LlmSettingsDraft draft) {
        panel.columns("llm.layout", 0.42D, 0.58D, columns -> columns
                .column(0, left -> buildSettingsColumn(left, context, draft))
                .column(1, right -> buildDownloadColumn(right, context, draft)));
    }

    private void buildSettingsColumn(ModuleSettingsPanel panel, ModuleSettingsContext context, LlmSettingsDraft draft) {
        panel.enable("llm.enabled", llm("enabled"), draft.enabled)
                .toggles("llm.diagnostics", common("section.diagnostics"), toggles -> toggles
                        .toggle("llm.diagnostics.enabled", common("option.diagnostics_enabled"), draft.diagnosticsEnabled))
                .status("llm.device", llm("section.device"), draft::buildDeviceStatus)
                .compound("llm.load", llm("section.load_settings"), draft.enabled::get,
                        draft::buildLoadOptions,
                        actions -> actions
                                .button("llm.load.start", llm("action.load"), SettingsButtonStyle.PRIMARY, () -> draft.startLoad(context), draft::canLoad)
                                .button("llm.load.stop", llm("action.unload"), SettingsButtonStyle.DANGER, () -> draft.stopLoad(context), draft::canUnload)
                                .button("llm.mtp.calibrate", llm("action.mtp_calibrate"), () -> draft.calibrateMtp(context), draft::canCalibrateMtp, draft::mtpSupported),
                        status -> {
                            draft.buildModelInfo(status);
                            draft.buildLoadStatus(status);
                        });
    }

    private void buildDownloadColumn(ModuleSettingsPanel panel, ModuleSettingsContext context, LlmSettingsDraft draft) {
        panel.<LlmModelInfo>catalog("llm.download.catalog", llm("section.download_models"), draft::buildDownloadFilters, list -> list
                .items(draft::downloadableModels)
                .card(draft::downloadModelCard)
                .itemActions((model, actions) -> draft.buildDownloadItemActions(context, model, actions))
                .emptyText(llm("download.empty")));
    }

    private static final class LlmSettingsDraft implements com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession {
        private final ClientConfig config;
        private final TianshuCoreManager coreManager;
        private final LlmModuleService moduleService;
        private final LlmModelService modelService;
        private final MutableSettingsValue<Boolean> enabled;
        private final MutableSettingsValue<Boolean> diagnosticsEnabled;
        private final MutableSettingsValue<String> selectedModelName;
        private final MutableSettingsValue<String> selectedGpuDeviceId;
        private final MutableSettingsValue<Boolean> frameGuardEnabled;
        private final MutableSettingsValue<Double> frameGuardTargetFps;
        private final MutableSettingsValue<Boolean> mtpEnabled;
        private final MutableSettingsValue<String> seriesFilter;
        private final MutableSettingsValue<String> downloadStateFilter;
        private final AtomicBoolean downloadRefreshQueued = new AtomicBoolean(false);
        private volatile long lastDownloadRefreshMillis;

        private LlmSettingsDraft(ClientConfig config, TianshuCoreManager coreManager) {
            this.config = config;
            this.coreManager = coreManager;
            this.moduleService = coreManager.requireService(LlmModuleService.class);
            this.modelService = coreManager.requireService(LlmModelService.class);
            this.enabled = new MutableSettingsValue<>(config::isLlmEnabled, config::setLlmEnabled);
            this.diagnosticsEnabled = new MutableSettingsValue<>(config::isLlmDiagnosticsEnabled, config::setLlmDiagnosticsEnabled);
            this.selectedModelName = new MutableSettingsValue<>(this::currentModelName, ignored -> {}, Objects::nonNull);
            this.selectedGpuDeviceId = new MutableSettingsValue<>(this::currentDeviceTargetId, ignored -> {}, Objects::nonNull);
            this.frameGuardEnabled = new MutableSettingsValue<>(config::isLlmFrameGuardEnabled, config::setLlmFrameGuardEnabled);
            this.frameGuardTargetFps = new MutableSettingsValue<>(
                    () -> (double) config.getLlmFrameGuardTargetFps(),
                    value -> config.setLlmFrameGuardTargetFps(value == null ? 60 : (int) Math.round(value)),
                    value -> value != null && value >= 15.0D && value <= 240.0D
            );
            this.mtpEnabled = new MutableSettingsValue<>(config::isLlmMtpEnabled, config::setLlmMtpEnabled);
            this.seriesFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.downloadStateFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            GpuInfo.requestRefresh(() -> runOnClient(this::refreshSettingsScreen));
        }

        private void buildDeviceStatus(com.rheinmetal.tianshu.client.gui.settings.api.StatusTemplate status) {
            status.row("llm.device.gpu", llm("row.gpu"), this::selectedGpuStatus);
            status.row("llm.device.vram", llm("row.vram"), this::vramStatusText, () -> true, this::showVramStatus);
            status.row("llm.device.memory", llm("row.memory"), this::memoryStatusText, () -> true, this::showMemoryStatus);
        }

        private Component vramStatusText() {
            GpuInfo.GpuDevice device = selectedGpuDevice();
            if (device == null || device.vramTotalBytes() <= 0L) return common("unknown");
            long totalMb = device.vramTotalBytes() / (1024 * 1024);
            long usedMb = Math.max(0L, device.vramUsedBytes() / (1024 * 1024));
            long freeMb = Math.max(0L, totalMb - usedMb);
            return llm("vram.usage", usedMb, freeMb, totalMb);
        }

        private Component memoryStatusText() {
            Runtime runtime = Runtime.getRuntime();
            long gameMb = Math.max(0L, (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
            long llmMb = llmResourceUsageMb();
            long totalMb = runtime.maxMemory() / (1024 * 1024);
            long freeMb = Math.max(0L, totalMb - gameMb - llmMb);
            return llm("memory.usage", gameMb, llmMb, freeMb, totalMb);
        }

        private void buildModelInfo(com.rheinmetal.tianshu.client.gui.settings.api.StatusTemplate status) {
            status.row("llm.info.name", llm("row.model_name"), this::selectedModelNameStatus);
            status.row("llm.info.size", llm("row.model_size"), this::selectedModelSizeStatus);
        }

        private void buildLoadOptions(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("llm.model", llm("option.model"), downloadedModelNames(), selectedModelName, this::modelOptionLabel, enabled::get)
                    .select("llm.gpu.device", llm("option.gpu_device"), deviceTargetIds(), selectedGpuDeviceId, this::deviceTargetOptionLabel, enabled::get)
                    .select("llm.frame_guard.enabled", llm("option.frame_guard"), List.of(Boolean.TRUE, Boolean.FALSE), frameGuardEnabled, this::enabledLabel, enabled::get)
                    .slider("llm.frame_guard.target_fps", llm("option.target_fps"), frameGuardTargetFps, 15.0D, 240.0D, () -> enabled.get() && frameGuardEnabled.get())
                    .select("llm.mtp.enabled", llm("option.mtp"), List.of(Boolean.TRUE, Boolean.FALSE), mtpEnabled, this::enabledLabel, enabled::get, this::mtpSupported);
        }

        private void buildDownloadFilters(com.rheinmetal.tianshu.client.gui.settings.api.OptionTemplate options) {
            options.select("llm.download.series", llm("option.series"), seriesFilterValues(), seriesFilter, this::filterLabel)
                    .select("llm.download.state", llm("option.download_state"), List.of(ALL, DOWNLOADED, NOT_DOWNLOADED), downloadStateFilter, this::filterLabel);
        }

        private void buildLoadStatus(com.rheinmetal.tianshu.client.gui.settings.api.StatusTemplate status) {
            status.row("llm.load.state", llm("row.load_state"), this::loadStateStatus);
            status.row("llm.load.compatibility", llm("row.load_compatibility"), this::loadCompatibilityStatus);
            status.row("llm.mtp.capability", llm("row.mtp"), this::mtpStatusText, () -> true, this::mtpSupported);
            status.row("llm.download.progress", llm("row.download_status"), this::downloadStatusText);
        }

        @Override
        public String moduleId() {
            return MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty()
                    || diagnosticsEnabled.dirty()
                    || selectedModelName.dirty()
                    || selectedGpuDeviceId.dirty()
                    || frameGuardEnabled.dirty()
                    || frameGuardTargetFps.dirty()
                    || mtpEnabled.dirty();
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
            diagnosticsEnabled.save();
            config.setCustomLlmName(selectedModelName.get());
            selectedModelName.save();
            config.setLlmGpuDeviceId(persistedDeviceTargetId());
            selectedGpuDeviceId.save();
            frameGuardEnabled.save();
            frameGuardTargetFps.save();
            mtpEnabled.save();
            config.save();
            if (!enabled.get()) {
                moduleService.unload();
            }
            return SettingsSaveResult.success(llm("message.saved"), true, true);
        }

        @Override
        public void reset() {
            enabled.reset();
            diagnosticsEnabled.reset();
            selectedModelName.reset();
            selectedGpuDeviceId.reset();
            frameGuardEnabled.reset();
            frameGuardTargetFps.reset();
            mtpEnabled.reset();
        }

        private Component enabledLabel(Boolean value) {
            return common(Boolean.TRUE.equals(value) ? "on" : "off");
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

        private String currentDeviceTargetId() {
            String configured = normalizeDeviceId(config.getLlmGpuDeviceId());
            if (configured.isBlank() || isAutoDevice(configured)) {
                return AUTO_DEVICE_ID;
            }
            if (isLegacyCpuDevice(configured)) {
                return AUTO_DEVICE_ID;
            }
            if (isCpuDevice(configured) || gpuDeviceById(configured) != null) {
                return configured;
            }
            return AUTO_DEVICE_ID;
        }

        private List<String> deviceTargetIds() {
            List<GpuInfo.GpuDevice> devices = GpuInfo.devices();
            List<String> values = new ArrayList<>(devices.size() + 2);
            values.add(AUTO_DEVICE_ID);
            for (GpuInfo.GpuDevice device : devices) {
                values.add(device.id());
            }
            values.add(CPU_DEVICE_ID);
            return values;
        }

        private Component deviceTargetOptionLabel(String deviceId) {
            if (isAutoDevice(deviceId)) {
                return llm("device.auto", resolvedDeviceTargetLabel());
            }
            if (isCpuDevice(deviceId)) {
                return llm("device.cpu");
            }
            if (!GpuInfo.detected() || GpuInfo.detecting()) {
                return llm("device.detecting");
            }
            GpuInfo.GpuDevice device = gpuDeviceById(deviceId);
            return device == null ? common("unknown") : Component.literal(device.name());
        }

        private GpuInfo.GpuDevice gpuDeviceById(String deviceId) {
            for (GpuInfo.GpuDevice device : GpuInfo.devices()) {
                if (device.id().equalsIgnoreCase(deviceId == null ? "" : deviceId.trim())) {
                    return device;
                }
            }
            return null;
        }

        private LlmModelInfo resolveModel(String name) {
            return modelService.resolveModel(name);
        }

        private Component selectedModelNameStatus() {
            LlmModelInfo info = resolveModel(selectedModelName.get());
            return info == null ? common("not_selected") : Component.literal(info.getDisplayName());
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
            LoadCompatibility compatibility = loadCompatibility(info);
            if (!compatibility.supported()) {
                context.showStatus(compatibility.message(), 4000);
                return;
            }
            config.setLlmGpuDeviceId(persistedDeviceTargetId());
            config.save();
            LlmControlResult result = moduleService.load();
            context.showStatus(result.accepted() ? llm("message.load_started") : Component.literal(result.message()), 3000);
        }

        private void stopLoad(ModuleSettingsContext context) {
            LlmControlResult result = moduleService.unload();
            context.showStatus(result.accepted() ? llm("message.unload_started") : Component.literal(result.message()), 3000);
        }

        private boolean mtpSupported() {
            return coreManager.findService(LLMService.class)
                    .map(LLMService::supportsMtp)
                    .orElse(false);
        }

        private boolean canCalibrateMtp() {
            LlmRuntimeSnapshot snapshot = moduleService.snapshot();
            return enabled.get() && snapshot.running() && mtpSupported();
        }

        private void calibrateMtp(ModuleSettingsContext context) {
            coreManager.findService(LLMService.class).ifPresentOrElse(service -> {
                context.showStatus(llm("message.mtp_calibration_started"), 3000);
                service.calibrateMtpAsync(LlmMtpCalibrationRequest.defaults()).whenComplete((result, error) -> runOnClient(() -> {
                    if (error != null) {
                        context.showStatus(llm("message.mtp_calibration_failed", error.getMessage()), 5000);
                    } else if (result == null || !result.supported() || result.bestDraftMax() <= 0) {
                        context.showStatus(llm("message.mtp_calibration_unsupported"), 4000);
                    } else {
                        context.showStatus(llm("message.mtp_calibration_complete", result.bestDraftMax()), 5000);
                    }
                    refreshSettingsScreen();
                }));
            }, () -> context.showStatus(llm("state.unloaded"), 3000));
        }

        private Component mtpStatusText() {
            return coreManager.findService(LLMService.class)
                    .map(service -> {
                        var capability = service.getMtpCapability();
                        if (capability == null || !capability.supported()) {
                            return common("unsupported");
                        }
                        if (capability.calibrated() && capability.recommendedDraftMax() > 0) {
                            return llm("mtp.status.calibrated", capability.recommendedDraftMax());
                        }
                        return llm("mtp.status.supported");
                    })
                    .orElse(common("unsupported"));
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

        private Component loadCompatibilityStatus() {
            LlmModelInfo info = resolveModel(selectedModelName.get());
            if (info == null || !modelService.hasModelContent(info)) {
                return common("dash");
            }
            LoadCompatibility compatibility = loadCompatibility(info);
            return compatibility.supported() ? llm("compatibility.supported") : compatibility.message();
        }

        private long llmResourceUsageMb() {
            LlmRuntimeSnapshot snapshot = moduleService.snapshot();
            return snapshot.running() || snapshot.state() == LlmRuntimeState.STARTING ? Math.max(0L, modelService.modelSizeBytes(resolveModel(selectedModelName.get())) / (1024 * 1024)) : 0L;
        }

        private GpuInfo.GpuDevice selectedGpuDevice() {
            String targetId = effectiveDeviceTargetId();
            return isCpuDevice(targetId) ? null : GpuInfo.selectedDevice(targetId);
        }

        private String effectiveDeviceTargetId() {
            String configured = normalizeDeviceId(selectedGpuDeviceId.get());
            if (isAutoDevice(configured)) {
                return defaultDeviceTargetId();
            }
            if (!GpuInfo.detected() || GpuInfo.detecting()) {
                return configured;
            }
            if (isCpuDevice(configured) || gpuDeviceById(configured) != null) {
                return configured;
            }
            return defaultDeviceTargetId();
        }

        private String persistedDeviceTargetId() {
            String configured = normalizeDeviceId(selectedGpuDeviceId.get());
            return isAutoDevice(configured) ? "" : effectiveDeviceTargetId();
        }

        private Component resolvedDeviceTargetLabel() {
            String targetId = effectiveDeviceTargetId();
            if (!GpuInfo.detected() || GpuInfo.detecting()) {
                return llm("device.detecting");
            }
            if (isCpuDevice(targetId)) {
                return llm("device.cpu");
            }
            GpuInfo.GpuDevice device = gpuDeviceById(targetId);
            return device == null ? common("unknown") : Component.literal(device.name());
        }

        private String defaultDeviceTargetId() {
            if (!GpuInfo.detected() || GpuInfo.detecting()) {
                return AUTO_DEVICE_ID;
            }
            List<GpuInfo.GpuDevice> devices = GpuInfo.devices();
            if (devices.size() >= 2) {
                return devices.get(1).id();
            }
            if (devices.size() == 1) {
                return devices.get(0).id();
            }
            return CPU_DEVICE_ID;
        }

        private String normalizeDeviceId(String value) {
            return value == null ? "" : value.trim();
        }

        private boolean isCpuDevice(String value) {
            String normalized = normalizeDeviceId(value);
            return CPU_DEVICE_ID.equalsIgnoreCase(normalized) || LEGACY_CPU_DEVICE_ID.equalsIgnoreCase(normalized);
        }

        private boolean isLegacyCpuDevice(String value) {
            return LEGACY_CPU_DEVICE_ID.equalsIgnoreCase(normalizeDeviceId(value));
        }

        private boolean isAutoDevice(String value) {
            String normalized = normalizeDeviceId(value);
            return normalized.isBlank() || AUTO_DEVICE_ID.equalsIgnoreCase(normalized);
        }

        private Component selectedGpuStatus() {
            if (!GpuInfo.detected() || GpuInfo.detecting()) {
                return llm("device.detecting");
            }
            if (isCpuDevice(effectiveDeviceTargetId())) {
                return llm("device.cpu");
            }
            GpuInfo.GpuDevice device = selectedGpuDevice();
            if (device == null) {
                return llm("device.not_detected");
            }
            return Component.literal(device.name());
        }

        private boolean showVramStatus() {
            return GpuInfo.detected() && !GpuInfo.detecting() && !isCpuDevice(effectiveDeviceTargetId()) && selectedGpuDevice() != null;
        }

        private boolean showMemoryStatus() {
            return GpuInfo.detected() && !GpuInfo.detecting() && isCpuDevice(effectiveDeviceTargetId());
        }

        private LoadCompatibility loadCompatibility(LlmModelInfo info) {
            long requiredBytes = estimatedRequiredMemoryBytes(info);
            if (requiredBytes <= 0L) {
                return LoadCompatibility.ok();
            }
            if (isCpuDevice(effectiveDeviceTargetId())) {
                long availableBytes = availableJvmMemoryBytes();
                if (availableBytes > 0L && availableBytes < requiredBytes) {
                    return LoadCompatibility.unsupported(llm("validation.insufficient_memory", formatBytes(requiredBytes), formatBytes(availableBytes)));
                }
                return LoadCompatibility.ok();
            }
            GpuInfo.GpuDevice device = selectedGpuDevice();
            if (device == null || device.vramTotalBytes() <= 0L) {
                return LoadCompatibility.ok();
            }
            long availableBytes = Math.max(0L, device.vramTotalBytes() - device.vramUsedBytes());
            if (availableBytes < requiredBytes) {
                return LoadCompatibility.unsupported(llm("validation.insufficient_vram", formatBytes(requiredBytes), formatBytes(availableBytes)));
            }
            return LoadCompatibility.ok();
        }

        private long estimatedRequiredMemoryBytes(LlmModelInfo info) {
            long modelBytes = Math.max(modelService.modelSizeBytes(info), estimatedModelSizeBytes(info));
            if (modelBytes <= 0L) {
                return 0L;
            }
            return Math.round(modelBytes * 1.15D) + 512L * 1024L * 1024L;
        }

        private long availableJvmMemoryBytes() {
            Runtime runtime = Runtime.getRuntime();
            return Math.max(0L, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
        }

        private List<LlmModelInfo> downloadableModels() {
            return modelService.allModels().stream()
                    .filter(this::matchesSeriesFilter)
                    .filter(this::matchesDownloadStateFilter)
                    .toList();
        }

        private SettingsListCard downloadModelCard(LlmModelInfo info) {
            if (info == null) return SettingsListCard.text(Component.empty());
            LlmModelCardState state = cardState(info);
            Component title = Component.literal(info.getDisplayName());
            Component status = state.statusLabel();
            List<Component> details = new ArrayList<>();
            Component description = modelDescriptionLabel(info);
            if (description != null) {
                details.add(llm("download.card.description", description));
            }
            details.add(llm("download.card.series", modelSeriesLabel(info)));
            details.add(llm("download.card.size", modelDownloadSizeLabel(info)));
            details.add(llm("download.card.vram", estimatedVramLabel(info)));
            details.add(llm("download.card.thinking_capability", thinkingCapabilityLabel(info)));
            return new SettingsListCard(title, status, details, List.of());
        }

        private void buildDownloadItemActions(ModuleSettingsContext context, LlmModelInfo info, com.rheinmetal.tianshu.client.gui.settings.api.ItemActionTemplate<LlmModelInfo> actions) {
            actions.button("llm.download.item.start", llm("action.download"), SettingsButtonStyle.PRIMARY, model -> startDownload(context, model), model -> cardState(model).canStartDownload())
                    .button("llm.download.item.pause", llm("action.pause"), this::pauseDownload, model -> cardState(model).canPauseDownload())
                    .button("llm.download.item.resume", llm("action.resume"), this::resumeDownload, model -> cardState(model).canResumeDownload())
                    .button("llm.download.item.cancel", llm("action.cancel"), SettingsButtonStyle.DANGER, model -> cancelDownload(context, model), model -> cardState(model).canCancelDownload())
                    .button("llm.download.item.delete", llm("action.delete"), SettingsButtonStyle.DANGER, model -> deleteModel(context, model), model -> cardState(model).canDeleteModel());
        }

        private void startDownload(ModuleSettingsContext context, LlmModelInfo info) {
            if (info == null || modelService.isDownloading()) return;
            modelService.downloadModel(info, new LlmModelService.DownloadProgressCallback() {
                @Override
                public void onProgress(String label, int percent) {
                    requestDownloadRefresh();
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
                        context.showStatus(localizedDownloadMessage(message), 4000);
                        refreshSettingsScreen();
                    });
                }

                @Override
                public void onCancelled() {
                    runOnClient(() -> {
                        context.showStatus(llm("status.cancelled"), 1500);
                        refreshSettingsScreen();
                    });
                }
            });
        }

        private void pauseDownload(LlmModelInfo info) {
            if (!cardState(info).canPauseDownload()) {
                return;
            }
            modelService.pauseDownload();
            refreshSettingsScreen();
        }

        private void resumeDownload(LlmModelInfo info) {
            if (!cardState(info).canResumeDownload()) {
                return;
            }
            modelService.resumeDownload();
            refreshSettingsScreen();
        }

        private void cancelDownload(ModuleSettingsContext context, LlmModelInfo info) {
            if (!cardState(info).canCancelDownload()) {
                return;
            }
            modelService.cancelDownload();
            context.showStatus(llm("status.cancelling"), 3000);
            refreshSettingsScreen();
        }

        private LlmModelCardState cardState(LlmModelInfo info) {
            LlmModelService.DownloadSnapshot snapshot = modelService.downloadSnapshot();
            boolean downloading = snapshot.running();
            boolean activeDownload = downloading && sameModelName(info, snapshot.modelName());
            boolean paused = activeDownload && snapshot.paused();
            boolean cancelling = activeDownload && snapshot.cancelling();
            boolean installed = info != null && modelService.hasModelContent(info);
            boolean deleting = modelService.isDeletingModel(info);
            boolean anyOperationActive = downloading || modelService.isDeleting();
            return new LlmModelCardState(info, installed, anyOperationActive, activeDownload, paused, cancelling, deleting);
        }

        private boolean sameModelName(LlmModelInfo info, String modelName) {
            return info != null && info.name != null && modelName != null && info.name.equalsIgnoreCase(modelName);
        }

        private void deleteModel(ModuleSettingsContext context, LlmModelInfo info) {
            if (info == null || modelService.isDownloading() || modelService.isDeleting()) return;
            String deletedModelName = info.name == null ? "" : info.name;
            Component displayName = Component.literal(info.getDisplayName());
            modelService.deleteModelAsync(info, deleted -> runOnClient(() -> {
                if (deleted) {
                    context.showStatus(llm("message.deleted", displayName), 3000);
                    if (!deletedModelName.isBlank() && deletedModelName.equalsIgnoreCase(selectedModelName.get())) {
                        selectedModelName.set(currentModelName());
                    }
                } else {
                    context.showStatus(llm("message.delete_failed"), 3000);
                }
                refreshSettingsScreen();
            }));
        }

        private Component downloadStatusText() {
            LlmModelService.DownloadSnapshot snapshot = modelService.downloadSnapshot();
            if (snapshot.running()) {
                Component label = snapshot.cancelling()
                        ? llm("status.cancelling")
                        : snapshot.paused()
                        ? llm("status.paused")
                        : llm("status.downloading");
                return llm("download.progress", label, snapshot.percent());
            }
            if (!snapshot.errorMessage().isBlank()) {
                return localizedDownloadMessage(snapshot.errorMessage());
            }
            return llm("status.idle");
        }

        private Component localizedDownloadMessage(String message) {
            if (message == null || message.isBlank()) {
                return llm("status.download_failed");
            }
            if (message.startsWith("tianshu.")) {
                return Component.translatable(message);
            }
            return Component.literal(message);
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

        private Component filterLabel(String value) {
            return switch (value == null ? ALL : value) {
                case DOWNLOADED -> common("downloaded");
                case NOT_DOWNLOADED -> common("not_downloaded");
                case ALL -> llm("filter.all");
                default -> Component.literal(value);
            };
        }

        private List<String> seriesFilterValues() {
            Set<String> series = new LinkedHashSet<>();
            modelService.allModels().stream()
                    .map(this::modelSeries)
                    .filter(value -> !value.isBlank())
                    .sorted(Comparator.comparing(String::toLowerCase, String.CASE_INSENSITIVE_ORDER))
                    .forEach(series::add);
            List<String> values = new ArrayList<>(series.size() + 1);
            values.add(ALL);
            values.addAll(series);
            return values;
        }

        private boolean matchesSeriesFilter(LlmModelInfo info) {
            String selectedSeries = seriesFilter.get();
            return selectedSeries == null || selectedSeries.isBlank() || ALL.equals(selectedSeries) || selectedSeries.equalsIgnoreCase(modelSeries(info));
        }

        private boolean matchesDownloadStateFilter(LlmModelInfo info) {
            return switch (downloadStateFilter.get()) {
                case DOWNLOADED -> modelService.hasModelContent(info);
                case NOT_DOWNLOADED -> !modelService.hasModelContent(info);
                default -> true;
            };
        }

        private Component modelSeriesLabel(LlmModelInfo info) {
            String series = modelSeries(info);
            return series.isBlank() ? common("unknown") : Component.literal(series);
        }

        private String modelSeries(LlmModelInfo info) {
            if (info == null) return "";
            String explicit = info.getSeries();
            if (!explicit.isBlank()) return explicit;
            String source = firstNonBlank(info.getDisplayName(), info.name, info.repoId);
            if (source.isBlank()) return "";
            String normalized = source.replace('\\', '/');
            int slash = normalized.indexOf('/');
            if (slash >= 0) {
                normalized = normalized.substring(slash + 1);
            }
            normalized = normalized.trim();
            int separator = firstSeparatorIndex(normalized);
            String series = separator > 0 ? normalized.substring(0, separator) : normalized;
            return series.isBlank() ? "" : series;
        }

        private Component modelDownloadSizeLabel(LlmModelInfo info) {
            if (info == null) return common("unknown");
            long actualBytes = modelService.modelSizeBytes(info);
            if (actualBytes > 0L) return formatBytes(actualBytes);
            long estimatedBytes = estimatedModelSizeBytes(info);
            return estimatedBytes > 0L ? llm("size.estimated", formatBytes(estimatedBytes)) : common("unknown");
        }

        private Component estimatedVramLabel(LlmModelInfo info) {
            long bytes = estimatedVramBytes(info);
            return bytes > 0L ? llm("size.estimated", formatBytes(bytes)) : common("unknown");
        }

        private Component modelDescriptionLabel(LlmModelInfo info) {
            if (info == null) return null;
            String key = info.getDescriptionKey();
            if (!key.isBlank()) {
                return Component.translatable(key);
            }
            String description = info.getDescription();
            return description.isBlank() ? null : Component.literal(description);
        }

        private Component thinkingCapabilityLabel(LlmModelInfo info) {
            if (info == null || info.getThinkingCapability().isBlank()) {
                return common("unknown");
            }
            return llm("thinking_capability." + info.getThinkingCapability());
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

        private long estimatedModelSizeBytes(LlmModelInfo info) {
            if (info == null) return 0L;
            long catalogSize = info.getDownloadSizeBytes();
            if (catalogSize > 0L) return catalogSize;
            double billionParameters = modelParameterBillions(info);
            if (billionParameters <= 0D) return 0L;
            return Math.round(billionParameters * 1_000_000_000D * bytesPerParameter(info)) + 256L * 1024L * 1024L;
        }

        private long estimatedVramBytes(LlmModelInfo info) {
            if (info == null) return 0L;
            long catalogEstimate = info.getEstimatedVramBytes();
            if (catalogEstimate > 0L) return catalogEstimate;
            long modelBytes = Math.max(modelService.modelSizeBytes(info), estimatedModelSizeBytes(info));
            if (modelBytes <= 0L) return 0L;
            return Math.round(modelBytes * 1.15D) + 512L * 1024L * 1024L;
        }

        private double modelParameterBillions(LlmModelInfo info) {
            for (String source : List.of(info.getDisplayName(), info.name == null ? "" : info.name, info.getModelFile(), info.repoId == null ? "" : info.repoId)) {
                Matcher matcher = PARAMETER_SCALE_PATTERN.matcher(source == null ? "" : source);
                if (matcher.find()) {
                    try {
                        return Double.parseDouble(matcher.group(1));
                    } catch (NumberFormatException ignored) {
                        return 0D;
                    }
                }
            }
            return 0D;
        }

        private double bytesPerParameter(LlmModelInfo info) {
            String source = (firstNonBlank(info == null ? "" : info.getModelFile(), info == null ? "" : info.name) + " " + (info == null ? "" : info.repoId)).toLowerCase(Locale.ROOT);
            if (source.contains("q2")) return 0.32D;
            if (source.contains("q3")) return 0.42D;
            if (source.contains("q4")) return 0.58D;
            if (source.contains("q5")) return 0.70D;
            if (source.contains("q6")) return 0.82D;
            if (source.contains("q8") || source.contains("int8")) return 1.05D;
            return source.contains("gguf") ? 0.58D : 0.65D;
        }

        private String firstNonBlank(String... values) {
            if (values == null) return "";
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return "";
        }

        private int firstSeparatorIndex(String value) {
            int result = -1;
            for (char separator : new char[] {' ', '-', '_', ':'}) {
                int index = value.indexOf(separator);
                if (index > 0 && (result < 0 || index < result)) {
                    result = index;
                }
            }
            return result;
        }

        private Component formatBytes(long bytes) {
            if (bytes >= 1024L * 1024 * 1024) {
                return Component.literal(String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024)));
            }
            return Component.literal(String.format("%.0f MB", bytes / (1024.0 * 1024)));
        }
    }

    private record LoadCompatibility(boolean supported, Component message) {
        private static LoadCompatibility ok() {
            return new LoadCompatibility(true, Component.empty());
        }

        private static LoadCompatibility unsupported(Component message) {
            return new LoadCompatibility(false, message == null ? Component.empty() : message);
        }
    }

    private record LlmModelCardState(LlmModelInfo info, boolean installed, boolean anyOperationActive, boolean activeDownload, boolean paused, boolean cancelling, boolean deleting) {
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
            return info != null && installed && !anyOperationActive && !deleting;
        }

        private Component statusLabel() {
            if (activeDownload) {
                if (cancelling) {
                    return llm("status.cancelling");
                }
                return paused ? llm("status.paused") : llm("status.downloading");
            }
            return common(installed ? "downloaded" : "not_downloaded");
        }
    }
}
