package com.rheinmetal.tianshu.function.weaving.settings;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-model keys are the only thing an old config file has, so the multi-model view has to be
 * derivable from them without a rewrite. These tests pin that migration contract.
 */
class LlmModelEntryMigrationTest {
    @Test
    void legacyKeysSynthesiseExactlyOneEntry() {
        List<LlmModelEntry> models = config("https://api.deepseek.com/v1", "sk-legacy", "deepseek-flash", 102_400)
                .getLlmModels();

        assertEquals(1, models.size());
        LlmModelEntry entry = models.get(0);
        assertEquals(LlmModelEntry.LEGACY_ID, entry.id());
        assertEquals("https://api.deepseek.com/v1", entry.baseUrl());
        assertEquals("deepseek-flash", entry.modelId());
        assertEquals(102_400, entry.contextWindowTokens());
    }

    @Test
    void activeIdPointsAtTheLegacyEntry() {
        assertEquals(LlmModelEntry.LEGACY_ID, config("", "", "deepseek-flash", 102_400).getLlmActiveModelId());
    }

    @Test
    void apiKeyIsOnlyResolvableForTheActiveEntry() {
        LlmConfiguration config = config("https://api.deepseek.com/v1", "sk-legacy", "deepseek-flash", 102_400);

        assertEquals("sk-legacy", config.getLlmApiKey(LlmModelEntry.LEGACY_ID));
        assertEquals("", config.getLlmApiKey("some-other-model"));
    }

    @Test
    void entryNeverCarriesTheCredential() {
        LlmModelEntry entry = config("https://api.deepseek.com/v1", "sk-legacy", "deepseek-flash", 102_400)
                .getLlmModels()
                .get(0);

        assertTrue(!entry.toString().contains("sk-legacy"), "the credential must not reach a serialisable value");
    }

    @Test
    void blankFieldsFallBackInsteadOfProducingEmptyEndpoints() {
        LlmModelEntry entry = config("", "", "", 0).getLlmModels().get(0);

        assertEquals(LlmConfiguration.DEFAULT_LLM_REMOTE_BASE_URL, entry.baseUrl());
        assertEquals(LlmConfiguration.DEFAULT_LLM_REMOTE_MODEL, entry.modelId());
        assertEquals(LlmConfiguration.DEFAULT_LLM_CONTEXT_WINDOW_TOKENS, entry.contextWindowTokens());
    }

    @Test
    void storedModelListReplacesTheSynthesisedEntry() {
        TestModelConfiguration stored = new TestModelConfiguration(twoModels().withActiveModelId("reasoner"));

        assertEquals(List.of("chat", "reasoner"), ids(stored.getLlmModels()));
        assertEquals("reasoner", stored.getLlmActiveModelId());
    }

    @Test
    void singleModelGettersReportTheActiveEntry() {
        TestModelConfiguration config = new TestModelConfiguration(twoModels().withActiveModelId("chat"));

        assertEquals("https://chat.example/v1", config.getLlmRemoteBaseUrl());
        assertEquals("deepseek-flash", config.getLlmRemoteModel());
        assertEquals(51_200, config.getLlmContextWindowTokens());
        assertEquals("sk-chat", config.getLlmRemoteApiKey());

        config.use(twoModels().withActiveModelId("reasoner"));

        assertEquals("https://reasoner.example/v1", config.getLlmRemoteBaseUrl());
        assertEquals("deepseek-reasoner", config.getLlmRemoteModel());
        assertEquals(204_800, config.getLlmContextWindowTokens());
        assertEquals("sk-reasoner", config.getLlmRemoteApiKey());
        assertEquals("sk-chat", config.getLlmApiKey("chat"), "the inactive entry keeps its own credential");
    }

    @Test
    void theDefaultViewAndTheStoredDocumentAgreeOnALegacyProfile() {
        LlmConfiguration profile = config("https://api.deepseek.com/v1", "sk-legacy", "deepseek-flash", 102_400);
        LlmModelSettings stored = LlmModelSettings.fromStored(
                List.of(),
                "",
                List.of(),
                LlmModelEntry.legacy(
                        profile.getLlmRemoteBaseUrl(),
                        profile.getLlmRemoteModel(),
                        profile.getLlmContextWindowTokens()),
                profile.getLlmRemoteApiKey());

        assertEquals(ids(profile.getLlmModels()), ids(stored.models()));
        assertEquals(profile.getLlmActiveModelId(), stored.activeModelId());
        assertEquals(
                profile.getLlmApiKey(profile.getLlmActiveModelId()),
                stored.apiKey(stored.activeModelId()));
    }

