package com.rheinmetal.tianshu.function.llm.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage contract of the multi-model document: what a config file with only the legacy keys resolves
 * to, how the entries reach the file, and the one rule that matters most 鈥?the credential never shares
 * a value with an entry.
 */
class LlmModelSettingsTest {
    private static final String LEGACY_KEY = "sk-legacy-secret";
    private static final String FAST_KEY = "sk-fast-secret";
    private static final String DEEP_KEY = "sk-deep-secret";

    @Test
    void emptyStoreResolvesToTheSingleLegacyModel() {
        LlmModelSettings settings = legacySettings();

        assertFalse(settings.storedModelsPresent());
        assertEquals(1, settings.models().size());
        assertEquals(LlmModelEntry.LEGACY_ID, settings.activeModelId());
        assertEquals(LlmModelEntry.LEGACY_ID, settings.activeModel().id());
        assertEquals("https://api.deepseek.com/v1", settings.activeModel().baseUrl());
        assertEquals("deepseek-flash", settings.activeModel().modelId());
        assertEquals(102_400, settings.activeModel().contextWindowTokens());
        assertEquals(LEGACY_KEY, settings.apiKey(LlmModelEntry.LEGACY_ID));
        assertTrue(settings.modelLines().isEmpty(), "reading a legacy file must not invent a model list");
    }

    @Test
    void storedEntriesKeepTheirOrderAndTheSelectedId() {
        LlmModelSettings settings = twoEntries().withActiveModelId("fast");

        assertEquals(List.of("fast", "deep"), ids(settings.models()));
        assertEquals("fast", settings.activeModelId());
        assertEquals("https://fast.example/v1", settings.activeModel().baseUrl());
        assertEquals("deepseek-flash", settings.activeModel().modelId());
    }

    @Test
    void unknownOrBlankSelectionFallsBackToTheFirstEntry() {
        assertEquals("fast", twoEntries().withActiveModelId("retired").activeModelId());
        assertEquals("fast", twoEntries().withActiveModelId("").activeModelId());
        assertEquals("fast", twoEntries().withActiveModelId(null).activeModelId());
    }

    @Test
    void credentialsAreAddressedByIdAndOnlyTheLegacyIdBorrowsTheLegacyKey() {
        LlmModelSettings settings = twoEntries()
                .withApiKey("fast", FAST_KEY)
                .withApiKey("deep", DEEP_KEY);

        assertEquals(FAST_KEY, settings.apiKey("fast"));
        assertEquals(DEEP_KEY, settings.apiKey("deep"));
        assertEquals("", settings.apiKey("retired"), "an entry without a stored key must not borrow one");
        assertEquals("", settings.apiKey(null));
        assertEquals("", settings.apiKey("  "));
    }

    @Test
    void legacyKeyStillAnswersTheLegacyEntryOfAStoredList() {
        LlmModelSettings settings = LlmModelSettings.fromStored(
                List.of("default|DeepSeek Flash|deepseek|https://api.deepseek.com/v1|deepseek-flash|102400"),
                "default",
                List.of(),
                legacyEntry(),
                LEGACY_KEY);

        assertTrue(settings.storedModelsPresent());
        assertEquals(LEGACY_KEY, settings.apiKey(LlmModelEntry.LEGACY_ID));
    }

    @Test
    void clearingACredentialFallsBackToTheLegacyKeyOrNothing() {
        LlmModelSettings settings = twoEntries().withApiKey("fast", FAST_KEY);

        assertEquals("", settings.withApiKey("fast", "").apiKey("fast"));
        assertEquals("", settings.withApiKey("fast", null).apiKey("fast"));
        assertEquals(LEGACY_KEY, settings.withApiKey("default", "").apiKey("default"));
    }

    @Test
    void noSerialisedFormCarriesACredential() {
        LlmModelSettings settings = twoEntries()
                .withActiveModelId("fast")
                .withApiKey("fast", FAST_KEY)
                .withApiKey("deep", DEEP_KEY);

        assertTrue(settings.modelLines().stream().noneMatch(line -> line.contains(FAST_KEY)));
        assertTrue(settings.modelLines().stream().noneMatch(line -> line.contains(DEEP_KEY)));
        assertFalse(settings.toString().contains(FAST_KEY), "a log line must not leak a credential");
        assertFalse(settings.toString().contains(DEEP_KEY), "a log line must not leak a credential");
        assertTrue(settings.credentialLines().contains("fast=" + FAST_KEY), "the credential store is separate");
        settings.models().forEach(entry -> {
            assertFalse(entry.toString().contains(FAST_KEY));
            assertFalse(entry.toString().contains(DEEP_KEY));
        });
    }

