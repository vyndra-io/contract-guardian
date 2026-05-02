package io.contractguardian.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.contractguardian.model.ApprovalStatus;
import io.contractguardian.policy.ApprovalChecker;
import io.contractguardian.policy.GateConfig;

/**
 * Checks whether an override approval is in place for a GitHub pull request.
 *
 * <p>The check passes when the configured {@link GateConfig#approvalLabel()} is present
 * on the PR and the label was applied by a user whose login appears in
 * {@link GateConfig#approvers()}.
 *
 * <p>Authentication requires a {@code GITHUB_TOKEN} environment variable.
 */
public class GitHubApprovalChecker implements ApprovalChecker {

    private final String owner;
    private final String repo;
    private final int prNumber;
    private final GitHubApiClient apiClient;

    /**
     * Creates a checker for the given pull request, reading the token from the
     * {@code GITHUB_TOKEN} environment variable.
     *
     * @param owner    the repository owner (user or org)
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @throws IllegalStateException if {@code GITHUB_TOKEN} is not set
     */
    public GitHubApprovalChecker(final String owner, final String repo, final int prNumber) {
        this(owner, repo, prNumber, requireToken());
    }

    /**
     * Creates a checker with an explicit token.
     *
     * @param owner    the repository owner
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @param token    the GitHub personal access token
     */
    public GitHubApprovalChecker(final String owner, final String repo, final int prNumber,
                                  final String token) {
        this.owner = owner;
        this.repo = repo;
        this.prNumber = prNumber;
        this.apiClient = new GitHubApiClient(token);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@link ApprovalStatus#none()} when the approval label is absent,
     * when no approvers are configured, or when the label was not applied by a
     * listed approver.
     */
    @Override
    public ApprovalStatus check(final GateConfig gate) {
        if (gate.approvalLabel() == null || gate.approvalLabel().isBlank()) {
            return ApprovalStatus.none();
        }
        if (gate.approvers().isEmpty()) {
            return ApprovalStatus.none();
        }

        try {
            if (!hasLabel(gate.approvalLabel())) {
                return ApprovalStatus.none();
            }
            final String appliedBy = findLabelApplier(gate.approvalLabel());
            if (appliedBy == null) {
                return ApprovalStatus.none();
            }
            if (gate.approvers().contains(appliedBy)) {
                return new ApprovalStatus(true, appliedBy);
            }
        } catch (GitHubApiClient.GitHubApiException e) {
            // Fail open — treat API errors as unapproved rather than crashing the scan
        }

        return ApprovalStatus.none();
    }

    /**
     * Parses an {@code owner/repo#prNumber} spec into a {@link GitHubApprovalChecker}.
     *
     * @param spec the PR spec string (e.g. {@code myorg/myrepo#42})
     * @return a configured checker
     * @throws IllegalArgumentException if the spec format is invalid
     */
    public static GitHubApprovalChecker fromSpec(final String spec) {
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
        return new GitHubApprovalChecker(
                repoPath.substring(0, slashIdx),
                repoPath.substring(slashIdx + 1),
                prNum);
    }

    private boolean hasLabel(final String labelName) {
        final JsonNode labels = apiClient.listLabels(owner, repo, prNumber);
        for (final JsonNode label : labels) {
            if (labelName.equals(label.path("name").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private String findLabelApplier(final String labelName) {
        final JsonNode events = apiClient.listLabelEvents(owner, repo, prNumber);
        String lastAppliedBy = null;
        for (final JsonNode event : events) {
            if (!"labeled".equals(event.path("event").asText(""))) {
                continue;
            }
            if (!labelName.equals(event.path("label").path("name").asText(""))) {
                continue;
            }
            lastAppliedBy = event.path("actor").path("login").asText(null);
        }
        return lastAppliedBy;
    }

    private static String requireToken() {
        final String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "GITHUB_TOKEN environment variable is not set.");
        }
        return token;
    }
}
