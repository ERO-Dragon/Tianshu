package com.rheinmetal.tianshu.client.settings.module.llm;

import com.rheinmetal.tianshu.client.api.settings.ActionTemplate;
import com.rheinmetal.tianshu.client.api.settings.ItemActionTemplate;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.api.settings.OptionTemplate;
import com.rheinmetal.tianshu.client.api.settings.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.api.settings.SettingsListCard;
import com.rheinmetal.tianshu.client.api.settings.StatusTemplate;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerHost;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.host.ClientScheduler;
import com.rheinmetal.tianshu.client.host.ClientUiHost;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveTransaction;
import com.rheinmetal.tianshu.client.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.llm.LlmModelService;
import com.rheinmetal.tianshu.function.llm.LlmModuleService;
import com.rheinmetal.tianshu.function.llm.account.LlmAccountSnapshot;
import com.rheinmetal.tianshu.function.llm.catalog.LlmModelCatalogKeySource;
import com.rheinmetal.tianshu.function.llm.catalog.LlmModelCatalogResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmControlResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.function.llm.settings.LlmBuiltinProviders;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmContextTiers;
import com.rheinmetal.tianshu.function.llm.settings.LlmModelEntry;
import com.rheinmetal.tianshu.function.llm.settings.LlmModelSpec;
import com.rheinmetal.tianshu.function.llm.usage.LlmPricing;
import com.rheinmetal.tianshu.function.llm.usage.LlmUsageCounter;
import com.rheinmetal.tianshu.function.llm.usage.LlmUsageSnapshot;
import com.rheinmetal.tianshu.model.LlmModelInfo;
import com.rheinmetal.tianshu.model.ModelAvailabilitySnapshot;
import com.rheinmetal.tianshu.model.ModelDownloadProgress;