    @Test
    void separatorInsideAFieldSurvivesTheRoundTrip() {
        LlmModelEntry awkward = new LlmModelEntry(
                "eu-west",
                "Fast | Europe",
                "azure|custom",
                "https://eu.example/v1",
                "deepseek-flash\\beta",
                51_200);
        LlmModelSettings written = legacySettings()
                .withModels(List.of(awkward))
                .withActiveModelId("eu-west");

        LlmModelSettings readBack = LlmModelSettings.fromStored(
                written.modelLines(), written.activeModelId(), written.credentialLines(), legacyEntry(), LEGACY_KEY);

        assertEquals(1, readBack.models().size());
        assertEquals("Fast | Europe", readBack.activeModel().displayName());
        assertEquals("azure|custom", readBack.activeModel().providerId());
        assertEquals("deepseek-flash\\beta", readBack.activeModel().modelId());
        assertEquals(51_200, readBack.activeModel().contextWindowTokens());
    }

    @Test
    void unreadableAndDuplicateLinesAreSkipped() {
        LlmModelSettings settings = LlmModelSettings.fromStored(
                List.of(
                        "fast|Fast|deepseek|https://fast.example/v1|deepseek-flash|51200",
                        "no-separators",
                        "bad-window|Bad|deepseek|https://bad.example/v1|deepseek-flash|not-a-number",
                        "|Unnamed|deepseek|https://unnamed.example/v1|deepseek-flash|51200",
                        "fast|Duplicate|deepseek|https://duplicate.example/v1|deepseek-flash|51200",
                        "",
                        "   "),
                "fast",
                List.of("fast=" + FAST_KEY, "no-separator-here", "=orphan", "illegal id=" + DEEP_KEY),
                legacyEntry(),
                LEGACY_KEY);

        assertEquals(List.of("fast"), ids(settings.models()));
        assertEquals("https://fast.example/v1", settings.activeModel().baseUrl());
        assertEquals(FAST_KEY, settings.apiKey("fast"));
    }

    @Test
    void writingTheMigratedListAndReadingItAgainChangesNothing() {
        LlmModelSettings legacy = legacySettings();
        LlmModelSettings migrated = legacy.withModels(legacy.models()).withLegacyCredentialAdopted();
        LlmModelSettings readBack = LlmModelSettings.fromStored(
                migrated.modelLines(), migrated.activeModelId(), migrated.credentialLines(), legacyEntry(), "");
        LlmModelSettings secondPass = LlmModelSettings.fromStored(
                readBack.modelLines(), readBack.activeModelId(), readBack.credentialLines(), legacyEntry(), "");

        assertTrue(migrated.storedModelsPresent());
        assertEquals(1, providerLines(migrated).size(), "the migration materialises exactly the synthesised provider");
        assertEquals(1, modelLines(migrated).size(), "and exactly the one model it serves");
        assertTrue(migrated.credentialLines().contains(LlmModelEntry.LEGACY_ID + "=" + LEGACY_KEY),
                "the adopted credential lives in the credential store, not in the entry");
        assertEquals(ids(legacy.models()), ids(readBack.models()));
        assertEquals(ids(readBack.models()), ids(secondPass.models()));
        assertEquals(legacy.activeModelId(), secondPass.activeModelId());
        assertEquals(readBack.modelLines(), secondPass.modelLines());
        assertEquals(readBack.credentialLines(), secondPass.credentialLines());
        assertEquals(LEGACY_KEY, secondPass.apiKey(LlmModelEntry.LEGACY_ID), "the key must survive every pass");
        assertEquals(LEGACY_KEY, secondPass.apiKey(secondPass.activeModelId()));
    }

    @Test
    void theLegacyCredentialIsAdoptedOnlyOnceTheListExists() {
        LlmModelSettings legacy = legacySettings();

        assertTrue(legacy.withLegacyCredentialAdopted().credentialLines().isEmpty(),
                "a profile without a list keeps its credential in the legacy key only");
        LlmModelSettings migrated = legacy.withModels(legacy.models()).withLegacyCredentialAdopted();
        assertEquals(List.of(LlmModelEntry.LEGACY_ID + "=" + LEGACY_KEY), migrated.credentialLines());
        assertEquals(migrated.credentialLines(), migrated.withLegacyCredentialAdopted().credentialLines(),
                "adopting twice must not duplicate the credential");
        assertTrue(migrated.modelLines().stream().noneMatch(line -> line.contains(LEGACY_KEY)));
    }

