package com.rheinmetal.tianshu.function.weaving.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The persisted multi-model document: the configured providers, which one is active, the models each
 * provider serves and the credentials keyed by provider id.
 *
 * <p>The credential is stored <em>next to</em> the entries, never inside one:
 * {@link LlmModelEntry} cannot carry it, {@link #modelLines()} cannot write it, and
 * {@link #toString()} reports ids only. That way an entry, a model list or a debug line can never leak
 * a key.
 *
 * <p>Everything here is pure and MC-free on purpose. The loader layer only hands over the raw strings
 * it reads from its config file and writes back what this class serialises, which keeps the storage
 * format and the migration rules testable without a game runtime.
 *
 * <h2>Line format</h2>
 *
 * <p>One key holds several line kinds, each field escaped and separated by {@code |}:
 * <ul>
 *   <li>{@code @entry|id|displayName|providerId|baseUrl|activeModelId} — one provider.</li>
 *   <li>{@code @model|entryId|modelId|displayName|contextWindowTokens} — one model of that
 *       provider.</li>
 * </ul>
 *
 * <p>Both kinds start with a tag, and an entry id may not start with {@code @} (see
 * {@link #ID_PATTERN}), so a tagged line can never be mistaken for the pre-list format below. The tag
 * is what makes the format forward compatible: a line kind this build does not know is skipped
 * instead of being read as something else.
 *
 * <h2>Migration</h2>
 *
 * <p>Migration is a <em>read</em>, twice over:
 * <ul>
 *   <li>A line in the old single-model format
 *       ({@code id|displayName|providerId|baseUrl|modelId|contextWindowTokens}) is read as a provider
 *       with exactly one model, whose id, display name and window are taken from that line.</li>
 *   <li>A document whose model list is empty resolves to the one provider synthesised from the legacy
 *       single-model keys.</li>
 *   <li>A model line that still carries the retired image-capability column
 *       ({@code @model|entryId|modelId|displayName|vision|contextWindowTokens}) is read with that
 *       column ignored, so a file written before the column was dropped keeps every model.</li>
 * </ul>
 *
 * <p>Resolving either document again yields the same providers, the same models, the same active ids
 * and the same credential, so running the migration any number of times is a no-op — and the legacy
 * keys stay where they are, so nothing can be lost on the way.
 */
public final class LlmModelSettings {
    /** Separator between the fields of one serialised line. */
    private static final char FIELD_SEPARATOR = '|';
    /** Escapes a separator or itself inside a text field, so any value survives a round trip. */
    private static final char FIELD_ESCAPE = '\\';
    /** Tag of a provider line; a provider id may not start with it, so no line is ambiguous. */
    private static final String ENTRY_TAG = "@entry";
    /** Tag of a model line. */
    private static final String MODEL_TAG = "@model";
    /** {@code @entry|id|displayName|providerId|baseUrl|activeModelId}. */
    private static final int ENTRY_FIELD_COUNT = 6;
    /** {@code @model|entryId|modelId|displayName|contextWindowTokens}. */
    private static final int MODEL_FIELD_COUNT = 5;
    /**
     * The model line as it was written before the image-capability column was retired:
     * {@code @model|entryId|modelId|displayName|vision|contextWindowTokens}. Only read, never written.
     */
    private static final int LEGACY_MODEL_FIELD_COUNT = 6;
    /** {@code id|displayName|providerId|baseUrl|modelId|contextWindowTokens}: the pre-list format. */
    private static final int LEGACY_FIELD_COUNT = 6;
    /** Separator between the id and the credential in one serialised credential. */
    private static final char CREDENTIAL_SEPARATOR = '=';

    /**
     * An id has to survive both line formats, so it may not contain a separator, and it has to be
     * usable as a map key that a player can type. The first character is never punctuation, which is
     * also what keeps {@code @entry} / {@code @model} from ever colliding with a real id.
     */
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final List<LlmModelEntry> storedEntries;
    private final LlmModelEntry legacyEntry;
    private final String requestedActiveId;
    private final Map<String, String> credentials;
    private final String legacyApiKey;

    private LlmModelSettings(List<LlmModelEntry> storedEntries, LlmModelEntry legacyEntry, String requestedActiveId,
                             Map<String, String> credentials, String legacyApiKey) {
        this.storedEntries = storedEntries;
        this.legacyEntry = legacyEntry;
        this.requestedActiveId = requestedActiveId;
        this.credentials = credentials;
        this.legacyApiKey = legacyApiKey;
    }

    /**
     * Reads the persisted document.
     *
     * @param modelLines       serialised providers and their models, empty when the config file predates
     *                         the provider list
     * @param storedActiveId   the provider id the config file names, may be blank or unknown
     * @param credentialLines  serialised credentials; a separate key, never part of {@code modelLines}
     * @param legacyEntry      provider synthesised from the legacy single-model keys
     * @param legacyApiKey     credential stored under the legacy single-model key
     */
    public static LlmModelSettings fromStored(List<? extends String> modelLines, String storedActiveId,
                                              List<? extends String> credentialLines,
                                              LlmModelEntry legacyEntry, String legacyApiKey) {
        LlmModelEntry fallbackEntry = legacyEntry == null
                ? LlmModelEntry.legacy(null, null, 0)
                : legacyEntry;
        return new LlmModelSettings(
                decodeEntries(modelLines),
                fallbackEntry,
                storedActiveId == null ? "" : storedActiveId.trim(),
                decodeCredentials(credentialLines),
                legacyApiKey == null ? "" : legacyApiKey.trim());
    }

    /** Every configured provider, in configuration order and never empty. */
    public List<LlmModelEntry> models() {
        return storedEntries.isEmpty() ? List.of(legacyEntry) : storedEntries;
    }

    /** Whether the config file carries a provider list, or whether the legacy keys are still in charge. */
    public boolean storedModelsPresent() {
        return !storedEntries.isEmpty();
    }

    /**
     * Id of the active <em>provider</em>; an unknown or missing selection falls back to the first one.
     * The active <em>model</em> is per provider and lives in {@link LlmModelEntry#activeModelId()}.
     */
    public String activeModelId() {
        if (storedEntries.isEmpty()) {
            return LlmModelEntry.LEGACY_ID;
        }
        for (LlmModelEntry entry : storedEntries) {
            if (entry.id().equals(requestedActiveId)) {
                return entry.id();
            }
        }
        return storedEntries.get(0).id();
    }

    /** The active provider, with the model it should talk to. */
    public LlmModelEntry activeModel() {
        String activeModelId = activeModelId();
        for (LlmModelEntry entry : models()) {
            if (entry.id().equals(activeModelId)) {
                return entry;
            }
        }
        return models().get(0);
    }

    /**
     * Credential of one provider: its own if it has one, otherwise the legacy single-model key, and only
     * for the provider the legacy keys migrate into. Any other id without a stored credential answers
     * empty, so a half-written provider list cannot borrow another provider's key.
     */
    public String apiKey(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "";
        }
        String trimmedId = modelId.trim();
        String dedicated = credentials.get(trimmedId);
        if (dedicated != null) {
            return dedicated;
        }
        return LlmModelEntry.LEGACY_ID.equals(trimmedId) ? legacyApiKey : "";
    }

    /**
     * Serialised providers and models for the model-list key: one {@code @entry} line per provider,
     * followed by one {@code @model} line per model it serves.
     */
    public List<String> modelLines() {
        List<String> lines = new ArrayList<>(storedEntries.size());
        for (LlmModelEntry entry : storedEntries) {
            lines.add(encodeEntry(entry));
            for (LlmModelSpec spec : entry.models()) {
                lines.add(encodeModel(entry.id(), spec));
            }
        }
        return lines;
    }

    /** Serialised credentials for the credential key, which is never the model-list key. */
    public List<String> credentialLines() {
        List<String> lines = new ArrayList<>(credentials.size());
        for (Map.Entry<String, String> credential : credentials.entrySet()) {
            lines.add(credential.getKey() + CREDENTIAL_SEPARATOR + credential.getValue());
        }
        return lines;
    }

    /** Replaces the stored provider list; an empty list hands control back to the legacy keys. */
    public LlmModelSettings withModels(List<LlmModelEntry> models) {
        return new LlmModelSettings(sanitizeEntries(models), legacyEntry, requestedActiveId, credentials,
                legacyApiKey);
    }

    /** Points the document at another provider; an id no entry carries is resolved to the first one. */
    public LlmModelSettings withActiveModelId(String modelId) {
        return new LlmModelSettings(storedEntries, legacyEntry, modelId == null ? "" : modelId.trim(), credentials,
                legacyApiKey);
    }

    /** Stores or clears the credential of one provider; a blank key means "this provider has none". */
    public LlmModelSettings withApiKey(String modelId, String apiKey) {
        if (modelId == null || modelId.isBlank()) {
            return this;
        }
        String trimmedId = modelId.trim();
        String trimmedKey = apiKey == null ? "" : apiKey.trim();
        Map<String, String> next = new LinkedHashMap<>(credentials);
        if (trimmedKey.isEmpty()) {
            next.remove(trimmedId);
        } else {
            next.put(trimmedId, trimmedKey);
        }
        return new LlmModelSettings(storedEntries, legacyEntry, requestedActiveId, freeze(next), legacyApiKey);
    }

    /** Replaces the stored provider that carries the same id; an unknown id is appended. */
    public LlmModelSettings withEntry(LlmModelEntry entry) {
        if (entry == null) {
            return this;
        }
        List<LlmModelEntry> next = new ArrayList<>(storedEntries);
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
        return new LlmModelSettings(sanitizeEntries(next), legacyEntry, requestedActiveId, credentials, legacyApiKey);
    }

    /**
     * Copies the legacy credential into the credential store under the legacy provider id once the
     * provider list is materialised, so a migrated profile no longer depends on the old key being kept
     * around. A profile without a list is left untouched: there the legacy key is still the only store.
     */
    public LlmModelSettings withLegacyCredentialAdopted() {
        if (!storedModelsPresent() || legacyApiKey.isEmpty()) {
            return this;
        }
        return withApiKey(LlmModelEntry.LEGACY_ID, legacyApiKey);
    }

    /**
     * Ids only: the credential map must never reach a log line, and this is the object most likely to
     * be handed to one.
     */
    @Override
    public String toString() {
        List<String> modelIds = new ArrayList<>(models().size());
        for (LlmModelEntry entry : models()) {
            modelIds.add(entry.id());
        }
        return "LlmModelSettings{models=" + modelIds
                + ", activeModelId=" + activeModelId()
                + ", credentials=" + credentials.keySet() + "}";
    }

    /**
     * Reads every line kind in one pass, then attaches the models to their provider.
     *
     * <p>Models are collected separately and attached afterwards so a hand-edited file may order the
     * lines freely. A model line naming a provider that is not in the file is dropped: it belongs to
     * nothing, and inventing a provider from it would create an endpoint nobody configured.
     */
    private static List<LlmModelEntry> decodeEntries(List<? extends String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        Map<String, ProviderLine> providers = new LinkedHashMap<>();
        List<ModelLine> modelLines = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            List<String> fields = splitFields(line);
            if (fields.isEmpty()) {
                continue;
            }
            String tag = fields.get(0).trim();
            if (ENTRY_TAG.equals(tag)) {
                ProviderLine provider = decodeProvider(fields);
                if (provider != null) {
                    providers.putIfAbsent(provider.id(), provider);
                }
            } else if (MODEL_TAG.equals(tag)) {
                ModelLine model = decodeModel(fields);
                if (model != null) {
                    modelLines.add(model);
                }
            } else if (fields.size() == LEGACY_FIELD_COUNT) {
                ProviderLine provider = decodeLegacyEntry(fields);
                if (provider != null) {
                    providers.putIfAbsent(provider.id(), provider);
                    // The old line is one provider *and* its only model, so it contributes both kinds.
                    modelLines.add(new ModelLine(provider.id(),
                            new LlmModelSpec(provider.activeModelId(), provider.activeModelId(),
                                    provider.legacyWindow())));
                }
            }
        }
        List<LlmModelEntry> decoded = new ArrayList<>(providers.size());
        for (ProviderLine provider : providers.values()) {
            decoded.add(provider.toEntry(modelsOf(provider, modelLines)));
        }
        return sanitizeEntries(decoded);
    }

    /** The models of one provider, in file order; an entry with none falls back to the defaults. */
    private static List<LlmModelSpec> modelsOf(ProviderLine provider, List<ModelLine> modelLines) {
        List<LlmModelSpec> specs = new ArrayList<>();
        for (ModelLine model : modelLines) {
            if (model.entryId().equals(provider.id())) {
                specs.add(model.spec());
            }
        }
        return specs;
    }

    /** A hand-edited config file is untrusted: an unreadable line is skipped, never repaired. */
    private static ProviderLine decodeProvider(List<String> fields) {
        if (fields.size() != ENTRY_FIELD_COUNT) {
            return null;
        }
        String id = fields.get(1).trim();
        if (!ID_PATTERN.matcher(id).matches()) {
            return null;
        }
        return new ProviderLine(id, fields.get(2), fields.get(3), fields.get(4), fields.get(5).trim(), 0);
    }

    /**
     * The pre-list format: id, display name, provider id, endpoint, the single model id and its window.
     * It reads as a provider serving exactly that one model, which is also its active one.
     */
    private static ProviderLine decodeLegacyEntry(List<String> fields) {
        String id = fields.get(0).trim();
        if (!ID_PATTERN.matcher(id).matches()) {
            return null;
        }
        Integer window = parseWindow(fields.get(LEGACY_FIELD_COUNT - 1));
        if (window == null) {
            return null;
        }
        String modelId = fields.get(4).trim();
        if (modelId.isEmpty()) {
            return null;
        }
        return new ProviderLine(id, fields.get(1), fields.get(2), fields.get(3), modelId, window);
    }

    /**
     * One model line. A line of the current width carries the window last; a line that still has the
     * retired image-capability column is read the same way, with that column skipped, so an existing
     * config file keeps its whole catalog instead of losing every model to a format it predates.
     */
    private static ModelLine decodeModel(List<String> fields) {
        if (fields.size() != MODEL_FIELD_COUNT && fields.size() != LEGACY_MODEL_FIELD_COUNT) {
            return null;
        }
        String entryId = fields.get(1).trim();
        String modelId = fields.get(2).trim();
        if (!ID_PATTERN.matcher(entryId).matches() || modelId.isEmpty()) {
            return null;
        }
        Integer window = parseWindow(fields.get(fields.size() - 1));
        if (window == null) {
            return null;
        }
        return new ModelLine(entryId, new LlmModelSpec(modelId, fields.get(3), window));
    }

    private static Integer parseWindow(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException unreadableWindow) {
            return null;
        }
    }

    private static String encodeEntry(LlmModelEntry entry) {
        return ENTRY_TAG + FIELD_SEPARATOR
                + escape(entry.id()) + FIELD_SEPARATOR
                + escape(entry.displayName()) + FIELD_SEPARATOR
                + escape(entry.providerId()) + FIELD_SEPARATOR
                + escape(entry.baseUrl()) + FIELD_SEPARATOR
                + escape(entry.activeModelId());
    }

    private static String encodeModel(String entryId, LlmModelSpec spec) {
        return MODEL_TAG + FIELD_SEPARATOR
                + escape(entryId) + FIELD_SEPARATOR
                + escape(spec.modelId()) + FIELD_SEPARATOR
                + escape(spec.displayName()) + FIELD_SEPARATOR
                + spec.contextWindowTokens();
    }

    private static Map<String, String> decodeCredentials(List<? extends String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Map.of();
        }
        Map<String, String> decoded = new LinkedHashMap<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            int separator = line.indexOf(CREDENTIAL_SEPARATOR);
            if (separator <= 0) {
                continue;
            }
            String id = line.substring(0, separator).trim();
            String apiKey = line.substring(separator + 1).trim();
            if (apiKey.isEmpty() || !ID_PATTERN.matcher(id).matches()) {
                continue;
            }
            decoded.put(id, apiKey);
        }
        return freeze(decoded);
    }

    /** One entry per id: the first occurrence wins, so a stale duplicate cannot shadow a live entry. */
    private static List<LlmModelEntry> sanitizeEntries(List<LlmModelEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, LlmModelEntry> unique = new LinkedHashMap<>();
        for (LlmModelEntry entry : entries) {
            if (entry != null && ID_PATTERN.matcher(entry.id()).matches()) {
                unique.putIfAbsent(entry.id(), entry);
            }
        }
        return List.copyOf(unique.values());
    }

    private static Map<String, String> freeze(Map<String, String> credentials) {
        return credentials.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(credentials));
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == FIELD_ESCAPE || character == FIELD_SEPARATOR) {
                escaped.append(FIELD_ESCAPE);
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    /** Splits on unescaped separators; an escaped character is taken verbatim. */
    private static List<String> splitFields(String line) {
        List<String> fields = new ArrayList<>(LEGACY_FIELD_COUNT);
        StringBuilder field = new StringBuilder(line.length());
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == FIELD_ESCAPE && index + 1 < line.length()) {
                field.append(line.charAt(++index));
                continue;
            }
            if (character == FIELD_SEPARATOR) {
                fields.add(field.toString());
                field.setLength(0);
                continue;
            }
            field.append(character);
        }
        fields.add(field.toString());
        return fields;
    }

    /** One decoded provider line plus the models read for it. */
    private record ProviderLine(String id, String displayName, String providerId, String baseUrl,
                                String activeModelId, int legacyWindow) {
        private LlmModelEntry toEntry(List<LlmModelSpec> models) {
            return new LlmModelEntry(id, displayName, providerId, baseUrl, models, activeModelId);
        }
    }

    /** One decoded model line. */
    private record ModelLine(String entryId, LlmModelSpec spec) {
    }
}