import com.rheinmetal.tianshu.client.api.text.UiText;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlmSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static final String MODULE_ID = "module.llm";
    private static final String ALL = "all";
    private static final String DOWNLOADED = "downloaded";
    private static final String NOT_DOWNLOADED = "not_downloaded";
    private static final long DOWNLOAD_REFRESH_INTERVAL_MILLIS = 250L;
    /** How long an editor verdict stays on screen; long enough to read a rejection. */
    private static final long EDITOR_STATUS_MILLIS = 4_000L;
    /** Prefix of the ids handed to models the player adds; the storage layer accepts it as an id. */
    private static final String NEW_MODEL_ID_PREFIX = "model-";
    private static final Pattern PARAMETER_SCALE_PATTERN = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*b\\b");
    private static final UiText TITLE = llm("title");
    private static final UiText DESCRIPTION = llm("description");

    private static UiText llm(String key, Object... args) {
        return UiText.key("tianshu.gui.llm." + key, args);
    }

    private static UiText common(String key, Object... args) {
        return UiText.key("tianshu.gui.common." + key, args);
    }

    /** Blank-safe text of a field; used by the page and by the two form buffers it drives. */
    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private final TianshuCoreManager coreManager;
    private final LlmSettingsAccess config;
    private final ClientScheduler scheduler;
    private final ClientUiHost uiHost;
    /** The window layer that shows a settings layer; the page owns what that layer displays. */
    private final SettingsLayerHost layerHost;

    public LlmSettingsRegistrySource(TianshuCoreManager coreManager, LlmSettingsAccess config,
                                     ClientScheduler scheduler, ClientUiHost uiHost,
                                     SettingsLayerHost layerHost) {
        this.coreManager = coreManager;
        this.config = config;
        this.scheduler = scheduler;
        this.uiHost = uiHost;
        this.layerHost = Objects.requireNonNull(layerHost, "layerHost");
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        if (registry == null || context == null || coreManager == null || config == null) {
            return;
        }
        register(registry, context,
                coreManager.requireService(LlmModuleService.class),
                coreManager.requireService(LlmModelService.class));
    }

    /**
     * The contribution itself, with the two services the page drives already resolved. Keeping the
     * lookup out of this method lets the page be built without a game runtime, which is what the
     * editor/save contract is tested against.
     */
    void register(TianshuSettingsRegistry registry, ModuleSettingsContext context,
                  LlmModuleService moduleService, LlmModelService modelService) {
        LlmSettingsDraft draft = new LlmSettingsDraft(config, moduleService, modelService, scheduler, uiHost, layerHost);
        context.settingsSessions().registerOrReplace(draft);
        registry.registerCategory(ModuleSettingsCategory.builder(MODULE_ID)
                .title(TITLE)
                .description(DESCRIPTION)
                .order(25)
                .panel((panel, panelContext) -> buildPanel(panel, panelContext, draft))
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context, LlmSettingsDraft draft) {
        panel.enable("llm.enabled", llm("enabled"), draft.enabled);
        buildLaneSelector(panel, context);
        panel.columns("llm.panel.layout", 0.45D, 0.55D, columns -> columns
                .column(0, left -> buildProviderColumn(left, context, draft))
                .column(1, right -> buildStatsColumn(right, draft)));
        buildRuntimeSections(panel, context, draft);
    }

    /**
     * Lane selector at the top of the page. The framework has no segmented control, so the two lanes
     * are an action row: the cloud tab is the active, styled one and the sealed local tab is rendered
     * with its enabled predicate switched off, which greys it out and makes it unclickable.
     */
    private void buildLaneSelector(ModuleSettingsPanel panel, ModuleSettingsContext context) {
        panel.actions("llm.lane.selector", llm("section.lane"), actions -> actions
                .button("llm.lane.cloud", llm("lane.cloud"), SettingsButtonStyle.PRIMARY,
                        () -> context.showStatus(llm("lane.cloud_active"), 2000), () -> true)
                .button("llm.lane.local", llm("lane.local"),
                        () -> { }, () -> false));
    }

    /**
     * Left column: the configured providers, the add button, then the editor.
     *
     * <p>A card carries what identifies and activates an entry — the name, the "自定义" badge of an
     * endpoint this build does not ship, the activation dot and the model the provider currently uses —
     * and nothing more. Endpoint data lives in the editor, so no card grows a full-width paragraph.
     *
     * <p>Those are the only three groups of the column, in that order: cards, add button, editor. The
     * add button keeps its place while the editor is open and only the button itself is disabled, so
     * opening a form never moves what is already on screen.
     *
     * <p>The editor is the form and one read-only line: which model the provider uses, and one button
     * that opens the picker. It is the whole catalog contribution of this page — a window of its own,
     * layered over the settings screen while it is open, so this column stays exactly as it is and
     * closing the window puts the player back where they were.
     */
    private void buildProviderColumn(ModuleSettingsPanel panel, ModuleSettingsContext context, LlmSettingsDraft draft) {
        panel.<LlmModelEntry>list("llm.providers", llm("section.providers"), list -> list
                        .items(draft::modelEntries)
                        .card(draft::providerCard)
                        .itemActions(draft::buildProviderActions)
                        .emptyText(llm("models.empty")))
                .actions("llm.providers.add", llm("section.provider_add"),
                        () -> true, () -> true,
                        actions -> actions.button("llm.providers.add.button", llm("action.add_provider"),
                                SettingsButtonStyle.PRIMARY, draft::startNewProvider, () -> !draft.editing()))
                .compound("llm.providers.editor", llm("section.editor"),
                        () -> true, draft::editing,
                        draft::buildEditorOptions,
                        actions -> draft.buildEditorActions(context, actions),
                        draft::buildEditorStatus);
    }

    /**
     * Right column: usage statistics. Token counts come from the local ledger. Money is split by its
     * source and the two halves are never merged into one figure:
     * <ul>
     *   <li>今日花费 / 累计花费 are the ledger's sums for the local natural day and for its whole
     *       lifetime, tagged as a local figure.</li>
     *   <li>剩余余额 is whatever the provider's account API answered, tagged as an API figure; an
     *       unwired or failed query is reported as such and never rendered as zero.</li>
     * </ul>
     */
    private void buildStatsColumn(ModuleSettingsPanel panel, LlmSettingsDraft draft) {
        panel.status("llm.usage.modules", llm("section.usage_modules"), draft::buildModuleUsageStatus)
                .status("llm.usage.overall", llm("section.usage_overall"), draft::buildOverallUsageStatus)
                .status("llm.usage.spend", llm("section.usage_spend"), draft::buildSpendStatus)
                .status("llm.usage.balance", llm("section.usage_balance"), draft::buildBalanceStatus)
                .actions("llm.usage.actions", llm("section.usage_actions"), actions -> actions
                        .button("llm.usage.refresh", llm("action.refresh_usage"), draft::refreshUsage));
    }

    /** Full-width sections below the two columns; they are not part of the model/statistics pair. */
    private void buildRuntimeSections(ModuleSettingsPanel panel, ModuleSettingsContext context, LlmSettingsDraft draft) {
        panel.compound("llm.load", llm("section.load_settings"), draft.enabled::get,
                        draft::buildLoadOptions,
                        actions -> actions
                                .button("llm.load.start", llm("action.load"), SettingsButtonStyle.PRIMARY, () -> draft.startLoad(context), draft::canLoad)
                                .button("llm.load.stop", llm("action.unload"), SettingsButtonStyle.DANGER, () -> draft.stopLoad(context), draft::canUnload),
                        status -> {
                            draft.buildModelInfo(status);
                            draft.buildLoadStatus(status);
                        })
                .compound("llm.embedding", llm("section.embedding"), draft.enabled::get,
                        options -> { },
                        actions -> actions.button(
                                "llm.embedding.download",
                                llm("action.download_embedding"),
                                SettingsButtonStyle.PRIMARY,
                                () -> draft.downloadEmbeddingModel(context),
                                draft::canDownloadEmbeddingModel
                        ),
                        status -> {
                            status.row("llm.embedding.model", llm("row.embedding_model"), draft::embeddingModelNameStatus);
                            status.row("llm.embedding.state", llm("row.embedding_state"), draft::embeddingModelStateStatus);
                        })
                .<LlmModelInfo>catalog("llm.download.catalog", llm("section.download_models"), draft::buildDownloadFilters, list -> list
                        .items(draft::downloadableModels)
                        .card(draft::downloadModelCard)
                        .itemActions((model, actions) -> draft.buildDownloadItemActions(context, model, actions))
                        .emptyText(llm("download.empty")));
    }

    private static final class LlmSettingsDraft implements com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession {
        private final LlmSettingsAccess config;
        private final LlmModuleService moduleService;
        private final LlmModelService modelService;
        private final ClientScheduler scheduler;
        private final ClientUiHost uiHost;
        private final SettingsLayerHost layerHost;
        private final MutableSettingsValue<Boolean> enabled;
        private final MutableSettingsValue<String> selectedModelName;
        /** The whole model list is one draft value: add, edit and delete all rebuild it. */
        private final MutableSettingsValue<List<LlmModelEntry>> models;
        private final MutableSettingsValue<String> activeModelId;
        private final MutableSettingsValue<String> seriesFilter;
        private final MutableSettingsValue<String> downloadStateFilter;
        /**
         * Credentials typed into the editor, keyed by model id. They are written to the configuration
         * on save and are never read back into a widget, so a blank field keeps the stored key.
         */
        private final Map<String, String> pendingApiKeys = new LinkedHashMap<>();
        /** Credentials of deleted models, cleared on save so no orphan secret stays behind. */
        private final Set<String> retiredApiKeyIds = new LinkedHashSet<>();
        private final AtomicBoolean downloadRefreshQueued = new AtomicBoolean(false);
        private volatile ModelAvailabilitySnapshot availabilitySnapshot;
        private volatile long lastDownloadRefreshMillis;
        /**
         * True while a provider model-list request is in flight. It belongs to the picker: the layer
         * disables its fetch action and shows {@code catalog.fetching} until the answer arrives.
         */
        private volatile boolean catalogFetching;
        private ModelEditorBuffer modelEditor;
        /** The open picker session, or {@code null} while no layer shows the catalog. */
        private ModelPickerSession modelPicker;

        private LlmSettingsDraft(LlmSettingsAccess config, LlmModuleService moduleService,
                                 LlmModelService modelService, ClientScheduler scheduler, ClientUiHost uiHost,
                                 SettingsLayerHost layerHost) {
            this.config = config;
            this.scheduler = scheduler;
            this.uiHost = uiHost;
            this.moduleService = moduleService;
            this.modelService = modelService;
            this.layerHost = layerHost;
            this.availabilitySnapshot = modelService.modelAvailability();
            this.enabled = new MutableSettingsValue<>(config::isLlmEnabled, config::setLlmEnabled);
            this.selectedModelName = new MutableSettingsValue<>(this::currentModelName, ignored -> {}, Objects::nonNull);
            this.models = new MutableSettingsValue<>(config::getLlmModels, config::setLlmModels,
                    value -> value != null && !value.isEmpty());
            this.activeModelId = new MutableSettingsValue<>(config::getLlmActiveModelId, config::setLlmActiveModelId, Objects::nonNull);
            this.seriesFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            this.downloadStateFilter = new MutableSettingsValue<>(() -> ALL, ignored -> {});
            modelService.refreshModelAvailabilityAsync(() -> runOnClient(() -> {
                availabilitySnapshot = modelService.modelAvailability();
                refreshSettingsScreen();
            }));
            // The balance is a provider answer, so the page asks for it once when it is built; the answer
            // repaints the row when it lands instead of leaving the player with a stale figure.
            moduleService.refreshAccount(this::requestUsageRefresh);
        }

        // ---------------------------------------------------------------- model cards

        private List<LlmModelEntry> modelEntries() {
            return models.get();
        }

        /**
         * Active entry of the draft. The list is never empty, but a defensive {@code null} keeps the
         * card renderer total while an edit is half applied.
         */
        private LlmModelEntry activeEntry() {
            List<LlmModelEntry> entries = models.get();
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            String id = activeModelId.get();
            if (id != null) {
                for (LlmModelEntry entry : entries) {
                    if (entry.id().equals(id)) {
                        return entry;
                    }
                }
            }
            return entries.get(0);
        }

        /**
         * One card per configured provider: the name, the "自定义" badge of an endpoint this build does
         * not ship, the activation dot as the status text, and one detail line naming the model the
         * provider currently uses. The model line is the only endpoint data a card shows: a provider can
         * serve several models, so which one is active is the fact the player cannot see anywhere else
         * without opening the form.
         */
        private SettingsListCard providerCard(LlmModelEntry entry) {
            if (entry == null) {
                return SettingsListCard.text(UiText.literal(""));
            }
            boolean active = entry.id().equals(activeModelId.get());
            return new SettingsListCard(
                    providerLabel(entry),
                    active ? llm("models.card.enabled") : llm("models.card.disabled"),
                    List.of(llm("models.card.active_model", UiText.literal(entry.modelId()))),
                    providerBadges(entry)
            );
        }

        /**
         * A built-in provider is named by language key, so the stored entry keeps an untranslated
         * identity and the card still reads as the provider's real name in either language.
         */
        private UiText providerLabel(LlmModelEntry entry) {
            LlmBuiltinProviders.Provider provider = LlmBuiltinProviders.find(entry.providerId());
            return provider == null ? UiText.literal(entry.displayName()) : UiText.key(provider.labelKey());
        }

        private List<UiText> providerBadges(LlmModelEntry entry) {
            return LlmBuiltinProviders.isBuiltin(entry.providerId())
                    ? List.of()
                    : List.of(llm("models.badge.custom"));
        }

        /**
         * Card actions in the reference order: activate first, then edit, then delete. The framework does
         * not render an action whose predicate fails, which is exactly what is wanted here — the active
         * card shows its state instead of an 启用 button, and the last remaining provider has no 删除.
         */
        private void buildProviderActions(LlmModelEntry entry, ItemActionTemplate<LlmModelEntry> actions) {
            actions.button("llm.provider.activate", llm("action.activate"), SettingsButtonStyle.PRIMARY,
                            this::activate, model -> model != null && !model.id().equals(activeModelId.get()))
                    .button("llm.provider.edit", llm("action.edit"), this::startEdit,
                            model -> model != null && modelEditor == null)
                    .button("llm.provider.delete", llm("action.delete_model"), SettingsButtonStyle.DANGER,
                            this::retireModel, model -> model != null && models.get().size() > 1);
        }

        /** Activation is a draft value, so it is persisted by the same save as everything else. */
        private void activate(LlmModelEntry entry) {
            if (entry == null || entry.id().equals(activeModelId.get())) {
                return;
            }
            activeModelId.set(entry.id());
            refreshSettingsScreen();
        }

        /**
         * Deleting the last model would leave the profile without any endpoint, so it is refused both
         * by the button predicate and here. The model's credential is retired on save, not now, so
         * cancelling the page leaves the stored key alone.
         */
        private void retireModel(LlmModelEntry entry) {
            List<LlmModelEntry> current = models.get();
            if (entry == null || current.size() <= 1) {
                return;
            }
            List<LlmModelEntry> next = current.stream()
                    .filter(candidate -> !candidate.id().equals(entry.id()))
                    .toList();
            if (next.isEmpty()) {
                return;
            }
            models.set(next);
            pendingApiKeys.remove(entry.id());
            retiredApiKeyIds.add(entry.id());
            if (entry.id().equals(activeModelId.get())) {
                activeModelId.set(next.get(0).id());
            }
            if (modelEditor != null && entry.id().equals(modelEditor.entryId())) {
                // The form (and the picker hanging off it, if any) belongs to the provider that is gone.
                modelEditor = null;
                modelPicker = null;
            }
            refreshSettingsScreen();
        }

        /**
         * Opens the form for an existing entry. The mode follows the entry: a configuration that is
         * exactly a shipped provider profile is edited as 内置提供商, anything else (a proxy, another
         * model id, a hand-set window) as 自定义 API — so pressing 保存 can never silently rewrite a
         * difference the player cannot see in the form.
         */
        private void startEdit(LlmModelEntry entry) {
            if (entry == null || modelEditor != null) {
                return;
            }
            LlmBuiltinProviders.Provider provider = LlmBuiltinProviders.find(entry.providerId());
            EditorMode mode = provider != null && provider.isProfileOf(entry)
                    ? EditorMode.BUILTIN
                    : EditorMode.CUSTOM;
            modelEditor = new ModelEditorBuffer(entry.id(), mode, entry.providerId(), entry.displayName(),
                    entry.baseUrl(), entry.models(), entry.activeModelId(), "");
            refreshSettingsScreen();
        }

        /** A new entry starts from the shipped default provider; 自定义 API is one switch away. */
        private void startNewProvider() {
            if (modelEditor != null) {
                return;
            }
            LlmBuiltinProviders.Provider provider = LlmBuiltinProviders.defaultProvider();
            modelEditor = new ModelEditorBuffer(null, EditorMode.BUILTIN, provider.id(), provider.id(),
                    provider.baseUrl(), provider.defaultCatalog(), provider.defaultModelId(), "");
            refreshSettingsScreen();
        }

        /** Whether a provider form is open at all; the add button is disabled while one is. */
        private boolean editing() {
            return modelEditor != null;
        }

        private void cancelEditing() {
            modelPicker = null;
            if (modelEditor == null) {
                return;
            }
            modelEditor = null;
            refreshSettingsScreen();
        }

        /**
         * The form's action row. 选择模型 is the only catalog control left in the editor: which models the
         * provider serves, and which of them it uses, is a decision of its own and lives one level down.
         */
        private void buildEditorActions(ModuleSettingsContext context, ActionTemplate actions) {
            actions.button("llm.editor.choose_model", llm("action.choose_model"), this::openModelPicker)
                    .button("llm.editor.cancel", llm("action.cancel_edit"), this::cancelEditing)
                    .button("llm.editor.save", llm("action.save_provider"), SettingsButtonStyle.PRIMARY,
                            () -> applyEditor(context));
        }

        /**
         * The one catalog fact the editor still states: the model this provider would use once the form is
         * applied. It is read-only on purpose — a select here would be a second way to pick a model, and
         * two ways to make one decision is what made the form unreadable.
         */
        private void buildEditorStatus(StatusTemplate status) {
            ModelEditorBuffer editor = modelEditor;
            if (editor == null) {
                return;
            }
            status.row("llm.editor.active_model", llm("row.active_model"), () -> activeModelLabel(editor));
        }

        private UiText activeModelLabel(ModelEditorBuffer editor) {
            String modelId = editor.activeModelId();
            return modelId.isEmpty() ? common("not_selected") : UiText.literal(modelId);
        }

        /**
         * The add/edit form: what identifies the endpoint, and nothing about its models. Which models the
         * provider serves is the picker's business, so no model id, context window or catalog control is
         * typed here — a provider has several models and exactly one of them is in use, which a single
         * field cannot express.
         *
         * <p>Both modes share this buffer and the commit path, so a value is validated once and written
         * by one code path; the mode switch only decides which fields exist. The framework has no
         * segmented control, so the mode is a two-value select, which the renderer draws as one toggle
         * button showing the current mode.
         */
        private void buildEditorOptions(OptionTemplate options) {
            ModelEditorBuffer editor = modelEditor;
            if (editor == null) {
                return;
            }
            UiText apiKeyLabel = apiKeyConfigured(editor.entryId())
                    ? llm("option.api_key_configured")
                    : llm("option.api_key");
            options.select("llm.editor.mode", llm("option.editor_mode"), List.of(EditorMode.values()),
                            editor::mode, EditorMode::label, editor::selectMode)
                    .select("llm.editor.provider", llm("option.provider"), LlmBuiltinProviders.providerIds(),
                            editor::providerId, this::providerOptionLabel, editor::selectProvider,
                            () -> true, editor::builtinMode)
                    .text("llm.editor.name", llm("option.model_display_name"),
                            editor::displayName, editor::setDisplayName, () -> true, editor::customMode)
                    .text("llm.editor.base_url", llm("option.remote_base_url"),
                            editor::baseUrl, editor::setBaseUrl, () -> true, editor::customMode)
                    // Last and write-only. The widget is always empty: only the fact that a credential is
                    // stored is read back, so a blank field keeps the key and a typed value replaces it.
                    .text("llm.editor.api_key", apiKeyLabel, editor::apiKey, editor::setApiKey);
        }

        private UiText providerOptionLabel(String providerId) {
            LlmBuiltinProviders.Provider provider = LlmBuiltinProviders.find(providerId);
            return provider == null ? common("dash") : UiText.key(provider.labelKey());
        }

        /** Whether the entry behind the form already holds a credential; the value itself is never read. */
        private boolean apiKeyConfigured(String entryId) {
            if (entryId == null || entryId.isBlank()) {
                return false;
            }
            if (pendingApiKeys.containsKey(entryId)) {
                return true;
            }
            return !retiredApiKeyIds.contains(entryId) && storedApiKeyPresent(entryId);
        }

        /**
         * The form's own 保存: commit what the buffer would store, then report it. Every field is
         * validated here instead of on each keystroke, so a half-typed value never reaches the stored
         * list. 内置提供商 mode ignores the endpoint text entirely: the shipped profile is the definition
         * of that mode, so its fields are not even shown and cannot contradict it.
         */
        private void applyEditor(ModuleSettingsContext context) {
            if (modelEditor == null) {
                return;
            }
            EditorPlan plan = editorPlan();
            if (!plan.applicable()) {
                context.showStatus(plan.failure(), EDITOR_STATUS_MILLIS);
                return;
            }
            commitEditor(plan);
            context.showStatus(llm("message.provider_saved", providerLabel(plan.entry())), EDITOR_STATUS_MILLIS);
            refreshSettingsScreen();
        }

        /**
         * The open form expressed as the change it would make, or why it cannot be applied.
         *
         * <p>Pure on purpose. {@link #validate()} is polled while the page renders (the 加载 button asks
         * it whether loading is possible), so it may not move a form into the draft; therefore the
         * judgment and the write are separate: this method only describes the change, {@link #commitEditor}
         * performs it. That is what lets the page-level 保存 finish an open edit instead of refusing it,
         * and it keeps both save paths — the form's own 保存 and the page's 保存 — applying the same thing.
         *
         * <p>The catalog is validated in both modes, because a provider may serve more models than the
         * one its profile documents.
         *
         * @return {@code null} while no form is open
         */
        private EditorPlan editorPlan() {
            ModelEditorBuffer editor = modelEditor;
            if (editor == null) {
                return null;
            }
            String displayName;
            String providerId;
            String baseUrl;
            if (editor.mode() == EditorMode.BUILTIN) {
                LlmBuiltinProviders.Provider provider = LlmBuiltinProviders.find(editor.providerId());
                if (provider == null) {
                    return EditorPlan.rejected(llm("validation.missing_provider"));
                }
                displayName = provider.id();
                providerId = provider.id();
                baseUrl = provider.baseUrl();
            } else {
                displayName = trimmed(editor.displayName());
                baseUrl = trimmed(editor.baseUrl());
                if (displayName.isEmpty()) {
                    return EditorPlan.rejected(llm("validation.missing_display_name"));
                }
                if (!isHttpUrl(baseUrl)) {
                    return EditorPlan.rejected(llm("validation.invalid_base_url"));
                }
                // A new custom endpoint is tagged as one so the card carries its badge; an existing entry
                // keeps the identity it is stored with, because the form does not show that field.
                providerId = editor.entryId() == null ? LlmModelEntry.CUSTOM_PROVIDER_ID : editor.providerId();
            }
            CatalogPlan catalog = catalogPlan(editor);
            if (!catalog.applicable()) {
                return EditorPlan.rejected(catalog.failure());
            }
            return EditorPlan.applied(new LlmModelEntry(
                    editor.entryId() == null ? nextModelId() : editor.entryId(),
                    displayName,
                    providerId,
                    baseUrl,
                    catalog.specs(),
                    editor.activeModelId()
            ), trimmed(editor.apiKey()));
        }

        /**
         * Writes a plan into the draft and closes the form. The only place an editor buffer becomes
         * draft state, so the two save paths cannot apply it differently.
         */
        private void commitEditor(EditorPlan plan) {
            LlmModelEntry entry = plan.entry();
            List<LlmModelEntry> next = new ArrayList<>(models.get());
            boolean replaced = false;
            for (int index = 0; index < next.size(); index++) {
                if (next.get(index).id().equals(entry.id())) {
                    next.set(index, entry);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                next.add(entry);
            }
            models.set(List.copyOf(next));
            retiredApiKeyIds.remove(entry.id());
            if (!plan.apiKey().isEmpty()) {
                pendingApiKeys.put(entry.id(), plan.apiKey());
            }
            // A closed form has no catalog to pick from, so any window on it ends with the form.
            modelEditor = null;
            modelPicker = null;
        }

        /**
         * The edited catalog in the shape the storage layer wants.
         *
         * <p>The declared window is not part of the form any more: a row carries the window it was
         * created with (the shipped profile's, or the one already stored), so only the identity of the
         * model is still the player's input here.
         */
        private CatalogPlan catalogPlan(ModelEditorBuffer editor) {
            if (editor.rowCount() == 0) {
                return CatalogPlan.rejected(llm("validation.catalog_empty"));
            }
            List<LlmModelSpec> specs = new ArrayList<>(editor.rowCount());
            for (int index = 0; index < editor.rowCount(); index++) {
                String modelId = trimmed(editor.modelIdAt(index));
                if (modelId.isEmpty()) {
                    return CatalogPlan.rejected(llm("validation.missing_model"));
                }
                if (specs.stream().anyMatch(spec -> spec.modelId().equalsIgnoreCase(modelId))) {
                    return CatalogPlan.rejected(llm("validation.duplicate_model", modelId));
                }
                specs.add(new LlmModelSpec(modelId, trimmed(editor.displayNameAt(index)),
                        editor.windowTokensAt(index)));
            }
            return CatalogPlan.applied(List.copyOf(specs));
        }

        /** Id for a model the player just added; the storage layer rejects an id that is already used. */
        private String nextModelId() {
            Set<String> used = new LinkedHashSet<>();
            for (LlmModelEntry entry : models.get()) {
                used.add(entry.id());
            }
            int candidate = 1;
            while (used.contains(NEW_MODEL_ID_PREFIX + candidate)) {
                candidate++;
            }
            return NEW_MODEL_ID_PREFIX + candidate;
        }

        /** Accepted window range of one model entry, expressed by the tier policy and the storage bound. */
        private boolean validContextWindow(int tokens) {
            return tokens <= LlmConfiguration.MAX_LLM_CONTEXT_WINDOW_TOKENS
                    && LlmContextTiers.supportsTier(LlmContextTiers.minimumTier(), tokens);
        }

        private UiText contextWindowRangeMessage() {
            return llm("validation.context_window_out_of_range",
                    LlmUsageValueFormat.tokens(LlmContextTiers.minimumTier()),
                    LlmUsageValueFormat.tokens(LlmConfiguration.MAX_LLM_CONTEXT_WINDOW_TOKENS));
        }

        private boolean storedApiKeyPresent(String modelId) {
            String stored = config.getLlmApiKey(modelId);
            return stored != null && !stored.isBlank();
        }

        private boolean hasApiKey(LlmModelEntry entry) {
            if (entry == null) {
                return false;
            }
            if (pendingApiKeys.containsKey(entry.id())) {
                return true;
            }
            return !retiredApiKeyIds.contains(entry.id()) && storedApiKeyPresent(entry.id());
        }

        /**
         * Whether one provider will have a credential once the page save ran: what the draft holds, plus
         * — for that provider only — the key typed into the form that the same click is about to apply.
         * Without the second half, a key the player just typed would be reported as missing simply
         * because the checks run before the form is applied.
         */
        private boolean hasApiKey(LlmModelEntry entry, EditorPlan plan) {
            if (entry == null) {
                return false;
            }
            if (plan != null && plan.applicable()
                    && plan.entry().id().equals(entry.id())
                    && !plan.apiKey().isEmpty()) {
                return true;
            }
            return hasApiKey(entry);
        }

        // ---------------------------------------------------------------- model picker

        /**
         * Opens the picker layer on the form's catalog.
         *
         * <p>The picker works on a copy: 确认 is the only path back into the form, so closing it with 取消
         * changes neither the model list nor the model in use. What is handed over is a description —
         * {@link LlmModelPickerLayers} — so this page never sees a window; the host decides how the
         * description is drawn, and every element of it reads this session on demand.
         */
        private void openModelPicker() {
            ModelEditorBuffer editor = modelEditor;
            if (editor == null || modelPicker != null) {
                return;
            }
            catalogFetching = false;
            modelPicker = new ModelPickerSession(
                    new ModelPickerBuffer(editor.rowCopies(), editor.activeModelId()), editor);
            layerHost.open(LlmModelPickerLayers.of(modelPicker));
        }

        /**
         * The picker window's state, backed by one {@link ModelPickerBuffer} copy of the form's catalog.
         *
         * <p>It is the seam the platform window draws through: every read returns current state and every
         * intent mutates the copy. Nothing here writes the form except {@link #confirm()}, and nothing
         * here writes the configuration at all — the confirmed model is draft state, and the page-level
         * save is what persists it.
         *
         * <p>The provider request goes out through the same seam the editor used before the picker
         * existed, so the credential priority and the per-state failure text stay in one place instead of
         * being re-implemented behind a window.
         */
        private final class ModelPickerSession implements LlmModelPickerSession {
            private final ModelPickerBuffer buffer;
            private final ModelEditorBuffer editor;
            private UiText status = UiText.literal("");

            private ModelPickerSession(ModelPickerBuffer buffer, ModelEditorBuffer editor) {
                this.buffer = buffer;
                this.editor = editor;
            }

            @Override
            public List<String> modelIds() {
                return buffer.modelIds();
            }

            @Override
            public String selectedModelId() {
                return buffer.hasSelection() ? buffer.selectedModelId() : "";
            }

            @Override
            public void select(String modelId) {
                buffer.select(modelId);
            }

            @Override
            public String search() {
                return buffer.search();
            }

            @Override
            public void search(String term) {
                buffer.setSearch(term);
            }

            @Override
            public String customModelId() {
                return buffer.customModelId();
            }

            @Override
            public void customModelId(String value) {
                buffer.setCustomModelId(value);
            }

            @Override
            public boolean canAddCustomModel() {
                return buffer.canAddCustom();
            }

            @Override
            public void addCustomModel() {
                buffer.addCustomModel(defaultWindowFor(editor.providerId()));
            }

            @Override
            public boolean fetching() {
                return catalogFetching;
            }

            @Override
            public void fetchModels() {
                if (catalogFetching) {
                    return;
                }
                catalogFetching = true;
                status = llm("catalog.fetching");
                CatalogCredential credential = catalogCredential(editor);
                moduleService.requestModelCatalog(trimmed(editor.baseUrl()), credential.value(),
                        credential.source(), result -> runOnClient(() -> onAnswer(result)));
            }

            @Override
            public UiText status() {
                return status;
            }

            @Override
            public void confirm() {
                editor.replaceCatalog(buffer.rows(), selectedModelId());
                close();
            }

            @Override
            public void cancel() {
                close();
            }

            /**
             * Applies one provider answer to this picker.
             *
             * <p>A window that was closed while the request was in flight is gone, and its answer is stale:
             * the identity check is the whole guard, so a late answer can never rewrite a form the player
             * already left.
             */
            private void onAnswer(LlmModelCatalogResult result) {
                catalogFetching = false;
                if (modelPicker != this) {
                    return;
                }
                if (result.success()) {
                    buffer.replaceWithFetchedModels(result.modelIds(), defaultWindowFor(editor.providerId()));
                    status = llm("catalog.fetched", result.modelIds().size());
                    return;
                }
                status = catalogMessage(result);
            }

            /** Ends this session, if it still is the open one; the window closes itself after that. */
            private void close() {
                if (modelPicker == this) {
                    modelPicker = null;
                }
            }
        }

        /** The context window a model the picker adds starts from: the shipped profile's, or the default. */
        private int defaultWindowFor(String providerId) {
            LlmBuiltinProviders.Provider provider = LlmBuiltinProviders.find(providerId);
            return provider == null
                    ? LlmConfiguration.DEFAULT_LLM_CONTEXT_WINDOW_TOKENS
                    : provider.contextWindowTokens();
        }

        /**
         * The credential one fetch may spend, in the order the player built it up:
         * <ol>
         *   <li>what the form holds right now — typed and not applied yet, which is why a fetch works
         *       before anything was saved;</li>
         *   <li>what was applied to the form but not saved, keyed by the provider id;</li>
         *   <li>what the stored configuration of that provider holds.</li>
         * </ol>
         *
         * <p>The origin travels with the value so a failed request can name it. A provider deleted in
         * this session spends nothing: its key is retired on save, not now.
         */
        private CatalogCredential catalogCredential(ModelEditorBuffer editor) {
            String typed = trimmed(editor.apiKey());
            if (!typed.isEmpty()) {
                return new CatalogCredential(typed, LlmModelCatalogKeySource.TYPED);
            }
            String entryId = editor.entryId();
            if (entryId == null || entryId.isBlank() || retiredApiKeyIds.contains(entryId)) {
                return new CatalogCredential("", LlmModelCatalogKeySource.NONE);
            }
            String pending = trimmed(pendingApiKeys.get(entryId));
            if (!pending.isEmpty()) {
                return new CatalogCredential(pending, LlmModelCatalogKeySource.PENDING);
            }
            String stored = trimmed(config.getLlmApiKey(entryId));
            return stored.isEmpty()
                    ? new CatalogCredential("", LlmModelCatalogKeySource.NONE)
                    : new CatalogCredential(stored, LlmModelCatalogKeySource.STORED);
        }


        /**
         * The player-facing text of a catalog failure.
         *
         * <p>The state owns the language key, so each cause reads as itself: a rejected credential, any
         * other HTTP status, an unreadable answer and a transport failure are four sentences. The states
         * whose sentence quotes the detail get it, which is where "HTTP 401" becomes visible as a refusal
         * instead of a vague "the request failed".
         */
        private UiText catalogMessage(LlmModelCatalogResult result) {
            String key = result.messageKey();
            if (key == null || key.isBlank()) {
                return llm("catalog.not_wired");
            }
            if (result.state().takesDetailArgument() && !result.detail().isBlank()) {
                return UiText.key(key, result.detail());
            }
            return UiText.key(key);
        }

        // ---------------------------------------------------------------- usage statistics

        private void buildModuleUsageStatus(StatusTemplate status) {
            LlmUsageSnapshot usage = moduleService.usageSnapshot();
            Map<String, LlmUsageCounter> byModule = usage.byModule();
            if (byModule.isEmpty()) {
                status.row("llm.usage.modules.empty", llm("row.usage_modules_empty"), () -> common("dash"));
                return;
            }
            for (Map.Entry<String, LlmUsageCounter> entry : byModule.entrySet()) {
                // One snapshot for the whole group, so the rows of one rebuild always agree.
                LlmUsageCounter counter = entry.getValue() == null ? LlmUsageCounter.zero() : entry.getValue();
                status.row("llm.usage.module." + entry.getKey(), LlmUsageValueFormat.sourceLabel(entry.getKey()),
                        () -> llm("row.module_tokens",
                                LlmUsageValueFormat.tokens(counter.inputTokens()),
                                LlmUsageValueFormat.tokens(counter.cacheHitInputTokens()),
                                LlmUsageValueFormat.tokens(counter.outputTokens())));
            }
        }

        /**
         * Global figures. The hit total is shown on purpose: it is the denominator the per-module hit
         * tokens are supposed to add up to, which makes the accounting rule checkable in game.
         */
        private void buildOverallUsageStatus(StatusTemplate status) {
            LlmUsageCounter total = moduleService.usageSnapshot().total();
            status.row("llm.usage.overall.ratio", llm("row.cache_hit_ratio"),
                            () -> LlmUsageValueFormat.ratio(total.hitRatio()))
                    .row("llm.usage.overall.input", llm("row.input_tokens"),
                            () -> LlmUsageValueFormat.tokens(total.inputTokens()))
                    .row("llm.usage.overall.hit", llm("row.cache_hit_tokens"),
                            () -> LlmUsageValueFormat.tokens(total.cacheHitInputTokens()))
                    .row("llm.usage.overall.output", llm("row.output_tokens"),
                            () -> LlmUsageValueFormat.tokens(total.outputTokens()))
                    .row("llm.usage.overall.requests", llm("row.requests"),
                            () -> LlmUsageValueFormat.count(total.requests()));
        }

        /**
         * 今日花费 and 累计花费 from the local ledger.
         *
         * <p>The {@code today} bucket is cut by the local natural day and is zeroed when the day rolls
         * over (see {@link com.rheinmetal.tianshu.function.llm.usage.LlmUsageLedger}), so no timezone,
         * provider calendar or extra bookkeeping is involved here: {@code todayCostMicros} of the same
         * snapshot is what the row shows.
         */
        private void buildSpendStatus(StatusTemplate status) {
            LlmUsageSnapshot usage = moduleService.usageSnapshot();
            LlmPricing pricing = moduleService.usagePricing();
            status.row("llm.usage.spend.today", llm("row.today_cost"),
                            () -> LlmUsageValueFormat.localEstimate(usage.todayCostMicros(pricing)))
                    .row("llm.usage.spend.total", llm("row.total_cost"),
                            () -> LlmUsageValueFormat.localEstimate(usage.totalCostMicros(pricing)));
        }

        /**
         * 剩余余额: what the provider's account API answered, with the currency it stated.
         *
         * <p>Only the provider can answer this, so the row reports the state of the query when there is no
         * figure — never a zero. A missing credential or an unusable endpoint is reported here without
         * even asking, because that is what the player has to fix.
         */
        private void buildBalanceStatus(StatusTemplate status) {
            status.row("llm.usage.balance.value", llm("row.balance"), this::balanceText);
        }

        private UiText balanceText() {
            LlmAccountSnapshot account = moduleService.accountSnapshot();
            if (trimmed(config.getLlmRemoteApiKey()).isEmpty()) {
                return llm("balance.missing_credential");
            }
            if (!isHttpUrl(trimmed(config.getLlmRemoteBaseUrl()))) {
                return llm("balance.missing_endpoint");
            }
            return LlmUsageValueFormat.providerAmount(account, account.balanceMicros());
        }

        /**
         * Asks the provider for fresh account figures and re-reads the ledger.
         *
         * <p>Non-blocking: the probe owns the threading, so a slow endpoint can only leave the rows as they
         * are. The answer repaints them when it lands instead of being polled, which is what turns the
         * balance row from 查询中… into a figure without a second click.
         */
        private void refreshUsage() {
            moduleService.refreshAccount(this::requestUsageRefresh);
            refreshSettingsScreen();
        }

        /** Repaints the rows once a provider answer was stored; the probe calls this on its own thread. */
        private void requestUsageRefresh() {
            runOnClient(this::refreshSettingsScreen);
        }

        // ---------------------------------------------------------------- runtime sections

        private void buildModelInfo(StatusTemplate status) {
            status.row("llm.info.name", llm("row.model_name"), this::selectedModelNameStatus);
            status.row("llm.info.size", llm("row.model_size"), this::selectedModelSizeStatus);
        }

        private void buildLoadOptions(OptionTemplate options) {
            options.select("llm.model", llm("option.model"), downloadedModelNames(), selectedModelName, this::modelOptionLabel, enabled::get);
        }

        private void buildDownloadFilters(OptionTemplate options) {
            options.select("llm.download.series", llm("option.series"), seriesFilterValues(), seriesFilter, this::filterLabel)
                    .select("llm.download.state", llm("option.download_state"), List.of(ALL, DOWNLOADED, NOT_DOWNLOADED), downloadStateFilter, this::filterLabel);
        }

        private void buildLoadStatus(StatusTemplate status) {
            status.row("llm.load.state", llm("row.load_state"), this::loadStateStatus);
            status.row("llm.download.progress", llm("row.download_status"), this::downloadStatusText);
        }

        // ---------------------------------------------------------------- session contract

        @Override
        public String moduleId() {
            return MODULE_ID;
        }

        @Override
        public boolean dirty() {
            return enabled.dirty()
                    || selectedModelName.dirty()
                    || models.dirty()
                    || activeModelId.dirty()
                    || !pendingApiKeys.isEmpty()
                    || !retiredApiKeyIds.isEmpty()
                    // An open form is unsaved state of this module. Counting it here is what makes the
                    // page-level 保存 reach this session at all: without it, a credential typed into the
                    // form would leave the session "clean" and the save would be skipped entirely.
                    || modelEditor != null;
        }

        @Override
        public SettingsValidationResult validate() {
            LlmModelInfo selected = resolveModel(selectedModelName.get());
            if (enabled.get() && selectedModelName.get() != null && !selectedModelName.get().isBlank() && (!selectedModelName.valid() || selected == null)) {
                return SettingsValidationResult.failure(llm("validation.invalid_model"));
            }
            if (enabled.get() && selected != null && !isDownloaded(selected)) {
                return SettingsValidationResult.failure(llm("validation.model_not_installed"));
            }
            // An open form is not a reason to refuse the page save. 保存 is expected to finish the job,
            // so the form is judged here as the change it would make — and applied by save(), never by
            // this method, which the 加载 button polls while the page renders. A form that cannot be
            // applied fails with its own reason, which is more actionable than "an editor is open"; a
            // form that can be applied loses nothing, because save() commits it instead of dropping it.
            EditorPlan plan = editorPlan();
            if (plan != null && !plan.applicable()) {
                return SettingsValidationResult.failure(plan.failure());
            }
            if (!enabled.get()) {
                return SettingsValidationResult.successful();
            }
            LlmModelEntry active = plannedActiveEntry(activeEntry(), plan);
            if (active == null) {
                return SettingsValidationResult.failure(llm("validation.model_list_empty"));
            }
            if (!isHttpUrl(active.baseUrl())) {
                return SettingsValidationResult.failure(llm("validation.invalid_base_url"));
            }
            if (active.modelId().isBlank()) {
                return SettingsValidationResult.failure(llm("validation.missing_model"));
            }
            if (!hasApiKey(active, plan)) {
                return SettingsValidationResult.failure(missingApiKeyMessage(active, plan));
            }
            if (!validContextWindow(active.contextWindowTokens())) {
                return SettingsValidationResult.failure(contextWindowRangeMessage());
            }
            return SettingsValidationResult.successful();
        }

        /**
         * Why the provider that will be in use has no credential, said precisely.
         *
         * <p>"No key" and "a key you have not applied" are different situations with different fixes: the
         * first asks the player to paste a key, the second asks them to save the form they already typed
         * it into. A key typed into the form of <em>another</em> provider is not a key of this one, and
         * reporting it as missing would send the player looking for a field they already filled in.
         */
        private UiText missingApiKeyMessage(LlmModelEntry active, EditorPlan plan) {
            ModelEditorBuffer editor = modelEditor;
            boolean typedButUnused = editor != null
                    && !trimmed(editor.apiKey()).isEmpty()
                    && (plan == null || !plan.applicable() || !plan.entry().id().equals(active.id()));
            return typedButUnused ? llm("validation.api_key_not_applied") : llm("validation.missing_api_key");
        }

        /**
         * The active provider as it will be once the page save ran: when the open form is that provider,
         * the checks above judge what is going to be stored instead of the state the player replaced.
         */
        private LlmModelEntry plannedActiveEntry(LlmModelEntry active, EditorPlan plan) {
            if (active == null || plan == null || !plan.applicable()) {
                return active;
            }
            return plan.entry().id().equals(active.id()) ? plan.entry() : active;
        }

        @Override
        public SettingsSaveResult save() {
            // The page-level 保存 commits the form the player still has open before it writes, so one
            // click finishes both the edit and the save. validate() has already judged exactly this plan,
            // so the failure here is only reachable when save() is called without validating first.
            // The picker needs no handling here: it is a window over this page, so it is either already
            // closed by the time 保存 can be pressed, or its selection was not confirmed and is not state.
            EditorPlan pendingPlan = editorPlan();
            if (pendingPlan != null) {
                if (!pendingPlan.applicable()) {
                    return SettingsSaveResult.failure(pendingPlan.failure(), SettingsSaveResult.FailureType.VALIDATION);
                }
                commitEditor(pendingPlan);
            }
            // Read after the commit: a provider the form just added is part of this save.
            boolean cloudSettingsChanged = models.dirty()
                    || activeModelId.dirty()
                    || !pendingApiKeys.isEmpty()
                    || !retiredApiKeyIds.isEmpty();
            SettingsSaveTransaction transaction = new SettingsSaveTransaction(enabled, selectedModelName, models, activeModelId)
                    .write(config::getCustomLlmName, config::setCustomLlmName, selectedModelName.get());
            for (String retiredModelId : retiredApiKeyIds) {
                // An empty credential removes the entry, so a deleted model takes its key with it.
                transaction.write(() -> config.getLlmApiKey(retiredModelId),
                        value -> config.setLlmApiKey(retiredModelId, value), "");
            }
            for (Map.Entry<String, String> pending : pendingApiKeys.entrySet()) {
                String modelId = pending.getKey();
                String apiKey = pending.getValue();
                transaction.write(() -> config.getLlmApiKey(modelId),
                        value -> config.setLlmApiKey(modelId, value), apiKey);
            }
            transaction.commit(config::save);
            pendingApiKeys.clear();
            retiredApiKeyIds.clear();
            if (!enabled.get()) {
                moduleService.unload();
            } else if (cloudSettingsChanged) {
                restartRunningRemoteRuntime();
            }
            // The cloud lane re-reads its endpoint without any model reload, so no restart is needed.
            return SettingsSaveResult.success(llm("message.saved"), true, false, false);
        }

        @Override
        public void reset() {
            enabled.reset();
            selectedModelName.reset();
            models.reset();
            activeModelId.reset();
            pendingApiKeys.clear();
            retiredApiKeyIds.clear();
            modelEditor = null;
            modelPicker = null;
            catalogFetching = false;
        }

        /**
         * The cloud client is built from configuration, so a running lane keeps serving the previous
         * endpoint until it is rebuilt. Rebuilding is cheap (no GGUF is mapped) and applies the new
         * credential immediately instead of asking the player to reload.
         */
        private void restartRunningRemoteRuntime() {
            if (!moduleService.snapshot().running()) {
                return;
            }
            moduleService.unload();
            moduleService.load();
        }

        private static boolean isHttpUrl(String value) {
            return value.startsWith("https://") || value.startsWith("http://");
        }

        private List<String> downloadedModelNames() {
            List<String> downloaded = modelService.allModels().stream()
                    .filter(this::isDownloaded)
                    .map(info -> info.name)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
            List<String> values = new ArrayList<>(downloaded.size() + 2);
            values.add("");
            values.addAll(downloaded);
            return values;
        }

        private String currentModelName() {
            String configured = config.getCustomLlmName();
            if (configured != null && !configured.isBlank() && resolveModel(configured) != null && isDownloaded(resolveModel(configured))) {
                return configured;
            }
            return "";
        }

        private UiText modelOptionLabel(String modelName) {
            return modelName == null || modelName.isBlank() ? common("not_selected") : UiText.literal(modelName);
        }

        private LlmModelInfo resolveModel(String name) {
            return modelService.resolveModel(name);
        }

        private ModelAvailabilitySnapshot.Entry availabilityEntry(LlmModelInfo info) {
            return info == null ? null : availabilitySnapshot.entry(info.name);
        }

        private boolean isDownloaded(LlmModelInfo info) {
            ModelAvailabilitySnapshot.Entry entry = availabilityEntry(info);
            return entry != null && entry.installed();
        }

        /**
         * The RAG vector model of the current language. It is not part of the cloud chat catalog, so it
         * does not appear in the download list and has its own group with a dedicated download action.
         */
        private LlmModelInfo embeddingModel() {
            String name = config.getLlmEmbeddingModelName();
            if (name == null || name.isBlank()) {
                return null;
            }
            return modelService.embeddingModelByName(name);
        }

        private UiText embeddingModelNameStatus() {
            LlmModelInfo info = embeddingModel();
            return info == null ? common("unknown") : UiText.literal(info.getDisplayName());
        }

        private UiText embeddingModelStateStatus() {
            LlmModelInfo info = embeddingModel();
            if (info == null) return common("unknown");
            return common(modelService.isModelInstalled(info) ? "downloaded" : "not_downloaded");
        }

        private boolean canDownloadEmbeddingModel() {
            LlmModelInfo info = embeddingModel();
            return enabled.get()
                    && info != null
                    && !modelService.isModelInstalled(info)
                    && !modelService.isDownloading()
                    && !modelService.isDeleting();
        }

        private void downloadEmbeddingModel(ModuleSettingsContext context) {
            LlmModelInfo info = embeddingModel();
            if (info == null) {
                context.showStatus(llm("error.embedding_unavailable"), 4000);
                return;
            }
            if (modelService.isModelInstalled(info)) {
                context.showStatus(llm("message.embedding_already_downloaded"), 3000);
                return;
            }
            if (!canDownloadEmbeddingModel()) {
                return;
            }
            modelService.downloadModel(info, new LlmModelService.DownloadProgressCallback() {
                @Override
                public void onProgress(ModelDownloadProgress progress) {
                    requestDownloadRefresh();
                }

                @Override
                public void onComplete() {
                    runOnClient(() -> {
                        availabilitySnapshot = modelService.modelAvailability();
                        context.showStatus(llm("message.embedding_download_complete"), 3000);
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

        private long cachedModelSizeBytes(LlmModelInfo info) {
            ModelAvailabilitySnapshot.Entry entry = availabilityEntry(info);
            return entry == null ? 0L : entry.sizeBytes();
        }

        private UiText selectedModelNameStatus() {
            LlmModelInfo info = resolveModel(selectedModelName.get());
            return info == null ? common("not_selected") : UiText.literal(info.getDisplayName());
        }

        private UiText selectedModelSizeStatus() {
            LlmModelInfo info = resolveModel(selectedModelName.get());
            if (info == null) return common("dash");
            ModelAvailabilitySnapshot.Entry entry = availabilityEntry(info);
            if (entry == null) return common("unknown");
            if (!entry.installed()) return common("not_downloaded");
            long sizeBytes = entry.sizeBytes();
            if (sizeBytes <= 0L) return common("unknown");
            if (sizeBytes > 1024L * 1024 * 1024) {
                return UiText.literal(String.format(Locale.ROOT, "%.1f GB", sizeBytes / (1024.0 * 1024 * 1024)));
            }
            return UiText.literal(String.format(Locale.ROOT, "%.0f MB", sizeBytes / (1024.0 * 1024)));
        }

        private boolean canLoad() {
            if (!enabled.get()) return false;
            LlmRuntimeSnapshot snapshot = moduleService.snapshot();
            if (snapshot.state() == LlmRuntimeState.STARTING || snapshot.state() == LlmRuntimeState.STOPPING || snapshot.running()) return false;
            // Chat runs in the cloud, so a downloaded local GGUF is no longer a precondition: the lane
            // only needs a complete endpoint configuration.
            return validate().success();
        }

        private boolean canUnload() {
            if (!enabled.get()) return false;
            LlmRuntimeSnapshot snapshot = moduleService.snapshot();
            return snapshot.running() || snapshot.state() == LlmRuntimeState.STARTING;
        }

        private void startLoad(ModuleSettingsContext context) {
            SettingsValidationResult validation = validate();
            if (!validation.success()) {
                context.showStatus(validation.message(), 4000);
                return;
            }
            if (dirty()) {
                // The runtime reads the persisted endpoint, so unsaved edits are written first.
                save();
                refreshSettingsScreen();
            }
            LlmControlResult result = moduleService.load();
            context.showStatus(result.accepted() ? llm("message.load_started") : UiText.literal(result.message()), 3000);
        }

        private void stopLoad(ModuleSettingsContext context) {
            LlmControlResult result = moduleService.unload();
            context.showStatus(result.accepted() ? llm("message.unload_started") : UiText.literal(result.message()), 3000);
        }

        /**
         * Chat is served by the cloud lane, so the runtime state no longer depends on a downloaded
         * GGUF: a local model is only the parked embedding/RAG engine and never gates the lane.
         */
        private UiText loadStateStatus() {
            if (!enabled.get()) return llm("state.disabled");
            LlmRuntimeSnapshot snapshot = moduleService.snapshot();
            return switch (snapshot.state()) {
                case DISABLED -> llm("state.disabled");
                case STARTING -> llm("state.loading");
                case RUNNING -> llm("state.loaded");
                case STOPPING -> llm("state.unloading");
                case FAILED -> failureMessageText(snapshot.failureMessage());
                case STOPPED -> llm("state.unloaded");
            };
        }

        /**
         * The runtime reports a resource key when it knows the actionable cause (for example an
         * unconfigured cloud credential); anything else is shown as the raw diagnostic.
         */
        private UiText failureMessageText(String failureMessage) {
            if (failureMessage == null || failureMessage.isBlank()) {
                return llm("state.failed");
            }
            return failureMessage.startsWith("tianshu.")
                    ? UiText.key(failureMessage)
                    : UiText.literal(failureMessage);
        }

        private List<LlmModelInfo> downloadableModels() {
            return modelService.allModels().stream()
                    .filter(this::matchesSeriesFilter)
                    .filter(this::matchesDownloadStateFilter)
                    .toList();
        }

        private SettingsListCard downloadModelCard(LlmModelInfo info) {
            if (info == null) return SettingsListCard.text(UiText.literal(""));
            LlmModelCardState state = cardState(info);
            UiText title = UiText.literal(info.getDisplayName());
            UiText status = state.statusLabel();
            List<UiText> details = new ArrayList<>();
            UiText description = modelDescriptionLabel(info);
            if (description != null) {
                details.add(llm("download.card.description", description));
            }
            details.add(llm("download.card.series", modelSeriesLabel(info)));
            details.add(llm("download.card.size", modelDownloadSizeLabel(info)));
            details.add(llm("download.card.vram", estimatedVramLabel(info)));
            details.add(llm("download.card.thinking_capability", thinkingCapabilityLabel(info)));
            return new SettingsListCard(title, status, details, List.of());
        }

        private void buildDownloadItemActions(ModuleSettingsContext context, LlmModelInfo info, com.rheinmetal.tianshu.client.api.settings.ItemActionTemplate<LlmModelInfo> actions) {
            actions.button("llm.download.item.start", llm("action.download"), SettingsButtonStyle.PRIMARY, model -> startDownload(context, model), model -> cardState(model).canStartDownload())
                    .button("llm.download.item.pause", llm("action.pause"), this::pauseDownload, model -> cardState(model).canPauseDownload())
                    .button("llm.download.item.resume", llm("action.resume"), this::resumeDownload, model -> cardState(model).canResumeDownload())
                    .button("llm.download.item.cancel", llm("action.cancel"), SettingsButtonStyle.DANGER, model -> cancelDownload(context, model), model -> cardState(model).canCancelDownload())
                    .button("llm.download.item.delete", llm("action.delete"), SettingsButtonStyle.DANGER, model -> deleteModel(context, model), model -> cardState(model).canDeleteModel());
        }

        private void startDownload(ModuleSettingsContext context, LlmModelInfo info) {
            if (info == null || !cardState(info).canStartDownload()) return;
            modelService.downloadModel(info, new LlmModelService.DownloadProgressCallback() {
                @Override
                public void onProgress(ModelDownloadProgress progress) {
                    requestDownloadRefresh();
                }

                @Override
                public void onComplete() {
                    runOnClient(() -> {
                        availabilitySnapshot = modelService.modelAvailability();
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
            boolean known = availabilityEntry(info) != null;
            boolean installed = info != null && isDownloaded(info);
            boolean deleting = modelService.isDeletingModel(info);
            boolean anyOperationActive = downloading || modelService.isDeleting();
            return new LlmModelCardState(info, known, installed, anyOperationActive, activeDownload, paused, cancelling, deleting);
        }

        private boolean sameModelName(LlmModelInfo info, String modelName) {
            return info != null && info.name != null && modelName != null && info.name.equalsIgnoreCase(modelName);
        }

        /**
         * Deletes a downloaded local GGUF. This is the file-management action of the download catalog,
         * unrelated to deleting a configured cloud model.
         */
        private void deleteModel(ModuleSettingsContext context, LlmModelInfo info) {
            if (info == null || modelService.isDownloading() || modelService.isDeleting()) return;
            String deletedModelName = info.name == null ? "" : info.name;
            UiText displayName = UiText.literal(info.getDisplayName());
            modelService.deleteModelAsync(info, deleted -> runOnClient(() -> {
                if (deleted) {
                    context.showStatus(llm("message.deleted", displayName), 3000);
                    if (!deletedModelName.isBlank() && deletedModelName.equalsIgnoreCase(selectedModelName.get())) {
                        selectedModelName.set(currentModelName());
                    }
                } else {
                    context.showStatus(llm("message.delete_failed"), 3000);
                }
                availabilitySnapshot = modelService.modelAvailability();
                refreshSettingsScreen();
            }));
        }

        private UiText downloadStatusText() {
            LlmModelService.DownloadSnapshot snapshot = modelService.downloadSnapshot();
            if (snapshot.running()) {
                UiText label = snapshot.cancelling()
                        ? llm("status.cancelling")
                        : snapshot.paused()
                        ? llm("status.paused")
                        : llm("status.downloading");
                return llm("download.progress", label, snapshot.progress().percent());
            }
            if (!snapshot.errorMessage().isBlank()) {
                return localizedDownloadMessage(snapshot.errorMessage());
            }
            return llm("status.idle");
        }

        private UiText localizedDownloadMessage(String message) {
            if (message == null || message.isBlank()) {
                return llm("status.download_failed");
            }
            if (message.startsWith("tianshu.")) {
                return UiText.key(message);
            }
            return UiText.literal(message);
        }

        private void runOnClient(Runnable runnable) {
            scheduler.execute(runnable);
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
            uiHost.requestSettingsRefresh();
        }

        private UiText filterLabel(String value) {
            return switch (value == null ? ALL : value) {
                case DOWNLOADED -> common("downloaded");
                case NOT_DOWNLOADED -> common("not_downloaded");
                case ALL -> llm("filter.all");
                default -> UiText.literal(value);
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
                case DOWNLOADED -> isDownloaded(info);
                case NOT_DOWNLOADED -> availabilityEntry(info) != null && !isDownloaded(info);
                default -> true;
            };
        }

        private UiText modelSeriesLabel(LlmModelInfo info) {
            String series = modelSeries(info);
            return series.isBlank() ? common("unknown") : UiText.literal(series);
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

        private UiText modelDownloadSizeLabel(LlmModelInfo info) {
            if (info == null) return common("unknown");
            long actualBytes = cachedModelSizeBytes(info);
            if (actualBytes > 0L) return formatBytes(actualBytes);
            long estimatedBytes = estimatedModelSizeBytes(info);
            return estimatedBytes > 0L ? llm("size.estimated", formatBytes(estimatedBytes)) : common("unknown");
        }

        private UiText estimatedVramLabel(LlmModelInfo info) {
            long bytes = estimatedVramBytes(info);
            return bytes > 0L ? llm("size.estimated", formatBytes(bytes)) : common("unknown");
        }

        private UiText modelDescriptionLabel(LlmModelInfo info) {
            if (info == null) return null;
            String key = info.getDescriptionKey();
            if (!key.isBlank()) {
                return UiText.key(key);
            }
            String description = info.getDescription();
            return description.isBlank() ? null : UiText.literal(description);
        }

        private UiText thinkingCapabilityLabel(LlmModelInfo info) {
            if (info == null || info.getThinkingCapability().isBlank()) {
                return common("unknown");
            }
            return llm("thinking_capability." + info.getThinkingCapability());
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
            long modelBytes = Math.max(cachedModelSizeBytes(info), estimatedModelSizeBytes(info));
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

        private UiText formatBytes(long bytes) {
            if (bytes >= 1024L * 1024 * 1024) {
                return UiText.literal(String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024)));
            }
            return UiText.literal(String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024)));
        }

        /**
         * The open form as the change it would make, or the reason it cannot be made. It is a value, not
         * a mutation: {@link #validate()} builds it to judge the form, {@link #save()} builds it and hands
         * it to {@link #commitEditor}, and only the commit touches the draft.
         *
         * @param entry   the provider that would replace or join the draft list
         * @param apiKey  the credential typed into the form; empty when the player did not replace it
         * @param failure why the form cannot be applied, {@code null} when it can
         */
        private record EditorPlan(LlmModelEntry entry, String apiKey, UiText failure) {
            private static EditorPlan applied(LlmModelEntry entry, String apiKey) {
                return new EditorPlan(entry, apiKey == null ? "" : apiKey, null);
            }

            private static EditorPlan rejected(UiText failure) {
                return new EditorPlan(null, "", failure);
            }

            private boolean applicable() {
                return entry != null;
            }
        }

        /** The edited catalog as specs, or the reason the rows cannot be committed. */
        private record CatalogPlan(List<LlmModelSpec> specs, UiText failure) {
            private static CatalogPlan applied(List<LlmModelSpec> specs) {
                return new CatalogPlan(specs, null);
            }

            private static CatalogPlan rejected(UiText failure) {
                return new CatalogPlan(List.of(), failure);
            }

            private boolean applicable() {
                return failure == null;
            }
        }

        /**
         * One credential a fetch may spend, with the place it came from.
         *
         * <p>The value is the only secret in this file and it never becomes text: it goes into the request
         * the catalog seam makes, while the source is what a failure line may name. Keeping the two
         * together is what stops "which of the three keys was actually used" from being a question only a
         * debugger can answer.
         */
        private record CatalogCredential(String value, LlmModelCatalogKeySource source) {
            private CatalogCredential {
                value = value == null ? "" : value;
                source = source == null ? LlmModelCatalogKeySource.NONE : source;
            }
        }
    }

    /**
     * The two shapes of one provider entry, mirroring the reference's segment switch.
     *
     * <p>{@link #BUILTIN} takes everything but the credential from
     * {@link LlmBuiltinProviders}; {@link #CUSTOM} asks for the endpoint itself. The mode is form state,
     * not stored state: which mode an entry opens in is derived from whether it still is a shipped
     * profile.
     */
    private enum EditorMode {
        BUILTIN("option.mode.builtin"),
        CUSTOM("option.mode.custom");

        private final String key;

        EditorMode(String key) {
            this.key = key;
        }

        private UiText label() {
            return llm(key);
        }
    }

    /**
     * Mutable text of one provider being added or edited, including the model catalog it serves. Values
     * are raw strings on purpose: a half-typed endpoint must survive until the player applies the form.
     *
     * <p>The buffer also holds the mode and the selected built-in provider, because switching either one
     * has to rewrite the other's fields: the two are one decision, not two. The same holds for the
     * catalog — a provider profile ships exactly one model, so picking another provider replaces the
     * catalog with that profile's default.
     *
     * <p>The catalog here is the committed form state. While the picker window is open it owns a copy
     * ({@link ModelPickerBuffer}), so its edits reach this list only through the window's 确认 — see
     * {@code ModelPickerSession#confirm()}.
     */
    private static final class ModelEditorBuffer {
        private final String entryId;
        private EditorMode mode;
        private String providerId;
        private String displayName;
        private String baseUrl;
        /** The catalog rows in form order; every edit replaces this list rather than mutating in place. */
        private List<CatalogRowBuffer> rows;
        /** The model id this provider would use if the form were applied now. */
        private String activeModelId;
        private String apiKey;

        private ModelEditorBuffer(String entryId, EditorMode mode, String providerId, String displayName,
                                  String baseUrl, List<LlmModelSpec> models, String activeModelId, String apiKey) {
            this.entryId = entryId;
            this.mode = mode == null ? EditorMode.BUILTIN : mode;
            this.providerId = providerId;
            this.displayName = displayName;
            this.baseUrl = baseUrl;
            this.rows = catalogRowsOf(models);
            this.activeModelId = activeModelId;
            this.apiKey = apiKey;
        }

        private static List<CatalogRowBuffer> catalogRowsOf(List<LlmModelSpec> models) {
            List<CatalogRowBuffer> buffers = new ArrayList<>();
            if (models != null) {
                for (LlmModelSpec spec : models) {
                    if (spec != null) {
                        buffers.add(CatalogRowBuffer.of(spec));
                    }
                }
            }
            return buffers;
        }

        private String entryId() {
            return entryId;
        }

        private EditorMode mode() {
            return mode;
        }

        private boolean builtinMode() {
            return mode == EditorMode.BUILTIN;
        }

        private boolean customMode() {
            return mode == EditorMode.CUSTOM;
        }

        /**
         * Switches the form's shape. Entering {@link EditorMode#BUILTIN} re-derives the endpoint fields
         * from the selected provider, so the form never shows text the player cannot edit; an entry that
         * carried a provider id this build does not ship falls back to the default one instead of leaving
         * the form in a state that cannot be saved.
         */
        private void selectMode(EditorMode next) {
            if (next == null || next == mode) {
                return;
            }
            mode = next;
            if (next == EditorMode.BUILTIN) {
                LlmBuiltinProviders.Provider provider = LlmBuiltinProviders.find(providerId);
                applyProvider(provider == null ? LlmBuiltinProviders.defaultProvider() : provider);
            }
        }

        /** Picks another built-in provider and copies its shipped profile into the derived fields. */
        private void selectProvider(String providerId) {
            if (providerId == null) {
                return;
            }
            this.providerId = providerId;
            if (mode == EditorMode.BUILTIN) {
                applyProvider(LlmBuiltinProviders.find(providerId));
            }
        }

        private void applyProvider(LlmBuiltinProviders.Provider provider) {
            if (provider == null) {
                return;
            }
            this.providerId = provider.id();
            this.displayName = provider.id();
            this.baseUrl = provider.baseUrl();
            // The profile is the definition of a built-in provider, its model catalog included.
            this.rows = catalogRowsOf(provider.defaultCatalog());
            this.activeModelId = provider.defaultModelId();
        }

        private String providerId() {
            return providerId;
        }

        private String displayName() {
            return displayName;
        }

        private void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        private String baseUrl() {
            return baseUrl;
        }

        private void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        // --- catalog rows -----------------------------------------------------------------------

        private int rowCount() {
            return rows.size();
        }

        private String modelIdAt(int index) {
            return rows.get(index).modelId();
        }

        private String displayNameAt(int index) {
            return rows.get(index).displayName();
        }

        /** Declared window this row stores; it comes from a preset, so it is read, not typed. */
        private int windowTokensAt(int index) {
            return rows.get(index).windowTokens();
        }

        /**
         * The model in use, resolved against the rows on every read: a renamed or deleted row cannot
         * leave the form pointing at a model that is no longer in its catalog.
         */
        private String activeModelId() {
            return resolveActiveModelId(activeModelId);
        }

        /** An independent copy of the catalog, which is what the picker edits. */
        private List<CatalogRowBuffer> rowCopies() {
            return catalogCopiesOf(rows);
        }

        /**
         * Replaces the whole catalog and the model in use, which is what confirming the picker does. Both
         * arrive from one picker state, so they cannot disagree: the selection is resolved against the new
         * rows here.
         */
        private void replaceCatalog(List<CatalogRowBuffer> rows, String activeModelId) {
            this.rows = catalogCopiesOf(rows);
            this.activeModelId = resolveActiveModelId(activeModelId);
        }

        private String resolveActiveModelId(String candidate) {
            String wanted = trimmed(candidate);
            if (!wanted.isEmpty()) {
                for (CatalogRowBuffer row : rows) {
                    if (trimmed(row.modelId()).equals(wanted)) {
                        return wanted;
                    }
                }
            }
            return rows.isEmpty() ? "" : trimmed(rows.get(0).modelId());
        }

        private static List<CatalogRowBuffer> catalogCopiesOf(List<CatalogRowBuffer> source) {
            List<CatalogRowBuffer> copies = new ArrayList<>();
            if (source != null) {
                for (CatalogRowBuffer row : source) {
                    if (row != null) {
                        copies.add(row.copy());
                    }
                }
            }
            return copies;
        }

        private String apiKey() {
            return apiKey;
        }

        private void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /**
     * One model of the catalog being edited: the provider id, its display name and the declared window it
     * carries.
     *
     * <p>A row is never typed in directly. It is created from a preset, from a provider answer, or from
     * the id the player typed into the picker, so a row without an id cannot exist and the catalog stays a
     * list of models instead of a list of blocks to fill in.
     *
     * <p>The declared window is not edited anywhere. A row keeps the window it was created with — the
     * shipped profile's, or the one already stored — so the value moves through the form unchanged.
     */
    private static final class CatalogRowBuffer {
        private final String modelId;
        private final String displayName;
        private final int contextWindowTokens;

        private CatalogRowBuffer(String modelId, String displayName, int contextWindowTokens) {
            this.modelId = modelId;
            this.displayName = displayName;
            this.contextWindowTokens = contextWindowTokens;
        }

        private static CatalogRowBuffer of(LlmModelSpec spec) {
            return new CatalogRowBuffer(spec.modelId(), spec.displayName(), spec.contextWindowTokens());
        }

        private CatalogRowBuffer copy() {
            return new CatalogRowBuffer(modelId, displayName, contextWindowTokens);
        }

        private String modelId() {
            return modelId == null ? "" : modelId;
        }

        private String displayName() {
            return displayName == null ? "" : displayName;
        }

        private int windowTokens() {
            return contextWindowTokens;
        }

        private LlmModelSpec toSpec() {
            return new LlmModelSpec(modelId(), displayName(), contextWindowTokens);
        }
    }

    /**
     * One opening of the model picker: a copy of the form's catalog, the model checked out in it, the
     * search term and the id typed for a custom model.
     *
     * <p>It copies the rows instead of editing the form's own list, so closing the picker with 取消 leaves
     * both the list and the model in use exactly as they were. 确认 is the only path back, which is the
     * whole reason this state exists apart from the form it edits.
     *
     * <p>The rows only ever grow here: the picker offers search, fetching and one custom entry, so there
     * is no row deletion to model. A provider keeps at least the models it was configured with, and a
     * fetched catalog replaces the list with what the provider actually serves.
     */
    private static final class ModelPickerBuffer {
        private final List<CatalogRowBuffer> rows = new ArrayList<>();
        private String selectedModelId;
        private String search;
        private String customModelId;

        private ModelPickerBuffer(List<CatalogRowBuffer> rows, String selectedModelId) {
            if (rows != null) {
                for (CatalogRowBuffer row : rows) {
                    if (row != null) {
                        this.rows.add(row.copy());
                    }
                }
            }
            this.selectedModelId = trimmed(selectedModelId);
            this.search = "";
            this.customModelId = "";
        }

        private List<CatalogRowBuffer> rows() {
            return List.copyOf(rows);
        }

        /**
         * The ids of this picker's catalog, in catalog order.
         *
         * <p>Unfiltered: the layer's list applies its own search term, so the picker and the framework
         * cannot disagree about what a match is.
         */
        private List<String> modelIds() {
            List<String> ids = new ArrayList<>(rows.size());
            for (CatalogRowBuffer row : rows) {
                String modelId = trimmed(row.modelId());
                if (!modelId.isEmpty() && !ids.contains(modelId)) {
                    ids.add(modelId);
                }
            }
            return List.copyOf(ids);
        }

        private String selectedModelId() {
            return selectedModelId;
        }

        /** Whether the checked-out model is still one of the rows; a fetch can replace the list. */
        private boolean hasSelection() {
            return rowFor(selectedModelId) != null;
        }

        private void select(String modelId) {
            if (rowFor(modelId) != null) {
                selectedModelId = trimmed(modelId);
            }
        }

        private String search() {
            return search;
        }

        private void setSearch(String search) {
            this.search = search;
        }

        private String customModelId() {
            return customModelId;
        }

        private void setCustomModelId(String customModelId) {
            this.customModelId = customModelId;
        }

        /** Whether the typed id can become a row at all: not blank and not already listed. */
        private boolean canAddCustom() {
            String modelId = trimmed(customModelId);
            return !modelId.isEmpty() && rowFor(modelId) == null;
        }

        /** Adds the typed model as a row and checks it out; a blank or repeated id changes nothing. */
        private void addCustomModel(int contextWindowTokens) {
            if (!canAddCustom()) {
                return;
            }
            String modelId = trimmed(customModelId);
            rows.add(CatalogRowBuffer.of(LlmModelSpec.of(modelId, contextWindowTokens)));
            selectedModelId = modelId;
            customModelId = "";
        }

        /**
         * Fills the list with a provider answer.
         *
         * <p>A model that is already configured keeps its display name and window: the provider lists ids
         * only, so re-reading the list must not throw away what is already known about a model. New ids
         * arrive with the id as their display name and the provider's preset window.
         */
        private void replaceWithFetchedModels(List<String> modelIds, int contextWindowTokens) {
            if (modelIds == null || modelIds.isEmpty()) {
                return;
            }
            List<CatalogRowBuffer> next = new ArrayList<>(modelIds.size());
            for (String modelId : modelIds) {
                String id = trimmed(modelId);
                if (id.isEmpty()) {
                    continue;
                }
                CatalogRowBuffer existing = rowFor(id);
                next.add(existing == null
                        ? CatalogRowBuffer.of(LlmModelSpec.of(id, contextWindowTokens))
                        : existing.copy());
            }
            if (next.isEmpty()) {
                return;
            }
            rows.clear();
            rows.addAll(next);
            if (!hasSelection()) {
                selectedModelId = firstRowId();
            }
        }

        private String firstRowId() {
            return rows.isEmpty() ? "" : trimmed(rows.get(0).modelId());
        }

        private CatalogRowBuffer rowFor(String modelId) {
            String wanted = trimmed(modelId);
            if (wanted.isEmpty()) {
                return null;
            }
            for (CatalogRowBuffer row : rows) {
                if (trimmed(row.modelId()).equals(wanted)) {
                    return row;
                }
            }
            return null;
        }
    }

    private record LlmModelCardState(LlmModelInfo info, boolean known, boolean installed, boolean anyOperationActive, boolean activeDownload, boolean paused, boolean cancelling, boolean deleting) {
        private boolean canStartDownload() {
            return info != null && known && !installed && !anyOperationActive;
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
            return info != null && known && installed && !anyOperationActive && !deleting;
        }

        private UiText statusLabel() {
            if (activeDownload) {
                if (cancelling) {
                    return llm("status.cancelling");
                }
                return paused ? llm("status.paused") : llm("status.downloading");
            }
            return common(!known ? "unknown" : (installed ? "downloaded" : "not_downloaded"));
        }
    }
}
