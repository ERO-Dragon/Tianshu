package com.rheinmetal.tianshu.function.auxilium.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AXMemoryStorageCompatibilityChecker {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXMemoryStorageCompatibilityChecker(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public AXMemoryStorageCompatibilityReport check(AXScope scope) {
        if (scope == null || !scope.writable() || layout == null || jsonStore == null) {
            return new AXMemoryStorageCompatibilityReport(false, false, List.of(
                    AXMemoryStorageCompatibilityReport.Issue.error("AX_MEMORY_SCOPE_NOT_WRITABLE", "scope is not writable")
            ));
        }
        Path path = layout.worldManifestFile(scope);
        if (!Files.isRegularFile(path)) {
            return AXMemoryStorageCompatibilityReport.missingManifest();
        }
        JsonObject manifest = jsonStore.readObject(path).orElse(null);
        if (manifest == null) {
            return new AXMemoryStorageCompatibilityReport(true, false, List.of(
                    AXMemoryStorageCompatibilityReport.Issue.error("AX_MEMORY_MANIFEST_UNREADABLE", "manifest is not valid json")
            ));
        }
        List<AXMemoryStorageCompatibilityReport.Issue> issues = new ArrayList<>();
        checkIdentity(manifest, issues);
        checkSchemas(manifest, issues);
        checkFiles(manifest, issues);
        checkDerivedArtifacts(manifest, issues);
        return new AXMemoryStorageCompatibilityReport(true, true, issues);
    }

    private void checkIdentity(JsonObject manifest, List<AXMemoryStorageCompatibilityReport.Issue> issues) {
        String kind = readString(manifest, "kind");
        if (kind.isBlank()) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_MANIFEST_KIND_MISSING", "manifest kind is missing"));
        } else if (!"ax.world_memory".equals(kind)) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.error("AX_MEMORY_MANIFEST_KIND_UNSUPPORTED", "manifest kind is not ax.world_memory"));
        }
        checkVersion(
                manifest,
                "manifestSchemaVersion",
                AXMemoryStorageManifestStore.MANIFEST_SCHEMA_VERSION,
                "AX_MEMORY_MANIFEST_SCHEMA_MISSING",
                "AX_MEMORY_MANIFEST_SCHEMA_FUTURE",
                issues
        );
        checkVersion(
                manifest,
                "layoutVersion",
                AXMemoryStorageManifestStore.LAYOUT_VERSION,
                "AX_MEMORY_LAYOUT_VERSION_MISSING",
                "AX_MEMORY_LAYOUT_VERSION_FUTURE",
                issues
        );
    }

    private void checkSchemas(JsonObject manifest, List<AXMemoryStorageCompatibilityReport.Issue> issues) {
        JsonObject schemas = object(manifest, "schemas");
        if (schemas == null) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_SCHEMAS_MISSING", "schema declarations are missing"));
            return;
        }
        for (Map.Entry<String, Integer> schema : AXMemoryStorageManifestStore.currentSchemas().entrySet()) {
            checkVersion(
                    schemas,
                    schema.getKey(),
                    schema.getValue(),
                    "AX_MEMORY_SCHEMA_MISSING_" + schema.getKey(),
                    "AX_MEMORY_SCHEMA_FUTURE_" + schema.getKey(),
                    issues
            );
        }
    }

    private void checkFiles(JsonObject manifest, List<AXMemoryStorageCompatibilityReport.Issue> issues) {
        JsonObject files = object(manifest, "files");
        if (files == null) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_FILES_MISSING", "file declarations are missing"));
            return;
        }
        for (Map.Entry<String, String> file : AXMemoryStorageManifestStore.requiredFiles().entrySet()) {
            String declared = readString(files, file.getKey());
            if (declared.isBlank()) {
                issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_FILE_MISSING_" + file.getKey(), "file declaration is missing"));
            } else if (!file.getValue().equals(declared)) {
                issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_FILE_PATH_DIFFERS_" + file.getKey(), "file declaration differs from current layout"));
            }
        }
        JsonArray appendOnly = array(files, "appendOnly");
        if (appendOnly == null) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_APPEND_ONLY_MISSING", "append-only file set is missing"));
            return;
        }
        for (String key : AXMemoryStorageManifestStore.appendOnlyFileKeys()) {
            if (!arrayContains(appendOnly, key)) {
                issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_APPEND_ONLY_FILE_MISSING_" + key, "append-only declaration is incomplete"));
            }
        }
    }

    private void checkDerivedArtifacts(JsonObject manifest, List<AXMemoryStorageCompatibilityReport.Issue> issues) {
        JsonObject derived = object(manifest, "derivedArtifacts");
        if (derived == null) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_DERIVED_ARTIFACTS_MISSING", "derived artifact declarations are missing"));
            return;
        }
        if (readBoolean(derived, "authority", false)) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.error("AX_MEMORY_DERIVED_ARTIFACTS_AUTHORITY", "derived artifacts must not be authority data"));
        }
        JsonArray rebuildable = array(derived, "rebuildable");
        if (rebuildable == null) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_DERIVED_REBUILDABLE_MISSING", "rebuildable derived artifact set is missing"));
            return;
        }
        for (String key : AXMemoryStorageManifestStore.rebuildableArtifactKeys()) {
            if (!arrayContains(rebuildable, key)) {
                issues.add(AXMemoryStorageCompatibilityReport.Issue.warning("AX_MEMORY_DERIVED_REBUILDABLE_MISSING_" + key, "derived artifact is not declared rebuildable"));
            }
        }
    }

    private void checkVersion(
            JsonObject object,
            String key,
            int current,
            String missingCode,
            String futureCode,
            List<AXMemoryStorageCompatibilityReport.Issue> issues
    ) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.warning(missingCode, key + " is missing"));
            return;
        }
        int version = readInt(object, key, -1);
        if (version < 1) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.error(missingCode, key + " is invalid"));
        } else if (version > current) {
            issues.add(AXMemoryStorageCompatibilityReport.Issue.error(futureCode, key + " is newer than this AX build supports"));
        }
    }

    private JsonObject object(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : null;
    }

    private JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : null;
    }

    private boolean arrayContains(JsonArray array, String value) {
        for (JsonElement element : array) {
            if (element != null && !element.isJsonNull() && value.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private String readString(JsonObject json, String key) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private int readInt(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean readBoolean(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
