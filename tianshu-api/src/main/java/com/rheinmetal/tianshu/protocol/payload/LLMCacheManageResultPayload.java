package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

public record LLMCacheManageResultPayload(
        String action,
        String uid,
        String entryId,
        boolean success,
        boolean exists,
        List<HitGroupPayload> hits,
        List<LibraryPayload> libraries,
        String errorMessage
) implements ITianshuPayload {

    public LLMCacheManageResultPayload {
        action = action != null ? action.trim().toUpperCase() : "";
        uid = uid != null ? uid.trim() : "";
        entryId = entryId != null ? entryId.trim() : "";
        hits = hits != null ? List.copyOf(hits) : List.of();
        libraries = libraries != null ? List.copyOf(libraries) : List.of();
        errorMessage = errorMessage != null && !errorMessage.isBlank() ? errorMessage.trim() : null;
    }

    public static LLMCacheManageResultPayload upserted(String uid, String entryId) {
        return new LLMCacheManageResultPayload("UPSERT_ENTRY", uid, entryId, true, true, List.of(), List.of(), null);
    }

    public static LLMCacheManageResultPayload patched(String uid, String entryId, boolean exists) {
        return new LLMCacheManageResultPayload("PATCH_ENTRY", uid, entryId, true, exists, List.of(), List.of(), null);
    }

    public static LLMCacheManageResultPayload deleted(String uid, String entryId) {
        return new LLMCacheManageResultPayload("DELETE_ENTRY", uid, entryId, true, false, List.of(), List.of(), null);
    }

    public static LLMCacheManageResultPayload cleared(String uid) {
        return new LLMCacheManageResultPayload("CLEAR_UID", uid, "", true, false, List.of(), List.of(), null);
    }

    public static LLMCacheManageResultPayload registered(LibraryPayload library) {
        return new LLMCacheManageResultPayload(
                "REGISTER_LIBRARY",
                library == null ? "" : library.uid(),
                "",
                true,
                true,
                List.of(),
                library == null ? List.of() : List.of(library),
                null
        );
    }

    public static LLMCacheManageResultPayload unregistered(String uid) {
        return new LLMCacheManageResultPayload("UNREGISTER_LIBRARY", uid, "", true, false, List.of(), List.of(), null);
    }

    public static LLMCacheManageResultPayload queried(String uid, boolean exists, LibraryPayload library) {
        return new LLMCacheManageResultPayload(
                "QUERY_UID",
                uid,
                "",
                true,
                exists,
                List.of(),
                library == null ? List.of() : List.of(library),
                null
        );
    }

    public static LLMCacheManageResultPayload searched(String action, String uid, List<HitGroupPayload> hits, List<LibraryPayload> libraries) {
        List<HitGroupPayload> safeHits = hits != null ? List.copyOf(hits) : List.of();
        return new LLMCacheManageResultPayload(
                action,
                uid,
                "",
                true,
                !safeHits.isEmpty(),
                safeHits,
                libraries != null ? List.copyOf(libraries) : List.of(),
                null
        );
    }

    public static LLMCacheManageResultPayload failed(String uid, String errorMessage) {
        return new LLMCacheManageResultPayload("FAILED", uid, "", false, false, List.of(), List.of(), errorMessage);
    }

    public record HitGroupPayload(
            String uid,
            List<HitEntryPayload> entries,
            double score
    ) implements ITianshuPayload {
        public HitGroupPayload {
            uid = uid != null ? uid.trim() : "";
            entries = entries != null ? List.copyOf(entries) : List.of();
            score = Double.isNaN(score) || Double.isInfinite(score) ? 0.0D : Math.max(0.0D, score);
        }

        public static HitGroupPayload of(String uid, List<HitEntryPayload> entries) {
            double score = entries == null || entries.isEmpty()
                    ? 0.0D
                    : entries.stream().mapToDouble(HitEntryPayload::score).max().orElse(0.0D);
            return new HitGroupPayload(uid, entries, score);
        }
    }

    public record HitEntryPayload(
            String entryId,
            String content,
            double score
    ) implements ITianshuPayload {
        public HitEntryPayload {
            entryId = entryId != null ? entryId.trim() : "";
            content = content != null ? content : "";
            score = Double.isNaN(score) || Double.isInfinite(score) ? 0.0D : Math.max(0.0D, score);
        }

        public static HitEntryPayload of(String entryId, String content, double score) {
            return new HitEntryPayload(entryId, content, score);
        }
    }

    public record LibraryPayload(
            String uid,
            String modid,
            String visibility,
            List<String> tags
    ) implements ITianshuPayload {
        public LibraryPayload {
            uid = uid != null ? uid.trim() : "";
            modid = modid != null ? modid.trim().toLowerCase() : "";
            visibility = visibility != null && !visibility.isBlank() ? visibility.trim().toUpperCase() : "SHARED";
            tags = tags != null ? List.copyOf(tags) : List.of();
        }

        public static LibraryPayload of(String uid, String modid, String visibility, List<String> tags) {
            return new LibraryPayload(uid, modid, visibility, tags);
        }
    }
}
