package com.rheinmetal.tianshu.client.settings.module.weaving;

import com.rheinmetal.tianshu.function.weaving.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.weaving.settings.LlmSettingsAccess;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerElement;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerHost;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerModel;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientScheduler;
import com.rheinmetal.tianshu.client.host.ClientUiHost;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsPanelModel;
import com.rheinmetal.tianshu.client.settings.model.SettingsTemplateModel;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.session.SettingsValidationResult;
import com.rheinmetal.tianshu.function.weaving.LlmModelService;
import com.rheinmetal.tianshu.function.weaving.LlmModuleService;
import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogClient;
import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogKeySource;
import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogResult;
import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogState;
import com.rheinmetal.tianshu.function.weaving.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.weaving.settings.LlmModelEntry;
import com.rheinmetal.tianshu.function.weaving.settings.LlmModelSpec;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The provider page as the player drives it: the form, the picker layer it describes, and what a save
 * does with either.
 *
 * <p>The page is built through {@link LlmSettingsRegistrySource#register} and read through the very
 * templates the renderer consumes. The picker is read through the layer the page hands to a recording
 * host — the same description a window draws — so the whole flow (open, search, add, fetch, confirm,
 * cancel) runs without a game runtime, and the assertions are about what the window would show.
 */
final class LlmProviderEditorPanelTest {
    private static final String MODULE_ID = "module.llm";
    private static final String TYPED_KEY = "sk-typed-into-the-editor";
    private static final String PROVIDERS_LIST = "llm.providers";
    private static final String ADD_BUTTON_GROUP = "llm.providers.add";
    private static final String EMBEDDING_GROUP = "llm.embedding";
    private static final String EDITOR_GROUP = "llm.providers.editor";
    private static final String EDITOR_MODEL_ROW = "llm.editor.active_model";
    private static final String CUSTOM_MODEL = "my-own-model";

    private static final ClientScheduler DIRECT_SCHEDULER = new ClientScheduler() {
        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public boolean isOnMainThread() {
            return true;
        }
    };

    private static final ClientUiHost NOOP_UI_HOST = new ClientUiHost() {
        @Override
        public void openSettings() {
        }

        @Override
        public void requestSettingsRefresh() {
        }

        @Override
        public void showStatus(UiText text, long durationMillis) {
        }
    };

    @TempDir
    Path gameDirectory;

    @Test
    void aKeyTypedOnlyIntoTheEditorIsAcceptedByThePageSaveAndWritten() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            // The reported scenario: the player types the key and presses the page-level 保存, never the
            // form's own one. Validation used to see an empty credential because it ran before the form
            // was applied.
            harness.runProviderAction("llm.provider.edit");
            harness.typeEditorText("llm.editor.api_key", TYPED_KEY);

            assertTrue(harness.session().dirty(), "an open form is unsaved state of the module");

            SettingsValidationResult validation = harness.session().validate();
            assertTrue(validation.success(),
                    () -> "the page save refused a credential that was just typed: " + validation.message().value());

            SettingsSaveResult saved = harness.session().save();
            assertTrue(saved.success(), () -> "the page save failed: " + saved.message().value());
            assertEquals(TYPED_KEY, harness.access().getLlmApiKey(LlmModelEntry.LEGACY_ID),
                    "the credential typed into the editor must be written by the same click");
            assertEquals(List.of(LlmModelEntry.LEGACY_ID), harness.access().entryIds(),
                    "the form replaced the edited provider instead of adding one");
            assertFalse(harness.editorOpen(), "committing the form closes it");
            assertEquals(1, harness.access().saveCount(), "the page save persists the profile once");
        }
    }

    @Test
    void theColumnIsCardsThenTheAddButtonThenOneEditorSection() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            assertEquals(List.of(PROVIDERS_LIST, ADD_BUTTON_GROUP, EDITOR_GROUP), harness.leftColumnIds(),
                    "the column is the cards, the add button and one editor section, in that order");

            harness.runProviderAction("llm.provider.edit");

            assertEquals(List.of(PROVIDERS_LIST, ADD_BUTTON_GROUP, EDITOR_GROUP), harness.leftColumnIds(),
                    "opening a form adds no section: the catalog is a layer, not a part of the page");
            assertEquals(0, harness.layerHost().opens(),
                    "and no layer is opened until the player asks for one");
        }
    }

    @Test
    void theVectorModelIsAFullWidthRowDirectlyAboveTheLaneSelector() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            assertEquals(List.of("llm.enabled", EMBEDDING_GROUP, "llm.lane.selector", "llm.panel.layout"),
                    harness.topLevelIds(),
                    "the vector model is the page's second row: everything below it needs an embedding "
                            + "file, so it outranks the lane and spans the page");
        }
    }

    @Test
    void anUnreadyVectorModelHidesTheLaneAndBothColumns() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            assertTrue(harness.template(EMBEDDING_GROUP).visible().getAsBoolean(),
                    "the download row itself stays on screen");
            assertFalse(harness.template("llm.lane.selector").visible().getAsBoolean(),
                    "an unready page does not offer a lane");
            assertFalse(harness.template("llm.panel.layout").visible().getAsBoolean(),
                    "and it does not draw the provider columns: the gate is visibility, not a grey-out");

            assertEquals(2, harness.visibleTopLevelIds().size(),
                    "so an unready page is exactly the enable switch and the download row");
        }
    }

    @Test
    void theVectorModelSectionStatesTheModelItsStateAndItsDownload() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            SettingsTemplateModel.CompoundGroup embedding = harness.group(EMBEDDING_GROUP);
            assertNotNull(embedding, "the vector model has a section of its own");
            assertTrue(embedding.visible().getAsBoolean(), "and it is always on screen");

            assertEquals(List.of("llm.embedding.model", "llm.embedding.state", "llm.embedding.hint"),
                    embedding.statuses().stream().map(SettingsTemplateModel.StatusEntry::id).toList(),
                    "the section states which vector model, what its download is doing, and what a load "
                            + "on the card risks on this machine");

            SettingsTemplateModel.ActionEntry download = embedding.actions().get(0);
            assertEquals("llm.embedding.download", download.id(),
                    "the button comes first: it is the row's only action");
            assertEquals("tianshu.gui.llm.action.download_embedding", download.label().value(),
                    "and it offers a download while none was attempted");
        }
    }

    /**
     * The position button states the position that is actually configured, and keeps stating it.
     *
     * <p>It used to read a value the page captured once when it was built, so a configuration changed
     * underneath the page — a hand-edited file, a default that moved with an update, another page's save —
     * left the button on the old position for the rest of the session: the button said "CPU" while the
     * configuration said "GPU", and the next save wrote that stale value back. The three reads below all
     * happen in one page session, and 999 is the configured default (every layer on the card).
     */
    @Test
    void thePositionButtonStatesTheConfiguredPositionAndFollowsIt() throws Exception {
        try (Harness harness = new Harness(gameDirectory,
                access -> access.setLlmEmbeddingGpuLayers(LlmConfiguration.EMBEDDING_GPU_LAYERS_ALL))) {
            assertEquals("tianshu.gui.llm.action.embedding_position_gpu", harness.positionArgumentKey(),
                    "999 offloads every layer, so the button has to say GPU");

            harness.access().setLlmEmbeddingGpuLayers(LlmConfiguration.EMBEDDING_GPU_LAYERS_CPU);
            assertEquals("tianshu.gui.llm.action.embedding_position_cpu", harness.positionArgumentKey(),
                    "a page built when the configuration said CPU must not outlive that answer");

            harness.access().setLlmEmbeddingGpuLayers(LlmConfiguration.EMBEDDING_GPU_LAYERS_ALL);
            assertEquals("tianshu.gui.llm.action.embedding_position_gpu", harness.positionArgumentKey(),
                    "and it follows the configuration back without a restart");
        }
    }

    /** A click stages the position: the button says so at once, and the save is what writes it. */
    @Test
    void clickingThePositionStagesItAndTheSaveWritesIt() throws Exception {
        try (Harness harness = new Harness(gameDirectory,
                access -> access.setLlmEmbeddingGpuLayers(LlmConfiguration.EMBEDDING_GPU_LAYERS_ALL))) {
            harness.runEmbeddingAction("llm.embedding.position");

            assertEquals("tianshu.gui.llm.action.embedding_position_cpu", harness.positionArgumentKey(),
                    "the click has to show the position it staged");
            assertEquals(LlmConfiguration.EMBEDDING_GPU_LAYERS_ALL,
                    harness.access().getLlmEmbeddingGpuLayers(),
                    "and it must not write anything by itself: the load costs a native reload");

            assertTrue(harness.session().save().success());

            assertEquals(LlmConfiguration.EMBEDDING_GPU_LAYERS_CPU, harness.access().getLlmEmbeddingGpuLayers(),
                    "the page save applies the staged position");
        }
    }

    @Test
    void theEditorIsTheFormAndOneReadOnlyModelLineWithOneButton() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            harness.runProviderAction("llm.provider.edit");

            List<String> optionIds = harness.editor().options().stream()
                    .map(SettingsTemplateModel.OptionEntry::id)
                    .toList();
            assertEquals(List.of("llm.editor.mode", "llm.editor.provider", "llm.editor.name",
                            "llm.editor.base_url", "llm.editor.api_key"), optionIds,
                    "the editor holds the endpoint form and nothing about the catalog: " + optionIds);

            List<String> actionIds = harness.editor().actions().stream()
                    .map(SettingsTemplateModel.ActionEntry::id)
                    .toList();
            assertTrue(actionIds.contains("llm.editor.choose_model"),
                    "one button opens the layer: " + actionIds);

            assertEquals(harness.firstProvider().activeModelId(), harness.editorStatusValue(EDITOR_MODEL_ROW),
                    "the model in use is stated once, read-only");

            assertTrue(harness.allTemplates().stream()
                            .noneMatch(template -> template instanceof SettingsTemplateModel.ToggleGroup),
                    "the model rows carry no switches");
        }
    }

    @Test
    void openingThePickerDescribesOneLayerAndLeavesThePageAlone() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            harness.runProviderAction("llm.provider.edit");
            String original = harness.editorStatusValue(EDITOR_MODEL_ROW);

            LayerDriver picker = harness.openPickerLayer();

            assertEquals("tianshu.gui.llm.section.model_picker", picker.titleKey(),
                    "the layer is titled for the player");
            assertEquals(List.of(original), picker.rows(), "the layer lists this provider's catalog");
            assertEquals(original, picker.selected(), "with the model in use checked out");
            assertTrue(picker.statusKey().isEmpty(), "and nothing to report before the player asks");

            assertTrue(harness.editorOpen(), "the form stays where it was while the layer is open");
            assertEquals(List.of(PROVIDERS_LIST, ADD_BUTTON_GROUP, EDITOR_GROUP),
                    harness.leftColumnIds(),
                    "the page under the layer is untouched");
        }
    }

    @Test
    void searchNarrowsTheLayerListWithoutApplyStep() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            harness.runProviderAction("llm.provider.edit");
            LayerDriver picker = harness.openPickerLayer();
            String original = picker.selected();
            picker.addCustomModel(CUSTOM_MODEL);

            assertEquals(List.of(original, CUSTOM_MODEL), picker.rows(), "the catalog is unfiltered");

            picker.search("own");
            assertEquals(List.of(CUSTOM_MODEL), picker.rows(),
                    "the framework narrows the list, with no apply step");
            assertEquals(CUSTOM_MODEL, picker.selected(),
                    "filtering changes what is listed, not what is checked out");

            picker.search("no-such-model");
            assertTrue(picker.rows().isEmpty(), "a term that matches nothing lists nothing");

            picker.search("");
            assertEquals(List.of(original, CUSTOM_MODEL), picker.rows(), "clearing the term restores it");
        }
    }

    @Test
    void aCustomModelIsAddedAndCheckedOutByTheLayer() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            harness.runProviderAction("llm.provider.edit");
            LayerDriver picker = harness.openPickerLayer();

            assertFalse(picker.actionEnabled(LlmModelPickerLayers.ACTION_ADD_CUSTOM),
                    "an empty field cannot become a model");

            picker.typeCustomModel(CUSTOM_MODEL);
            assertTrue(picker.actionEnabled(LlmModelPickerLayers.ACTION_ADD_CUSTOM), "a new id can");
            picker.click(LlmModelPickerLayers.ACTION_ADD_CUSTOM);

            assertTrue(picker.rows().contains(CUSTOM_MODEL), "the model joins the catalog");
            assertEquals(CUSTOM_MODEL, picker.selected(), "and is the one checked out");
            assertEquals("", picker.customModelText(), "the field is cleared for the next one");

            picker.typeCustomModel(CUSTOM_MODEL);
            assertFalse(picker.actionEnabled(LlmModelPickerLayers.ACTION_ADD_CUSTOM),
                    "the same id cannot be added twice");
        }
    }

    @Test
    void confirmingWritesTheModelInUseIntoTheFormAndStillNeedsThePageSave() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            harness.runProviderAction("llm.provider.edit");
            LayerDriver picker = harness.openPickerLayer();
            picker.addCustomModel(CUSTOM_MODEL);
            harness.typeEditorText("llm.editor.api_key", TYPED_KEY);

            assertEquals(0, harness.access().saveCount(), "nothing is written while the layer is open");

            picker.click(LlmModelPickerLayers.ACTION_CONFIRM);

            assertEquals(CUSTOM_MODEL, harness.editorStatusValue(EDITOR_MODEL_ROW),
                    "确认 puts the checked-out model into the form");
            assertEquals(0, harness.access().saveCount(), "the form is draft state, not configuration");

            SettingsValidationResult validation = harness.session().validate();
            assertTrue(validation.success(), () -> "the page save refused the confirmed model: "
                    + validation.message().value());
            assertTrue(harness.session().save().success());

            LlmModelEntry stored = harness.firstProvider();
            assertEquals(CUSTOM_MODEL, stored.activeModelId(), "the confirmed model is the active one");
            assertTrue(stored.models().stream().anyMatch(spec -> spec.modelId().equals(CUSTOM_MODEL)),
                    "and it is part of the stored catalog");
            assertEquals(1, harness.access().saveCount());
        }
    }

    @Test
    void cancellingEndsTheSessionWithoutChangingTheForm() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            harness.runProviderAction("llm.provider.edit");
            String original = harness.editorStatusValue(EDITOR_MODEL_ROW);

            LayerDriver picker = harness.openPickerLayer();
            picker.addCustomModel(CUSTOM_MODEL);
            picker.click(LlmModelPickerLayers.ACTION_CANCEL);

            assertEquals(original, harness.editorStatusValue(EDITOR_MODEL_ROW),
                    "取消 leaves the model in use alone");

            LayerDriver reopened = harness.openPickerLayer();
            assertEquals(2, harness.layerHost().opens(), "the cancelled session ended, so a new layer can open");
            assertFalse(reopened.rows().contains(CUSTOM_MODEL), "and the model it added went with it");
        }
    }

    @Test
    void anUnconfirmedSelectionIsNotPartOfThePageSave() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            harness.runProviderAction("llm.provider.edit");
            String original = harness.editorStatusValue(EDITOR_MODEL_ROW);
            harness.typeEditorText("llm.editor.api_key", TYPED_KEY);
            LayerDriver picker = harness.openPickerLayer();
            picker.addCustomModel(CUSTOM_MODEL);

            assertTrue(harness.session().save().success(), "the page save itself still finishes the form");

            LlmModelEntry stored = harness.firstProvider();
            assertEquals(original, stored.activeModelId(),
                    "a selection the player never confirmed is not state and is not saved");
            assertFalse(stored.models().stream().anyMatch(spec -> spec.modelId().equals(CUSTOM_MODEL)),
                    "and the model it added is not in the stored catalog either");
        }
    }

    @Test
    void fetchingUsesTheKeyTypedIntoTheFormBeforeAnythingIsSaved() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            harness.runProviderAction("llm.provider.edit");
            harness.typeEditorText("llm.editor.api_key", TYPED_KEY);
            LayerDriver picker = harness.openPickerLayer();

            picker.click(LlmModelPickerLayers.ACTION_FETCH);

            assertEquals(1, harness.catalog().requests().size(), "one fetch, one request");
            RecordingCatalogClient.Request request = harness.catalog().requests().get(0);
            assertEquals(TYPED_KEY, request.apiKey(),
                    "the credential typed into the form is spent even though nothing was applied");
            assertEquals(LlmModelCatalogKeySource.TYPED, request.keySource());
            assertTrue(harness.access().getLlmApiKey(LlmModelEntry.LEGACY_ID).isEmpty(),
                    "a fetch is not a save: it writes no credential");
            assertEquals(List.of("fetched-a", "fetched-b"), picker.rows(), "the answer fills the list");
            assertEquals("tianshu.gui.llm.catalog.fetched", picker.statusKey(),
                    "and the layer reports what it got");
        }
    }

    @Test
    void fetchingIsAttemptedWithoutAnyKeySoTheProviderDecides() throws Exception {
        try (Harness harness = new Harness(gameDirectory)) {
            harness.catalog().answer(LlmModelCatalogResult.failure(LlmModelCatalogState.UNAUTHORIZED, "401"));
            harness.runProviderAction("llm.provider.edit");
            LayerDriver picker = harness.openPickerLayer();

            picker.click(LlmModelPickerLayers.ACTION_FETCH);

            assertEquals(1, harness.catalog().requests().size(),
                    "a blank key is not a reason to skip the request");
            assertEquals("", harness.catalog().requests().get(0).apiKey());
            assertEquals(LlmModelCatalogKeySource.NONE, harness.catalog().requests().get(0).keySource());
            assertEquals("tianshu.gui.llm.catalog.unauthorized", picker.statusKey(),
                    "the provider's refusal is reported as a refusal");
            assertEquals(List.of("401"), picker.statusArguments());
        }
    }

    @Test
    void everyCatalogFailureRendersItsOwnSentence() throws Exception {
        Map<LlmModelCatalogState, String> expected = new LinkedHashMap<>();
        expected.put(LlmModelCatalogState.UNAUTHORIZED, "tianshu.gui.llm.catalog.unauthorized");
        expected.put(LlmModelCatalogState.HTTP_ERROR, "tianshu.gui.llm.catalog.http_error");
        expected.put(LlmModelCatalogState.MALFORMED_RESPONSE, "tianshu.gui.llm.catalog.malformed");
        expected.put(LlmModelCatalogState.NETWORK_ERROR, "tianshu.gui.llm.catalog.network_error");
        expected.put(LlmModelCatalogState.CREDENTIAL_NOT_SENDABLE,
                "tianshu.gui.llm.catalog.credential_not_sendable");
        expected.put(LlmModelCatalogState.MISSING_ENDPOINT, "tianshu.gui.llm.catalog.missing_endpoint");
        expected.put(LlmModelCatalogState.EMPTY, "tianshu.gui.llm.catalog.empty");

        for (Map.Entry<LlmModelCatalogState, String> entry : expected.entrySet()) {
            try (Harness harness = new Harness(gameDirectory)) {
                harness.catalog().answer(LlmModelCatalogResult.failure(entry.getKey(), "403"));
                harness.runProviderAction("llm.provider.edit");
                LayerDriver picker = harness.openPickerLayer();

                picker.click(LlmModelPickerLayers.ACTION_FETCH);

                assertEquals(entry.getValue(), picker.statusKey(),
                        "state " + entry.getKey() + " must read as itself");
            }
        }
    }

    @Test
    void validationSeparatesAKeyThatWasNeverTypedFromOneThatWasNotApplied() throws Exception {
        try (Harness harness = new Harness(gameDirectory, access -> {
            // Two providers: the active one has no credential, and the form is open on the other.
            access.seedProviders(List.of(
                    new LlmModelEntry("alpha", "alpha", "deepseek", "https://api.deepseek.com/v1",
                            List.of(new LlmModelSpec("model-alpha", "model-alpha", 8192)), "model-alpha"),
                    new LlmModelEntry("beta", "beta", "deepseek", "https://api.deepseek.com/v1",
                            List.of(new LlmModelSpec("model-beta", "model-beta", 8192)), "model-beta")));
        })) {
            harness.runProviderAction(1, "llm.provider.edit");

            assertEquals("tianshu.gui.llm.validation.missing_api_key",
                    harness.session().validate().message().value(),
                    "nothing was typed, so the player is asked for a key");

            harness.typeEditorText("llm.editor.api_key", TYPED_KEY);

            assertEquals("tianshu.gui.llm.validation.api_key_not_applied",
                    harness.session().validate().message().value(),
                    "the key belongs to another provider's form, so that form has to be saved first");
        }
    }

    /** Drives one described layer the way a window would: reads the list, types, clicks buttons. */
    private static final class LayerDriver {
        private final SettingsLayerModel layer;

        private LayerDriver(SettingsLayerModel layer) {
            this.layer = layer;
        }

        private String titleKey() {
            return layer.title().value();
        }

        private List<String> rows() {
            return list().visibleItems();
        }

        private String selected() {
            return list().currentSelection();
        }

        private void search(String term) {
            SettingsLayerElement.List.Search field = list().search();
            assertNotNull(field, "the picker list is not searchable");
            field.setTerm().accept(term);
        }

        private String customModelText() {
            return text(LlmModelPickerLayers.CUSTOM_MODEL).currentValue();
        }

        private void typeCustomModel(String value) {
            text(LlmModelPickerLayers.CUSTOM_MODEL).setter().accept(value);
        }

        private void addCustomModel(String value) {
            typeCustomModel(value);
            click(LlmModelPickerLayers.ACTION_ADD_CUSTOM);
        }

        private boolean actionEnabled(String actionId) {
            return action(actionId).isEnabled();
        }

        private void click(String actionId) {
            SettingsLayerElement.Action action = action(actionId);
            assertTrue(action.isEnabled(), () -> "action " + actionId + " is disabled");
            action.run().run();
            if (action.closesLayer()) {
                // A window runs the description's close hook after a closing action; mirror that here.
                layer.onClosed().run();
            }
        }

        private String statusKey() {
            return status().currentText().value();
        }

        private List<Object> statusArguments() {
            return status().currentText().arguments();
        }

        private SettingsLayerElement.List list() {
            SettingsLayerElement.List list = layer.element(LlmModelPickerLayers.MODELS,
                    SettingsLayerElement.List.class);
            assertNotNull(list, "the layer has no model list");
            return list;
        }

        private SettingsLayerElement.Text text(String id) {
            SettingsLayerElement.Text text = layer.element(id, SettingsLayerElement.Text.class);
            assertNotNull(text, "the layer has no text element " + id);
            return text;
        }

        private SettingsLayerElement.Status status() {
            SettingsLayerElement.Status status = layer.element(LlmModelPickerLayers.STATUS,
                    SettingsLayerElement.Status.class);
            assertNotNull(status, "the layer has no status line");
            return status;
        }

        private SettingsLayerElement.Action action(String actionId) {
            SettingsLayerElement.Actions actions = layer.element(LlmModelPickerLayers.ACTIONS,
                    SettingsLayerElement.Actions.class);
            assertNotNull(actions, "the layer has no action row");
            for (SettingsLayerElement.Action action : actions.actions()) {
                if (action.id().equals(actionId)) {
                    return action;
                }
            }
            return fail("the layer has no action " + actionId);
        }
    }

    /** The window layer as the page sees it: it records the description it was handed. */
    private static final class RecordingLayerHost implements SettingsLayerHost {
        private SettingsLayerModel layer;
        private int opens;

        @Override
        public void open(SettingsLayerModel layer) {
            this.layer = layer;
            this.opens++;
        }

        private SettingsLayerModel layer() {
            assertNotNull(layer, "no layer was opened");
            return layer;
        }

        private int opens() {
            return opens;
        }
    }

    /** The page plus everything it needs, built without a game runtime. */
    private static final class Harness implements AutoCloseable {
        private final FakeAccess access;
        private final ProtocolExecutorManager executors;
        private final RecordingCatalogClient catalog = new RecordingCatalogClient();
        private final RecordingLayerHost layerHost = new RecordingLayerHost();
        private final ModuleSettingsContext context;
        private final ModuleSettingsCategory category;

        private Harness(Path gameDirectory) {
            this(gameDirectory, null);
        }

        private Harness(Path gameDirectory, Consumer<FakeAccess> seed) {
            this.access = new FakeAccess(gameDirectory);
            if (seed != null) {
                seed.accept(access);
            }
            this.executors = new ProtocolExecutorManager(Runnable::run);
            LlmModuleService moduleService = new LlmModuleService(access, new LlmUsageLedger());
            moduleService.bindModelCatalogClient(catalog);
            LlmModelService modelService = new LlmModelService(new FakeGameEnvironment(gameDirectory), access, executors);
            SettingsCoordinator coordinator = new SettingsCoordinator();
            this.context = new ModuleSettingsContext() {
                @Override
                public SettingsCoordinator settingsCoordinator() {
                    return coordinator;
                }

                @Override
                public void showStatus(UiText message, long durationMillis) {
                }
            };
            TianshuSettingsRegistry registry = new TianshuSettingsRegistry();
            new LlmSettingsRegistrySource(null, access, DIRECT_SCHEDULER, NOOP_UI_HOST, layerHost)
                    .register(registry, context, moduleService, modelService);
            this.category = registry.find(MODULE_ID);
        }

        private FakeAccess access() {
            return access;
        }

        private RecordingCatalogClient catalog() {
            return catalog;
        }

        private RecordingLayerHost layerHost() {
            return layerHost;
        }

        private ModuleSettingsSession session() {
            return context.settingsSessions().find(MODULE_ID);
        }

        /** Presses 选择模型… and hands back a driver for the layer the page described. */
        private LayerDriver openPickerLayer() {
            runEditorAction("llm.editor.choose_model");
            return new LayerDriver(layerHost.layer());
        }

        private LlmModelEntry firstProvider() {
            SettingsTemplateModel.ListGroup<LlmModelEntry> providers = providers();
            assertNotNull(providers, "the page has no provider list");
            return providers.items().get().get(0);
        }

        /** The left column, which is where the providers, the add button and the editor live. */
        private List<SettingsTemplateModel> leftColumn() {
            for (SettingsTemplateModel template : templates()) {
                if (template instanceof SettingsTemplateModel.Columns columns) {
                    return columns.columns().get(0);
                }
            }
            return fail("the page has no columns layout");
        }

        private List<String> leftColumnIds() {
            return leftColumn().stream().map(SettingsTemplateModel::id).toList();
        }

        /** Top-level row ids in render order; a columns group counts as one row. */
        private List<String> topLevelIds() {
            return templates().stream().map(SettingsTemplateModel::id).toList();
        }

        /** Top-level row ids the renderer would actually draw, in order. */
        private List<String> visibleTopLevelIds() {
            return templates().stream()
                    .filter(template -> template.visible().getAsBoolean())
                    .map(SettingsTemplateModel::id)
                    .toList();
        }

        private boolean editorOpen() {
            SettingsTemplateModel.CompoundGroup editor = editor();
            return editor != null && editor.visible().getAsBoolean();
        }

        private SettingsTemplateModel.CompoundGroup editor() {
            return group(EDITOR_GROUP);
        }

        /** One compound section by id, or {@code null} when the page has none. */
        private SettingsTemplateModel.CompoundGroup group(String id) {
            SettingsTemplateModel template = template(id);
            return template instanceof SettingsTemplateModel.CompoundGroup compound ? compound : null;
        }

        /** The whole template tree, columns included, in render order. */
        private List<SettingsTemplateModel> allTemplates() {
            List<SettingsTemplateModel> flattened = new ArrayList<>();
            collect(templates(), flattened);
            return List.copyOf(flattened);
        }

        private static void collect(List<SettingsTemplateModel> templates, List<SettingsTemplateModel> into) {
            for (SettingsTemplateModel template : templates) {
                if (template == null) {
                    continue;
                }
                into.add(template);
                if (template instanceof SettingsTemplateModel.Columns columns) {
                    for (List<SettingsTemplateModel> column : columns.columns()) {
                        collect(column, into);
                    }
                }
            }
        }

        private SettingsTemplateModel template(String id) {
            for (SettingsTemplateModel template : allTemplates()) {
                if (template.id().equals(id)) {
                    return template;
                }
            }
            return null;
        }

        /** A fresh build of the page, exactly as the renderer rebuilds it on every refresh. */
        private List<SettingsTemplateModel> templates() {
            ModuleSettingsPanelModel panel = new ModuleSettingsPanelModel();
            category.panelFactory().build(panel, context);
            return panel.templates();
        }

        @SuppressWarnings("unchecked")
        private SettingsTemplateModel.ListGroup<LlmModelEntry> providers() {
            SettingsTemplateModel template = template(PROVIDERS_LIST);
            return template instanceof SettingsTemplateModel.ListGroup<?> list
                    ? (SettingsTemplateModel.ListGroup<LlmModelEntry>) list
                    : null;
        }

        private void runProviderAction(String actionId) {
            runProviderAction(0, actionId);
        }

        private void runProviderAction(int index, String actionId) {
            SettingsTemplateModel.ListGroup<LlmModelEntry> providers = providers();
            assertNotNull(providers, "the page has no provider list");
            LlmModelEntry entry = providers.items().get().get(index);
            for (SettingsTemplateModel.ItemActionEntry<LlmModelEntry> action : providers.itemActions().apply(entry)) {
                if (action.id().equals(actionId)) {
                    action.action().accept(entry);
                    return;
                }
            }
            fail("the provider card has no action " + actionId);
        }

        private void runEditorAction(String actionId) {
            SettingsTemplateModel.CompoundGroup editor = editor();
            assertNotNull(editor, "no editor section to act on");
            for (SettingsTemplateModel.ActionEntry action : editor.actions()) {
                if (action.id().equals(actionId)) {
                    assertTrue(action.enabled().getAsBoolean(), () -> actionId + " is disabled");
                    action.action().run();
                    return;
                }
            }
            fail("the editor has no action " + actionId);
        }

        /** Presses a button of the vector-model section: the download and the load position live there. */
        private void runEmbeddingAction(String actionId) {
            SettingsTemplateModel.CompoundGroup embedding = group(EMBEDDING_GROUP);
            assertNotNull(embedding, "no vector-model section to act on");
            for (SettingsTemplateModel.ActionEntry action : embedding.actions()) {
                if (action.id().equals(actionId)) {
                    assertTrue(action.enabled().getAsBoolean(), () -> actionId + " is disabled");
                    action.action().run();
                    return;
                }
            }
            fail("the vector model section has no action " + actionId);
        }

        /**
         * The language key of the position button's slot: the button is one sentence whose only variable is
         * the position, so the slot is exactly what the player reads.
         */
        private String positionArgumentKey() {
            SettingsTemplateModel.CompoundGroup embedding = group(EMBEDDING_GROUP);
            assertNotNull(embedding, "the vector model has a section of its own");
            for (SettingsTemplateModel.ActionEntry action : embedding.actions()) {
                if (action.id().equals("llm.embedding.position")) {
                    assertEquals("tianshu.gui.llm.action.embedding_position", action.label().value(),
                            "the button states the position in one slot");
                    return ((UiText) action.label().arguments().get(0)).value();
                }
            }
            return fail("the vector model section has no position button");
        }

        private String editorStatusValue(String rowId) {
            SettingsTemplateModel.CompoundGroup editor = editor();
            assertNotNull(editor, "no editor section to read");
            for (SettingsTemplateModel.StatusEntry entry : editor.statuses()) {
                if (entry.id().equals(rowId)) {
                    return entry.value().get().value();
                }
            }
            fail("the editor has no status row " + rowId);
            return "";
        }

        private void typeEditorText(String optionId, String value) {
            SettingsTemplateModel.CompoundGroup editor = editor();
            assertNotNull(editor, "no editor section to type into");
            for (SettingsTemplateModel.OptionEntry option : editor.options()) {
                if (option.id().equals(optionId) && option instanceof SettingsTemplateModel.TextEntry text) {
                    text.setter().accept(value);
                    return;
                }
            }
            fail("the editor has no text field " + optionId);
        }

        @Override
        public void close() {
            executors.close();
        }
    }

    /** The catalog seam as the page drives it: records what was asked and answers what a test set. */
    private static final class RecordingCatalogClient implements LlmModelCatalogClient {
        private final List<Request> requests = new ArrayList<>();
        private LlmModelCatalogResult answer = LlmModelCatalogResult.success(List.of("fetched-a", "fetched-b"));

        private void answer(LlmModelCatalogResult answer) {
            this.answer = answer;
        }

        private List<Request> requests() {
            return List.copyOf(requests);
        }

        @Override
        public void listModels(String baseUrl, String apiKey, LlmModelCatalogKeySource keySource,
                               java.util.function.Consumer<LlmModelCatalogResult> sink) {
            requests.add(new Request(baseUrl, apiKey, keySource));
            sink.accept(answer);
        }

        private record Request(String baseUrl, String apiKey, LlmModelCatalogKeySource keySource) {
        }
    }

    /**
     * The configuration port the page writes to. It only implements what the settings page touches, so a
     * test can assert what one save did to the profile.
     */
    private static final class FakeAccess implements LlmSettingsAccess {
        private final Path basePath;
        private final Map<String, String> apiKeys = new LinkedHashMap<>();
        private boolean enabled = true;
        private String customName = "";
        private List<LlmModelEntry> models;
        private String activeModelId = LlmModelEntry.LEGACY_ID;
        private int embeddingGpuLayers = LlmConfiguration.EMBEDDING_GPU_LAYERS_ALL;
        private int saves;

        private FakeAccess(Path basePath) {
            this.basePath = basePath;
        }

        /** Presets the configured providers, as a player who configured some before opening the page has. */
        private void seedProviders(List<LlmModelEntry> seeds) {
            this.models = List.copyOf(seeds);
            this.activeModelId = seeds.get(0).id();
        }

        private List<String> entryIds() {
            return getLlmModels().stream().map(LlmModelEntry::id).toList();
        }

        @Override
        public boolean isLlmEnabled() {
            return enabled;
        }

        @Override
        public void setLlmEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public String getCustomLlmName() {
            return customName;
        }

        @Override
        public void setCustomLlmName(String name) {
            this.customName = name == null ? "" : name;
        }

        @Override
        public Path getLlmBasePath() {
            return basePath;
        }

        @Override
        public void setLlmRemoteBaseUrl(String baseUrl) {
        }

        @Override
        public void setLlmRemoteApiKey(String apiKey) {
        }

        @Override
        public void setLlmRemoteModel(String model) {
        }

        @Override
        public List<LlmModelEntry> getLlmModels() {
            return models != null
                    ? models
                    : List.of(LlmModelEntry.legacy(getLlmRemoteBaseUrl(), getLlmRemoteModel(),
                            getLlmContextWindowTokens()));
        }

        @Override
        public void setLlmModels(List<LlmModelEntry> models) {
            this.models = models == null ? null : List.copyOf(models);
        }

        @Override
        public String getLlmActiveModelId() {
            return activeModelId;
        }

        @Override
        public void setLlmActiveModelId(String modelId) {
            this.activeModelId = modelId;
        }

        @Override
        public String getLlmApiKey(String modelId) {
            return apiKeys.getOrDefault(modelId, "");
        }

        @Override
        public void setLlmApiKey(String modelId, String apiKey) {
            if (apiKey == null || apiKey.isBlank()) {
                apiKeys.remove(modelId);
            } else {
                apiKeys.put(modelId, apiKey);
            }
        }

        @Override
        public int getLlmEmbeddingGpuLayers() {
            return embeddingGpuLayers;
        }

        @Override
        public void setLlmEmbeddingGpuLayers(int layers) {
            this.embeddingGpuLayers = layers;
        }

        @Override
        public void save() {
            saves++;
        }

        private int saveCount() {
            return saves;
        }
    }

    /** Only the game directory is real; the page must not need anything else. */
    private record FakeGameEnvironment(Path gameDirectory) implements IGameEnvironment {
        @Override
        public void displayMessageToPlayer(String message) {
        }

        @Override
        public void executeOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public Path getGameDirectory() {
            return gameDirectory;
        }

        @Override
        public boolean isClientSide() {
            return true;
        }

        @Override
        public void openFolder(Path dir) {
        }

        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void error(String msg, Throwable t) {
        }

        @Override
        public DiagnosticSink diagnostics() {
            return DiagnosticSink.NOOP;
        }
    }
}
