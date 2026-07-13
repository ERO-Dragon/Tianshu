package com.rheinmetal.tianshu.model;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelDownloadSourcePolicyTest {
    private final ModelDownloadSourcePolicy policy = new ModelDownloadSourcePolicy(
            "http://official.test",
            "http://mirror.test/"
    );

    @Test
    void buildsMirrorFirstHuggingFaceFileCandidatesWithSegmentEncoding() {
        assertEquals(List.of(
                URI.create("http://mirror.test/org/model/resolve/rev%20one/folder/file%20name.onnx"),
                URI.create("http://official.test/org/model/resolve/rev%20one/folder/file%20name.onnx")
        ), policy.huggingFaceFileCandidates(
                "http://mirror.test",
                "org/model",
                "rev one",
                "folder/file name.onnx"
        ));
    }

    @Test
    void buildsOfficialFirstTreeCandidatesAndPreservesRepoNamespace() {
        assertEquals(List.of(
                URI.create("http://official.test/api/models/org/model/tree/feature%2Fv2?recursive=true&expand=false"),
                URI.create("http://mirror.test/api/models/org/model/tree/feature%2Fv2?recursive=true&expand=false")
        ), policy.huggingFaceTreeCandidates(
                "http://official.test/",
                "org/model",
                "feature/v2"
        ));
    }

    @Test
    void githubProxyFirstStillKeepsDirectFallback() {
        assertEquals(List.of(
                URI.create("https://proxy.test/https://github.com/org/model/releases/download/v1/model.zip"),
                URI.create("https://github.com/org/model/releases/download/v1/model.zip")
        ), policy.githubArchiveCandidates(
                "https://github.com/org/model/releases/download/v1/model.zip",
                "https://proxy.test",
                true
        ));
    }

    @Test
    void githubDirectFirstStillKeepsConfiguredProxyFallback() {
        assertEquals(List.of(
                URI.create("https://github.com/org/model/archive/main.zip"),
                URI.create("https://proxy.test/https://github.com/org/model/archive/main.zip")
        ), policy.githubArchiveCandidates(
                "https://github.com/org/model/archive/main.zip",
                "https://proxy.test/",
                false
        ));
    }

    @Test
    void githubWithoutProxyHasOnlyDirectCandidate() {
        URI direct = URI.create("https://github.com/org/model/archive/main.zip");

        assertEquals(List.of(direct), policy.githubArchiveCandidates(direct.toString(), " ", true));
    }

    @Test
    void alreadyProxiedGithubUrlIsNotPrefixedAgain() {
        URI proxied = URI.create("https://proxy.test/https://github.com/org/model/archive/main.zip");

        assertEquals(List.of(proxied), policy.githubArchiveCandidates(
                proxied.toString(),
                "https://proxy.test/",
                true
        ));
    }

    @Test
    void rejectsRemotePathTraversalSegments() {
        assertThrows(IllegalArgumentException.class, () -> policy.huggingFaceFileCandidates(
                "http://official.test",
                "org/model",
                "main",
                "../secret.bin"
        ));
        assertThrows(IllegalArgumentException.class, () -> policy.huggingFaceTreeCandidates(
                "http://official.test",
                "org/../model",
                "main"
        ));
    }
}