    @Test
    void replacingAnEntryKeepsTheListOrder() {
        LlmModelSettings replaced = twoEntries()
                .withActiveModelId("deep")
                .withEntry(twoEntries().models().get(0).withBaseUrl("https://moved.example/v1"));

        assertEquals(List.of("fast", "deep"), ids(replaced.models()));
        assertEquals("https://moved.example/v1", replaced.models().get(0).baseUrl());
        assertEquals("deep", replaced.activeModelId());

        LlmModelSettings appended = twoEntries().withEntry(new LlmModelEntry("new", null, null,
                "https://new.example/v1", "deepseek-flash", 0));
        assertEquals(List.of("fast", "deep", "new"), ids(appended.models()));
        assertEquals("new", appended.models().get(2).id());
        assertEquals(LlmModelEntry.CUSTOM_PROVIDER_ID, appended.models().get(2).providerId());
    }

    @Test
    void clearingTheListHandsControlBackToTheLegacyKeys() {
        LlmModelSettings cleared = twoEntries().withModels(List.of());

        assertFalse(cleared.storedModelsPresent());
        assertEquals(List.of(LlmModelEntry.LEGACY_ID), ids(cleared.models()));
        assertTrue(cleared.modelLines().isEmpty());
    }

    @Test
    void oneProviderServesSeveralModelsAndKeepsTheOneInUse() {
        LlmModelEntry entry = catalogSettings().activeModel();

        assertEquals(3, entry.models().size());
        assertEquals("deepseek-reasoner", entry.activeModelId());
        assertEquals("deepseek-reasoner", entry.modelId(), "modelId() reports the active model");
        assertEquals(204_800, entry.contextWindowTokens(), "the window belongs to the model");
        assertEquals("deepseek-flash", entry.models().get(0).modelId());
        assertEquals(51_200, entry.models().get(0).contextWindowTokens());
    }

    @Test
    void switchingTheActiveModelMovesTheSingleModelAccessors() {
        LlmModelEntry entry = catalogSettings().activeModel();

        LlmModelEntry flash = entry.withActiveModelId("deepseek-flash");
        assertEquals("deepseek-flash", flash.modelId());
        assertEquals(51_200, flash.contextWindowTokens());

        // An id the catalog does not serve resolves to the first model instead of inventing a model.
        assertEquals("deepseek-flash", entry.withActiveModelId("retired").modelId());
        assertEquals("deepseek-flash", entry.withActiveModelId(null).modelId());
    }

    @Test
    void renamingTheActiveModelFollowsItInTheCatalog() {
        LlmModelEntry renamed = catalogSettings().activeModel().withModelId("deepseek-reasoner-2");

        assertEquals("deepseek-reasoner-2", renamed.modelId());
        assertEquals(3, renamed.models().size());
        assertEquals("deepseek-reasoner-2", renamed.models().get(1).modelId());
        assertEquals("DeepSeek Reasoner", renamed.models().get(1).displayName(),
                "a custom display name survives the rename");

        // Pointing at a model the catalog already serves switches to it instead of renaming over it.
        LlmModelEntry switched = catalogSettings().activeModel().withModelId("deepseek-vl");
        assertEquals(3, switched.models().size());
        assertEquals("deepseek-vl", switched.modelId());
    }

    @Test
    void theNewLineFormatSurvivesARoundTrip() {
        LlmModelSettings written = catalogSettings();
        LlmModelSettings readBack = LlmModelSettings.fromStored(
                written.modelLines(), written.activeModelId(), written.credentialLines(), legacyEntry(), LEGACY_KEY);

        assertEquals(1, providerLines(readBack).size());
        assertEquals(3, modelLines(readBack).size());
        assertEquals(written.modelLines(), readBack.modelLines());
        assertEquals(entrySignature(written.activeModel()), entrySignature(readBack.activeModel()));
    }

    @Test
    void aLegacyLineReadsAsAProviderWithOneModel() {
        LlmModelSettings settings = LlmModelSettings.fromStored(
                List.of("eu-west|Europe|deepseek|https://eu.example/v1|deepseek-flash|51200"),
                "eu-west",
                List.of(),
                legacyEntry(),
                LEGACY_KEY);

        LlmModelEntry entry = settings.activeModel();
        assertEquals(1, entry.models().size());
        assertEquals("deepseek-flash", entry.models().get(0).modelId());
        assertEquals("deepseek-flash", entry.models().get(0).displayName());
        assertEquals(51_200, entry.models().get(0).contextWindowTokens());
        assertEquals("deepseek-flash", entry.activeModelId());
        assertEquals("deepseek-flash", entry.modelId(), "the only model of that provider is its active one");
    }

    @Test
    void aModelLineNamingAnUnknownProviderIsDropped() {
        LlmModelSettings settings = LlmModelSettings.fromStored(
                List.of(
                        "@entry|known|Known|deepseek|https://known.example/v1|deepseek-flash",
                        "@model|known|deepseek-flash|Flash|51200",
                        "@model|orphan|deepseek-chat|Chat|51200"),
                "known",
                List.of(),
                legacyEntry(),
                LEGACY_KEY);

        assertEquals(List.of("known"), ids(settings.models()));
        assertEquals(List.of("deepseek-flash"), modelIdsOf(settings.models().get(0)));
    }

