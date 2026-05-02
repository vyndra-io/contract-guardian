package io.contractguardian.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helper for git operations needed during contract scanning.
 */
public class GitHelper {

    private final Path workingDir;

    /**
     * Creates a git helper rooted at the given working directory.
     *
     * @param workingDir the git repository working directory
     */
    public GitHelper(final Path workingDir) {
        this.workingDir = workingDir;
    }

    /**
     * Returns the working directory.
     *
     * @return the working directory path
     */
    public Path workingDir() {
        return workingDir;
    }

    /**
     * Returns the list of files changed in the given diff spec.
     *
     * <p>Accepts either a two-ref spec ({@code origin/main..HEAD}) or a single ref
     * ({@code HEAD}, {@code abc123}). A single ref is expanded to {@code <ref>^..<ref>}
     * so that it compares the named commit against its parent.
     *
     * @param diffSpec the git diff specification (e.g. "origin/main..HEAD" or "HEAD")
     * @return the list of changed file paths relative to the working directory
     */
    public List<String> changedFiles(final String diffSpec) {
        final String resolvedSpec = diffSpec.contains("..") ? diffSpec : diffSpec + "^.." + diffSpec;
        final String output = runGit("diff", "--name-only", "--diff-filter=ACMR", resolvedSpec);
        if (output.isBlank()) {
            return List.of();
        }
        return Arrays.stream(output.split("\n"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Extracts a file's content at a given git ref into a temporary file.
     *
     * @param ref      the git ref (e.g. "origin/main")
     * @param filePath the file path relative to the repository root
     * @return the path to a temporary file containing the content,
     *         or {@code null} if the file did not exist at that ref
     */
    public Path extractFileAtRef(final String ref, final String filePath) {
        try {
            final String content = runGit("show", ref + ":" + filePath);
            final String extension = filePath.contains(".")
                    ? filePath.substring(filePath.lastIndexOf('.'))
                    : ".tmp";
            final Path tempFile = Files.createTempFile("cg-baseline-", extension);
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            return tempFile;
        } catch (GitException e) {
            // File did not exist at that ref (newly added)
            return null;
        } catch (IOException e) {
            throw new GitException("Failed to write temp file for " + filePath, e);
        }
    }

    /**
     * Extracts the base ref from a git diff spec.
     *
     * <p>For a two-ref spec ({@code origin/main..HEAD}), returns the left side ({@code origin/main}).
     * For a single ref ({@code HEAD}), returns {@code <ref>^} so baseline files are fetched
     * from the commit's parent, consistent with how {@link #changedFiles} expands single refs.
     *
     * @param diffSpec the diff spec (e.g. "origin/main..HEAD" or "HEAD")
     * @return the base ref to use for file extraction
     */
    public String baseRef(final String diffSpec) {
        final int doubleDot = diffSpec.indexOf("..");
        if (doubleDot >= 0) {
            return diffSpec.substring(0, doubleDot);
        }
        return diffSpec + "^";
    }

    /**
     * Returns temp files containing the content of a file at each of the last {@code n} commits
     * on the given ref that touched that file, ordered most-recent-first.
     *
     * <p>If the file has fewer than {@code n} commits in its history at the given ref, only the
     * available commits are returned. An empty list is returned when the file has no history
     * (i.e. it is being added for the first time).
     *
     * @param ref      the git ref to walk history from (e.g. "origin/main")
     * @param filePath the file path relative to the repository root
     * @param n        the maximum number of historical versions to retrieve
     * @return an ordered list of temp files, most-recent version first; caller is responsible for deletion
     */
    public List<Path> fileHistoryAtRef(final String ref, final String filePath, final int n) {
        final String logOutput;
        try {
            logOutput = runGit("log", "--follow", "-n", String.valueOf(n),
                    "--pretty=format:%H", ref, "--", filePath);
        } catch (GitException e) {
            return List.of();
        }
        if (logOutput.isBlank()) {
            return List.of();
        }
        final List<Path> result = new ArrayList<>();
        for (final String sha : logOutput.split("\n")) {
            final String trimmed = sha.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            final Path extracted = extractFileAtRef(trimmed, filePath);
            if (extracted != null) {
                result.add(extracted);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Checks whether the working directory is inside a git repository.
     *
     * @return {@code true} if this is a git repository
     */
    public boolean isGitRepo() {
        try {
            runGit("rev-parse", "--is-inside-work-tree");
            return true;
        } catch (GitException e) {
            return false;
        }
    }

    private String runGit(final String... args) {
        final List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));

        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        Process process = null;
        try {
            process = pb.start();
            final String stdout;
            final String stderr;
            try (var in = process.getInputStream(); var err = process.getErrorStream()) {
                stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
            }

            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new GitException("git " + String.join(" ", args)
                        + " failed (exit " + exitCode + "): " + stderr.strip());
            }
            return stdout.strip();
        } catch (GitException e) {
            throw e;
        } catch (Exception e) {
            throw new GitException("Failed to execute git command", e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Thrown when a git command fails.
     */
    public static class GitException extends RuntimeException {

        /**
         * Creates an exception with a message.
         *
         * @param message description of the failure
         */
        public GitException(final String message) {
            super(message);
        }

        /**
         * Creates an exception with a message and cause.
         *
         * @param message description of the failure
         * @param cause   the underlying exception
         */
        public GitException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
