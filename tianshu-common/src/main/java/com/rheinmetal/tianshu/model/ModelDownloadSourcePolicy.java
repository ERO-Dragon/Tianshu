package com.rheinmetal.tianshu.model;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Builds deterministic model-download source candidates without performing I/O. */
final class ModelDownloadSourcePolicy {
    private final String hfOfficialBase;
    private final String hfMirrorBase;

    ModelDownloadSourcePolicy(String hfOfficialBase, String hfMirrorBase) {
        this.hfOfficialBase = normalizeBase(hfOfficialBase, "hfOfficialBase");
        this.hfMirrorBase = normalizeBase(hfMirrorBase, "hfMirrorBase");
    }

    List<URI> huggingFaceTreeCandidates(String preferredBase, String repoId, String revision) {
        String encodedRepo = encodePath(repoId, "repoId");
        String encodedRevision = encodeSegment(revision, "revision");
        List<URI> candidates = new ArrayList<>();
        for (String base : huggingFaceBases(preferredBase)) {
            candidates.add(URI.create(
                    base + "/api/models/" + encodedRepo + "/tree/" + encodedRevision
                            + "?recursive=true&expand=false"
            ));
        }
        return List.copyOf(candidates);
    }

    List<URI> huggingFaceFileCandidates(
            String preferredBase,
            String repoId,
            String revision,
            String filePath
    ) {
        String encodedRepo = encodePath(repoId, "repoId");
        String encodedRevision = encodeSegment(revision, "revision");
        String encodedFilePath = encodePath(filePath, "filePath");
        List<URI> candidates = new ArrayList<>();
        for (String base : huggingFaceBases(preferredBase)) {
            candidates.add(URI.create(
                    base + "/" + encodedRepo + "/resolve/" + encodedRevision + "/" + encodedFilePath
            ));
        }
        return List.copyOf(candidates);
    }

    static List<URI> githubArchiveCandidates(String directUrl, String proxyBaseUrl, boolean proxyFirst) {
        String direct = requireNonBlank(directUrl, "directUrl");
        String proxyBase = proxyBaseUrl == null || proxyBaseUrl.isBlank()
                ? ""
                : normalizeBase(proxyBaseUrl, "proxyBaseUrl") + "/";
        String proxied = proxyBase.isEmpty() || direct.startsWith(proxyBase)
                ? direct
                : proxyBase + direct;

        Set<URI> ordered = new LinkedHashSet<>();
        if (proxyFirst && !proxyBase.isEmpty()) {
            ordered.add(URI.create(proxied));
            ordered.add(URI.create(direct));
        } else {
            ordered.add(URI.create(direct));
            if (!proxyBase.isEmpty()) {
                ordered.add(URI.create(proxied));
            }
        }
        return List.copyOf(ordered);
    }

    private List<String> huggingFaceBases(String preferredBase) {
        Set<String> ordered = new LinkedHashSet<>();
        if (preferredBase != null && !preferredBase.isBlank()) {
            ordered.add(normalizeBase(preferredBase, "preferredBase"));
        }
        ordered.add(hfOfficialBase);
        ordered.add(hfMirrorBase);
        return List.copyOf(ordered);
    }

    private static String encodePath(String value, String name) {
        String path = requireNonBlank(value, name).replace('\\', '/');
        String[] segments = path.split("/", -1);
        List<String> encoded = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException(name + " contains an empty path segment");
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(name + " contains a traversal path segment");
            }
            encoded.add(encodeSegment(segment, name));
        }
        return String.join("/", encoded);
    }

    private static String encodeSegment(String value, String name) {
        return URLEncoder.encode(requireNonBlank(value, name), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String normalizeBase(String value, String name) {
        String base = requireNonBlank(value, name);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri = URI.create(base);
        String scheme = uri.getScheme();
        if (uri.getHost() == null
                || (!("http".equalsIgnoreCase(scheme)) && !("https".equalsIgnoreCase(scheme)))) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URL");
        }
        return base;
    }

    private static String requireNonBlank(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
