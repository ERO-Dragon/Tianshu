package com.rheinmetal.tianshu.client.settings.module.llm;

import com.rheinmetal.tianshu.client.api.settings.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerElement;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerElements;
import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerModel;
import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The model picker written in the framework's layer vocabulary.
 *
 * <p>This is the whole picker as far as the UI is concerned: one searchable list of the provider's
 * models, one field for a custom model id, four buttons and one status line. It holds no window, no
 * widget and no drawing — a {@link com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerHost}
 * decides how that description becomes a panel.
 *
 * <p>Every element reads the session on demand, so the picker stays live while the panel is open: typing
 * in the search field narrows the list on the next frame, a provider answer replaces it, and a button
 * greys out as soon as its action stops being possible.
 */
public final class LlmModelPickerLayers {
    /** Element ids, so a window (and a test) can address one part of the picker. */
    public static final String MODELS = "llm.picker.models";
    public static final String CUSTOM_MODEL = "llm.picker.custom_model";
    public static final String ACTIONS = "llm.picker.actions";
    public static final String STATUS = "llm.picker.status";

    /** Action ids, in the order the row shows them. */
    public static final String ACTION_FETCH = "llm.picker.fetch";
    public static final String ACTION_ADD_CUSTOM = "llm.picker.add_custom";
    public static final String ACTION_CANCEL = "llm.picker.cancel";
    public static final String ACTION_CONFIRM = "llm.picker.confirm";

    private static final BooleanSupplier ALWAYS = () -> true;

    private LlmModelPickerLayers() {
    }

    /**
     * The picker for one session.
     *
     * <p>The session is ended by the close hook, whichever way the layer is left: 确认 and 取消 do it
     * through their own callbacks first, and a layer closed by {@code Esc} or by the window going away
     * does it here. That way a session can never outlive its window and block the next 选择模型… click.
     */
    public static SettingsLayerModel of(LlmModelPickerSession session) {
        Objects.requireNonNull(session, "session");
        return SettingsLayerModel.of(
                        UiText.key("tianshu.gui.llm.section.model_picker"),
                        describeModels(session),
                        describeCustomModel(session),
                        describeActions(session),
                        SettingsLayerElements.status(STATUS, session::status))
                .closedBy(session::cancel);
    }

    private static SettingsLayerElement.List describeModels(LlmModelPickerSession session) {
        return SettingsLayerElements.searchableList(
                MODELS,
                UiText.key("tianshu.gui.llm.section.model_catalog"),
                UiText.key("tianshu.gui.llm.picker.empty"),
                session::modelIds,
                session::selectedModelId,
                session::select,
                UiText.key("tianshu.gui.llm.option.model_search"),
                session::search,
                session::search);
    }

    private static SettingsLayerElement.Text describeCustomModel(LlmModelPickerSession session) {
        UiText label = UiText.key("tianshu.gui.llm.option.custom_model_id");
        return SettingsLayerElements.text(CUSTOM_MODEL, label, label, session::customModelId, session::customModelId);
    }

    private static SettingsLayerElement.Actions describeActions(LlmModelPickerSession session) {
        return SettingsLayerElements.actions(
                ACTIONS,
                SettingsLayerElements.action(ACTION_FETCH,
                        UiText.key("tianshu.gui.llm.action.fetch_models"),
                        () -> !session.fetching(),
                        session::fetchModels),
                SettingsLayerElements.action(ACTION_ADD_CUSTOM,
                        UiText.key("tianshu.gui.llm.action.add_custom_model"),
                        session::canAddCustomModel,
                        session::addCustomModel),
                SettingsLayerElements.action(ACTION_CANCEL,
                        UiText.key("tianshu.gui.llm.action.cancel_picker"),
                        SettingsButtonStyle.NORMAL, ALWAYS, session::cancel, true),
                SettingsLayerElements.action(ACTION_CONFIRM,
                        UiText.key("tianshu.gui.llm.action.confirm_picker"),
                        SettingsButtonStyle.PRIMARY,
                        () -> !session.selectedModelId().isBlank(),
                        session::confirm,
                        true));
    }
}