    @Test
    void migratingALegacyProfileTwiceKeepsOneEntryAndItsKey() {
        LlmModelSettings legacy = LlmModelSettings.fromStored(
                List.of(),
                "",
                List.of(),
                LlmModelEntry.legacy("https://api.deepseek.com/v1", "deepseek-flash", 102_400),
                "sk-legacy");
        LlmModelSettings migrated = legacy.withModels(legacy.models()).withLegacyCredentialAdopted();
        LlmModelSettings firstPass = LlmModelSettings.fromStored(
                migrated.modelLines(),
                migrated.activeModelId(),
                migrated.credentialLines(),
                LlmModelEntry.legacy("", "", 0),
                "");
        LlmModelSettings secondPass = LlmModelSettings.fromStored(
                firstPass.modelLines(),
                firstPass.activeModelId(),
                firstPass.credentialLines(),
                LlmModelEntry.legacy("", "", 0),
                "");

        assertEquals(1, firstPass.models().size());
        assertEquals(List.of(LlmModelEntry.LEGACY_ID), ids(secondPass.models()));
        assertEquals(LlmModelEntry.LEGACY_ID, secondPass.activeModelId());
        assertEquals(firstPass.modelLines(), secondPass.modelLines(), "a second pass must not rewrite the list");
        assertEquals("https://api.deepseek.com/v1", secondPass.activeModel().baseUrl());
        assertEquals("deepseek-flash", secondPass.activeModel().modelId());
        assertEquals("sk-legacy", secondPass.apiKey(secondPass.activeModelId()), "the key must not be lost");
    }

    @Test
    void theRuntimeGettersFollowTheActiveModelOfTheActiveProvider() {
        LlmModelSettings document = catalogDocument();
        TestModelConfiguration config = new TestModelConfiguration(document);

        assertEquals("deepseek-reasoner", config.getLlmRemoteModel());
        assertEquals(204_800, config.getLlmContextWindowTokens());
        assertEquals("sk-deepseek", config.getLlmRemoteApiKey());

        config.use(document.withEntry(document.activeModel().withActiveModelId("deepseek-flash")));

        assertEquals("deepseek-flash", config.getLlmRemoteModel());
        assertEquals(51_200, config.getLlmContextWindowTokens());
        assertEquals("https://api.deepseek.com/v1", config.getLlmRemoteBaseUrl());
        assertEquals("sk-deepseek", config.getLlmRemoteApiKey(),
                "the credential belongs to the provider, so switching models keeps it");
    }

    @Test
    void anOldSingleModelLineIsUpgradedWhenWrittenAndTheUpgradeIsIdempotent() {
        LlmModelSettings legacyLine = LlmModelSettings.fromStored(
                List.of("eu-west|Europe|deepseek|https://eu.example/v1|deepseek-flash|51200"),
                "eu-west",
                List.of(),
                LlmModelEntry.legacy("", "", 0),
                "");
        LlmModelSettings rewritten = LlmModelSettings.fromStored(
                legacyLine.modelLines(), legacyLine.activeModelId(), legacyLine.credentialLines(),
                LlmModelEntry.legacy("", "", 0), "");
        LlmModelSettings secondPass = LlmModelSettings.fromStored(
                rewritten.modelLines(), rewritten.activeModelId(), rewritten.credentialLines(),
                LlmModelEntry.legacy("", "", 0), "");

        assertTrue(rewritten.modelLines().get(0).startsWith("@entry|eu-west|"), "writing upgrades the line");
        assertEquals(2, rewritten.modelLines().size(), "one provider line and one model line");
        assertEquals(rewritten.modelLines(), secondPass.modelLines(), "the upgrade must be idempotent");
        assertEquals("deepseek-flash", secondPass.activeModel().modelId());
        assertEquals(51_200, secondPass.activeModel().contextWindowTokens());
        assertEquals("https://eu.example/v1", secondPass.activeModel().baseUrl());
    }

    private static LlmModelSettings twoModels() {
        return LlmModelSettings.fromStored(
                List.of(
                        "chat|DeepSeek Flash|deepseek|https://chat.example/v1|deepseek-flash|51200",
                        "reasoner|DeepSeek Reasoner|deepseek|https://reasoner.example/v1|deepseek-reasoner|204800"),
                "chat",
                List.of("chat=sk-chat", "reasoner=sk-reasoner"),
                LlmModelEntry.legacy("https://api.deepseek.com/v1", "deepseek-flash", 102_400),
                "sk-legacy");
    }

    /** One provider serving two models, written in the current format. */
    private static LlmModelSettings catalogDocument() {
        return LlmModelSettings.fromStored(
                List.of(
                        "@entry|deepseek|DeepSeek|deepseek|https://api.deepseek.com/v1|deepseek-reasoner",
                        "@model|deepseek|deepseek-flash|DeepSeek Flash|51200",
                        "@model|deepseek|deepseek-reasoner|DeepSeek Reasoner|204800"),
                "deepseek",
                List.of("deepseek=sk-deepseek"),
                LlmModelEntry.legacy("https://api.deepseek.com/v1", "deepseek-flash", 102_400),
                "sk-legacy");
    }

    private static List<String> ids(List<LlmModelEntry> entries) {
        return entries.stream().map(LlmModelEntry::id).toList();
    }

    private static LlmConfiguration config(String baseUrl, String apiKey, String model, int window) {
        return new LlmConfiguration() {
            @Override
            public boolean isLlmEnabled() {
                return true;
            }

            @Override
            public String getCustomLlmName() {
                return "";
            }

            @Override
            public Path getLlmBasePath() {
                return Path.of(".");
            }

            @Override
            public String getLlmRemoteBaseUrl() {
                return baseUrl;
            }

            @Override
            public String getLlmRemoteApiKey() {
                return apiKey;
            }

            @Override
            public String getLlmRemoteModel() {
                return model;
            }

            @Override
            public int getLlmContextWindowTokens() {
                return window;
            }
        };
    }
}
