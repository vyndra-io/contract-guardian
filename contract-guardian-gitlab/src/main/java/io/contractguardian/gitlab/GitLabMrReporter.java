package io.contractguardian.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import io.contractguardian.github.GitHubMarkdownFormatter;
import io.contractguardian.model.Verdict;
import io.contractguardian.report.Reporter;

import java.io.PrintStream;
import java.util.Optional;

/**
 * Posts or updates a Contract Guardian scan summary as a GitLab merge request note.
 *
 * <p>Uses the same Markdown formatter as the GitHub reporter and the same hidden HTML marker
 * ({@code <!-- contract-guardian -->}) to detect and update existing notes rather than
 * posting duplicates on each run.
 *
 * <p>Authentication requires a {@code GITLAB_TOKEN} environment variable or an explicit
 * token passed to the constructor.
 *
 * <p>Supports both gitlab.com and self-hosted GitLab instances via the {@code gitlabUrl}
 * constructor parameter.
 */
public class GitLabMrReporter implements Reporter {

    private final String projectId;
    private final int mrIid;
    private final GitLabApiClient apiClient;
    private final GitHubMarkdownFormatter formatter;

    /**
     * Creates a GitLab MR reporter for gitlab.com that reads the token from env.
     *
     * @param projectId the GitLab project path (e.g. {@code mygroup/myrepo}) or numeric ID
     * @param mrIid     the merge request internal ID
     * @throws IllegalStateException if {@code GITLAB_TOKEN} is not set
     */
    public GitLabMrReporter(final String projectId, final int mrIid) {
        this(projectId, mrIid, requireToken(), "https://gitlab.com/api/v4");
    }

    /**
     * Creates a GitLab MR reporter with an explicit token and API base URL.
     *
     * @param projectId the GitLab project path or numeric ID
     * @param mrIid     the merge request internal ID
     * @param token     the GitLab personal access token
     * @param apiBase   the GitLab API base URL
     */
    public GitLabMrReporter(final String projectId, final int mrIid,
                             final String token, final String apiBase) {
        this.projectId = projectId;
        this.mrIid = mrIid;
        this.apiClient = new GitLabApiClient(token, apiBase);
        this.formatter = new GitHubMarkdownFormatter();
    }

    @Override
    public void report(final Verdict verdict, final PrintStream out) {
        final String body = formatter.format(verdict);

        try {
            final JsonNode notes = apiClient.listNotes(projectId, mrIid);
            final Optional<Long> existingId =
                    apiClient.findExistingNote(notes, GitHubMarkdownFormatter.MARKER);

            if (existingId.isPresent()) {
                apiClient.updateNote(projectId, mrIid, existingId.get(), body);
                out.printf("Contract Guardian: updated existing MR note on %s!%d%n", projectId, mrIid);
            } else {
                apiClient.createNote(projectId, mrIid, body);
                out.printf("Contract Guardian: posted MR note on %s!%d%n", projectId, mrIid);
            }
        } catch (GitLabApiClient.GitLabApiException e) {
            out.println("Contract Guardian: failed to post GitLab MR note — " + e.getMessage());
        }
    }

    /**
     * Parses a project-id!mrIid string into a {@link GitLabMrReporter}.
     *
     * <p>Expected format: {@code project-path!123} or {@code 456!123}
     *
     * @param spec the MR spec string
     * @return a configured reporter
     * @throws IllegalArgumentException if the spec format is invalid
     */
    public static GitLabMrReporter fromSpec(final String spec) {
        final int bangIdx = spec.lastIndexOf('!');
        if (bangIdx < 0) {
            throw new IllegalArgumentException(
                    "Invalid GitLab MR spec: '" + spec + "' — expected format: project-id!123");
        }
        final String projectId = spec.substring(0, bangIdx);
        final int mrIid;
        try {
            mrIid = Integer.parseInt(spec.substring(bangIdx + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid GitLab MR number in spec: '" + spec + "'", e);
        }
        if (projectId.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid GitLab MR spec: '" + spec + "' — project ID cannot be empty");
        }
        return new GitLabMrReporter(projectId, mrIid);
    }

    private static String requireToken() {
        final String token = System.getenv("GITLAB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "GITLAB_TOKEN environment variable is not set. "
                            + "Set it to a GitLab personal access token with 'api' scope.");
        }
        return token;
    }
}
