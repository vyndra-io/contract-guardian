package io.contractguardian.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import io.contractguardian.model.ApprovalStatus;
import io.contractguardian.policy.ApprovalChecker;
import io.contractguardian.policy.GateConfig;

/**
 * Checks whether an override approval is in place for a GitLab merge request.
 *
 * <p>The check passes when the configured {@link GateConfig#approvalLabel()} appears in
 * the MR's {@code labels} field and the label was most recently added by a user whose
 * username appears in {@link GateConfig#approvers()}.
 *
 * <p>Authentication requires a {@code GITLAB_TOKEN} environment variable or an explicit
 * token passed to the constructor.
 */
public class GitLabApprovalChecker implements ApprovalChecker {

    private final String projectId;
    private final int mrIid;
    private final GitLabApiClient apiClient;

    /**
     * Creates a checker for gitlab.com, reading the token from the {@code GITLAB_TOKEN}
     * environment variable.
     *
     * @param projectId the GitLab project path (e.g. {@code mygroup/myrepo}) or numeric ID
     * @param mrIid     the merge request internal ID
     * @throws IllegalStateException if {@code GITLAB_TOKEN} is not set
     */
    public GitLabApprovalChecker(final String projectId, final int mrIid) {
        this(projectId, mrIid, requireToken(), "https://gitlab.com/api/v4");
    }

    /**
     * Creates a checker with an explicit token and API base URL.
     *
     * @param projectId the GitLab project path or numeric ID
     * @param mrIid     the merge request internal ID
     * @param token     the GitLab personal access token
     * @param apiBase   the GitLab API base URL
     */
    public GitLabApprovalChecker(final String projectId, final int mrIid,
                                  final String token, final String apiBase) {
        this.projectId = projectId;
        this.mrIid = mrIid;
        this.apiClient = new GitLabApiClient(token, apiBase);
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
        } catch (GitLabApiClient.GitLabApiException e) {
            // Fail open — treat API errors as unapproved rather than crashing the scan
        }

        return ApprovalStatus.none();
    }

    /**
     * Parses a {@code project-path!mrIid} spec into a {@link GitLabApprovalChecker}.
     *
     * @param spec the MR spec string (e.g. {@code mygroup/myrepo!42})
     * @return a configured checker
     * @throws IllegalArgumentException if the spec format is invalid
     */
    public static GitLabApprovalChecker fromSpec(final String spec) {
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
        return new GitLabApprovalChecker(projectId, mrIid);
    }

    private boolean hasLabel(final String labelName) {
        final JsonNode mr = apiClient.getMergeRequest(projectId, mrIid);
        for (final JsonNode label : mr.path("labels")) {
            if (labelName.equals(label.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private String findLabelApplier(final String labelName) {
        final JsonNode events = apiClient.listLabelEvents(projectId, mrIid);
        String lastAppliedBy = null;
        for (final JsonNode event : events) {
            if (!"add".equals(event.path("action").asText(""))) {
                continue;
            }
            if (!labelName.equals(event.path("label").path("name").asText(""))) {
                continue;
            }
            lastAppliedBy = event.path("user").path("username").asText(null);
        }
        return lastAppliedBy;
    }

    private static String requireToken() {
        final String token = System.getenv("GITLAB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "GITLAB_TOKEN environment variable is not set.");
        }
        return token;
    }
}
