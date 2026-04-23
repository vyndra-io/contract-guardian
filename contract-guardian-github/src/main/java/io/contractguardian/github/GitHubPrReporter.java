package io.contractguardian.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.contractguardian.model.Verdict;
import io.contractguardian.report.Reporter;

import java.io.PrintStream;
import java.util.Optional;

/**
 * Posts or updates a Contract Guardian scan summary as a GitHub pull request comment.
 *
 * <p>Uses the hidden HTML marker {@code <!-- contract-guardian -->} to detect and
 * update an existing comment rather than posting duplicates on each run.
 *
 * <p>Authentication requires a {@code GITHUB_TOKEN} environment variable or
 * an explicit token passed to the constructor.
 */
public class GitHubPrReporter implements Reporter {

    private final String owner;
    private final String repo;
    private final int prNumber;
    private final GitHubApiClient apiClient;
    private final GitHubMarkdownFormatter formatter;

    /**
     * Creates a GitHub PR reporter that reads the token from the {@code GITHUB_TOKEN} env var.
     *
     * @param owner    the repository owner (user or org)
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @throws IllegalStateException if {@code GITHUB_TOKEN} is not set
     */
    public GitHubPrReporter(final String owner, final String repo, final int prNumber) {
        this(owner, repo, prNumber, requireToken());
    }

    /**
     * Creates a GitHub PR reporter with an explicit token.
     *
     * @param owner    the repository owner
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @param token    the GitHub personal access token
     */
    public GitHubPrReporter(final String owner, final String repo, final int prNumber,
                             final String token) {
        this.owner = owner;
        this.repo = repo;
        this.prNumber = prNumber;
        this.apiClient = new GitHubApiClient(token);
        this.formatter = new GitHubMarkdownFormatter();
    }

    @Override
    public void report(final Verdict verdict, final PrintStream out) {
        final String body = formatter.format(verdict);

        try {
            final JsonNode comments = apiClient.listComments(owner, repo, prNumber);
            final Optional<Long> existingId =
                    apiClient.findExistingComment(comments, GitHubMarkdownFormatter.MARKER);

            if (existingId.isPresent()) {
                apiClient.updateComment(owner, repo, existingId.get(), body);
                out.printf("Contract Guardian: updated existing PR comment on %s/%s#%d%n",
                        owner, repo, prNumber);
            } else {
                apiClient.createComment(owner, repo, prNumber, body);
                out.printf("Contract Guardian: posted PR comment on %s/%s#%d%n",
                        owner, repo, prNumber);
            }
        } catch (GitHubApiClient.GitHubApiException e) {
            out.println("Contract Guardian: failed to post GitHub PR comment — " + e.getMessage());
        }
    }

    /**
     * Parses an owner/repo#prNumber string into a {@link GitHubPrReporter}.
     *
     * <p>Expected format: {@code owner/repo#123}
     *
     * @param spec the PR spec string
     * @return a configured reporter
     * @throws IllegalArgumentException if the spec format is invalid
     */
    public static GitHubPrReporter fromSpec(final String spec) {
        final int hashIdx = spec.lastIndexOf('#');
        if (hashIdx < 0) {
            throw new IllegalArgumentException(
                    "Invalid GitHub PR spec: '" + spec + "' — expected format: owner/repo#123");
        }
        final String repoPath = spec.substring(0, hashIdx);
        final int prNum;
        try {
            prNum = Integer.parseInt(spec.substring(hashIdx + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid GitHub PR number in spec: '" + spec + "'", e);
        }
        final int slashIdx = repoPath.indexOf('/');
        if (slashIdx < 0) {
            throw new IllegalArgumentException(
                    "Invalid GitHub PR spec: '" + spec + "' — expected format: owner/repo#123");
        }
        final String owner = repoPath.substring(0, slashIdx);
        final String repo = repoPath.substring(slashIdx + 1);
        return new GitHubPrReporter(owner, repo, prNum);
    }

    private static String requireToken() {
        final String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "GITHUB_TOKEN environment variable is not set. "
                            + "Set it to a GitHub personal access token with 'pull_requests' scope.");
        }
        return token;
    }
}
