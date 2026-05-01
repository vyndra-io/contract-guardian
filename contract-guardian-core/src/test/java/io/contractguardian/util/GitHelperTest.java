package io.contractguardian.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitHelperTest {

    @Test
    void fileHistoryAtRef_returnsUpToNVersions(@TempDir Path repoDir) throws Exception {
        initRepo(repoDir);
        commitFile(repoDir, "schema.avsc", "v1");
        commitFile(repoDir, "schema.avsc", "v2");
        commitFile(repoDir, "schema.avsc", "v3");

        final GitHelper git = new GitHelper(repoDir);
        final List<Path> history = git.fileHistoryAtRef("HEAD", "schema.avsc", 2);

        try {
            assertThat(history).hasSize(2);
            assertThat(Files.readString(history.get(0))).isEqualTo("v3");
            assertThat(Files.readString(history.get(1))).isEqualTo("v2");
        } finally {
            history.forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void fileHistoryAtRef_fewerCommitsThanN_returnsAllAvailable(@TempDir Path repoDir) throws Exception {
        initRepo(repoDir);
        commitFile(repoDir, "schema.avsc", "v1");

        final GitHelper git = new GitHelper(repoDir);
        final List<Path> history = git.fileHistoryAtRef("HEAD", "schema.avsc", 5);

        try {
            assertThat(history).hasSize(1);
            assertThat(Files.readString(history.get(0))).isEqualTo("v1");
        } finally {
            history.forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void fileHistoryAtRef_fileNotInHistory_returnsEmptyList(@TempDir Path repoDir) throws Exception {
        initRepo(repoDir);
        commitFile(repoDir, "other.avsc", "irrelevant");

        final GitHelper git = new GitHelper(repoDir);
        final List<Path> history = git.fileHistoryAtRef("HEAD", "schema.avsc", 3);

        assertThat(history).isEmpty();
    }

    // --- helpers ---

    private void initRepo(final Path repoDir) throws Exception {
        run(repoDir, "git", "init");
        run(repoDir, "git", "config", "user.email", "test@example.com");
        run(repoDir, "git", "config", "user.name", "Test");
    }

    private void commitFile(final Path repoDir, final String fileName,
                             final String content) throws Exception {
        Files.writeString(repoDir.resolve(fileName), content);
        run(repoDir, "git", "add", fileName);
        run(repoDir, "git", "commit", "-m", "update " + fileName + " to " + content);
    }

    private void run(final Path dir, final String... command) throws Exception {
        final Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        final int exit = process.waitFor();
        if (exit != 0) {
            final String output = new String(process.getInputStream().readAllBytes());
            throw new IOException("Command failed (exit " + exit + "): " + output);
        }
    }
}