    @Test
    void aModelLineThatStillCarriesTheRetiredVisionColumnIsReadWithoutIt() {
        LlmModelSettings settings = LlmModelSettings.fromStored(
                List.of("@entry|known|Known|deepseek|https://known.example/v1|deepseek-reasoner",
                        "@model|known|deepseek-flash|Flash|false|51200",
                        "@model|known|deepseek-reasoner|Reasoner|maybe|204800"),
                "known", List.of(), legacyEntry(), LEGACY_KEY);

        LlmModelEntry entry = settings.models().get(0);
        assertEquals(List.of("deepseek-flash", "deepseek-reasoner"), modelIdsOf(entry),
                "the retired column must never cost a model");
        assertEquals(51_200, entry.models().get(0).contextWindowTokens());
        assertEquals(204_800, entry.models().get(1).contextWindowTokens(),
                "an unreadable vision column is skipped, not read as the window");
        assertEquals("Flash", entry.models().get(0).displayName());
    }

    @Test
    void rewritingModelsDropsTheRetiredVisionColumn() {
        String modelLine = modelLines(catalogSettings()).get(0);

        assertEquals("@model|deepseek|deepseek-flash|DeepSeek Flash|51200", modelLine,
                "the stored form no longer has an image-capability column");
    }

    @Test
    void aProviderLineWithoutModelsFallsBackToTheDefaultModel() {
        LlmModelSettings settings = LlmModelSettings.fromStored(
                List.of("@entry|known|Known|deepseek|https://known.example/v1|deepseek-flash"),
                "known", List.of(), legacyEntry(), LEGACY_KEY);

        LlmModelEntry entry = settings.models().get(0);
        assertEquals(1, entry.models().size(), "a provider always has a model to talk to");
        assertEquals(LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL, entry.modelId());
    }

    /** A config file that only knows the single-model keys: no model list and no credential store. */
    private static LlmModelSettings legacySettings() {
        return LlmModelSettings.fromStored(List.of(), "", List.of(), legacyEntry(), LEGACY_KEY);
    }    private static LlmModelSettings twoEntries() {
        return LlmModelSettings.fromStored(
                List.of(
                        "fast|DeepSeek Flash|deepseek|https://fast.example/v1|deepseek-flash|51200",
                        "deep|DeepSeek Reasoner|deepseek|https://deep.example/v1|deepseek-reasoner|204800"),
                "fast",
                List.of(),
                legacyEntry(),
                LEGACY_KEY);
    }

    private static LlmModelEntry legacyEntry() {
        return LlmModelEntry.legacy("https://api.deepseek.com/v1", "deepseek-flash", 102_400);
    }

    private static List<String> ids(List<LlmModelEntry> entries) {
        return entries.stream().map(LlmModelEntry::id).toList();
    }

    /** A document in the current two-line-kind format: one provider serving three models. */
    private static LlmModelSettings catalogSettings() {
        return LlmModelSettings.fromStored(
                List.of(
                        "@entry|deepseek|DeepSeek|deepseek|https://api.deepseek.com/v1|deepseek-reasoner",
                        "@model|deepseek|deepseek-flash|DeepSeek Flash|51200",
                        "@model|deepseek|deepseek-reasoner|DeepSeek Reasoner|204800",
                        "@model|deepseek|deepseek-vl|DeepSeek VL|65536"),
                "deepseek",
                List.of("deepseek=" + DEEP_KEY),
                legacyEntry(),
                LEGACY_KEY);
    }

    private static List<String> providerLines(LlmModelSettings settings) {
        return settings.modelLines().stream().filter(line -> line.startsWith("@entry")).toList();
    }

    private static List<String> modelLines(LlmModelSettings settings) {
        return settings.modelLines().stream().filter(line -> line.startsWith("@model")).toList();
    }

    private static List<String> modelIdsOf(LlmModelEntry entry) {
        return entry.models().stream().map(LlmModelSpec::modelId).toList();
    }

    /** Everything one provider carries: what a round trip has to reproduce exactly. */
    private static List<String> entrySignature(LlmModelEntry entry) {
        List<String> signature = new ArrayList<>();
        signature.add(entry.id() + "|" + entry.displayName() + "|" + entry.providerId() + "|"
                + entry.baseUrl() + "|" + entry.activeModelId());
        for (LlmModelSpec spec : entry.models()) {
            signature.add(spec.modelId() + "|" + spec.displayName() + "|" + spec.contextWindowTokens());
        }
        return signature;
    }
}
