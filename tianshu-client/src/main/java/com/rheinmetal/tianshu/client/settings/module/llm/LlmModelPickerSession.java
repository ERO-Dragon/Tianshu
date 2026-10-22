package com.rheinmetal.tianshu.client.settings.module.llm;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.List;

/**
 * The model picker's state and intents, exactly as the picker layer needs them.
 *
 * <p>The window belongs to the framework, the state belongs to the settings page: this interface is the
 * seam between the two. The window never touches configuration, credentials or HTTP, and the page never
 * touches game UI — it only says what the picker currently holds and what each click means.
 *
 * <p>Every read returns the current state and every intent mutates it, so the layer description can bind
 * suppliers to these calls instead of copying them. That is what keeps the picker live: the search term
 * is written straight into this state, the framework's list applies it on the next frame, and the caret
 * never moves because no widget is rebuilt.
 *
 * <p>All methods are called on the client thread.
 */
public interface LlmModelPickerSession {
    /**
     * Every model id of the provider being edited, in catalog order.
     *
     * <p>Unfiltered on purpose: the search field of the layer narrows it, so this session cannot disagree
     * with other lists about what "matching" means.
     */
    List<String> modelIds();

    /** The model checked out in the picker; empty when none is. */
    String selectedModelId();

    /** Checks out one of {@link #modelIds()}; an id that is not in the catalog is ignored. */
    void select(String modelId);

    /** Current search term. */
    String search();

    /** Replaces the search term; the layer's list reflects it on the next frame, with no apply step. */
    void search(String term);

    /** Model id typed for a custom entry, pending {@link #addCustomModel()}. */
    String customModelId();

    void customModelId(String value);

    /** Whether the typed id can become a model of this provider: non-blank and not already listed. */
    boolean canAddCustomModel();

    /** Adds the typed id to the provider's catalog and checks it out. */
    void addCustomModel();

    /** Whether a provider request is in flight; the fetch action is disabled while it is. */
    boolean fetching();

    /**
     * Asks the provider for the models it serves.
     *
     * <p>Non-blocking: the answer arrives on the client thread and replaces {@link #modelIds()},
     * {@link #status()} and, when the checked-out model is gone, the selection.
     */
    void fetchModels();

    /** Text of the last provider answer — a count on success, the reason on failure; empty before any. */
    UiText status();

    /**
     * Writes the checked-out model into the provider form and ends this session.
     *
     * <p>The form is not saved: the model in use is draft state like every other field of the page, so the
     * page-level save is still what persists it. The caller closes the window afterwards.
     */
    void confirm();

    /** Ends this session without touching the form; the caller closes the window afterwards. */
    void cancel();
}
