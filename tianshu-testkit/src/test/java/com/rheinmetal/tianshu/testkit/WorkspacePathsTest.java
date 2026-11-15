package com.rheinmetal.tianshu.testkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspacePathsTest {

    @TempDir
    Path temp;

    @Test
    void aLinkedWorktreeResolvesTheDocRootOfItsMainWorktree() throws Exception {
        Path main = Files.createDirectories(temp.resolve("main"));
        Files.createDirectories(main.resolve(".git"));
        Files.createDirectories(main.resolve("doc"));
        Path linked = Files.createDirectories(temp.resolve("linked"));
        Path pointer = main.resolve(".git").resolve("worktrees").resolve("linked");
        Files.writeString(linked.resolve(".git"), "gitdir: " + pointer.toString().replace('\\', '/') + "\n");

        assertEquals(main.resolve("doc"), WorkspacePaths.docRoot(linked, null));
    }

    @Test
    void aLocalDocDirectoryWinsOverTheMainWorktreeCopy() throws Exception {
        Path main = Files.createDirectories(temp.resolve("main"));
        Files.createDirectories(main.resolve("doc"));
        Path linked = Files.createDirectories(temp.resolve("linked"));
        Files.createDirectories(linked.resolve("doc"));

        assertEquals(linked.resolve("doc"), WorkspacePaths.docRoot(linked, null));
    }

    @Test
    void aConfiguredRootWinsWhenItExists() throws Exception {
        Path worktree = Files.createDirectories(temp.resolve("worktree"));
        Files.createDirectories(worktree.resolve("doc"));
        Path configured = Files.createDirectories(temp.resolve("configured"));

        assertEquals(configured, WorkspacePaths.docRoot(worktree, configured.toString()));
    }

    @Test
    void anUnresolvableDocRootIsReportedInsteadOfGuessed() throws Exception {
        Path worktree = Files.createDirectories(temp.resolve("bare"));

        assertThrows(IllegalStateException.class, () -> WorkspacePaths.docRoot(worktree, null));
    }
}