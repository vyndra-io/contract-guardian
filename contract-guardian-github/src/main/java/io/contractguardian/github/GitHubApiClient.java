package io.contractguardian.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Thin client for the GitHub REST API, scoped to PR comment operations.
 *
 * <p>Authentication is via a {@code GITHUB_TOKEN} personal access token
 * passed in the {@code Authorization} header.
 */
public class GitHubApiClient {

    private static final String API_BASE = "https://api.github.com";
    private static final String ACCEPT_HEADER = "application/vnd.github+json";
    private static final String API_VERSION_HEADER = "2022-11-28";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String token;

    /**
     * Creates a GitHub API client.
     *
     * @param token the GitHub personal access token for authentication
     */
    public GitHubApiClient(final String token) {
        this.token = token;
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    /**
     * Lists all issue comments on a pull request.
     *
     * @param owner  the repository owner (user or org)
     * @param repo   the repository name
     * @param prNumber the pull request number
     * @return the response body as a JSON array node, or empty on error
     * @throws GitHubApiException if the API call fails
     */
    public JsonNode listComments(final String owner, final String repo, final int prNumber) {
        final String url = String.format("%s/repos/%s/%s/issues/%d/comments", API_BASE, owner, repo, prNumber);
        final HttpRequest request = buildGetRequest(url);
        return executeRequest(request);
    }

    /**
     * Creates a new comment on a pull request.
     *
     * @param owner    the repository owner
     * @param repo     the repository name
     * @param prNumber the pull request number
     * @param body     the comment body (Markdown)
     * @return the created comment as a JSON object node
     * @throws GitHubApiException if the API call fails
     */
    public JsonNode createComment(final String owner, final String repo, final int prNumber,
                                  final String body) {
        final String url = String.format("%s/repos/%s/%s/issues/%d/comments", API_BASE, owner, repo, prNumber);
        final String payload = buildCommentPayload(body);
        final HttpRequest request = buildPostRequest(url, payload);
        return executeRequest(request);
    }

    /**
     * Updates an existing comment on a pull request.
     *
     * @param owner     the repository owner
     * @param repo      the repository name
     * @param commentId the ID of the comment to update
     * @param body      the new comment body (Markdown)
     * @return the updated comment as a JSON object node
     * @throws GitHubApiException if the API call fails
     */
    public JsonNode updateComment(final String owner, final String repo,
                                  final long commentId, final String body) {
        final String url = String.format("%s/repos/%s/%s/issues/comments/%d", API_BASE, owner, repo, commentId);
        final String payload = buildCommentPayload(body);
        final HttpRequest request = buildPatchRequest(url, payload);
        return executeRequest(request);
    }

    /**
     * Finds an existing contract-guardian comment by looking for the hidden marker.
     *
     * @param comments the JSON array of existing PR comments
     * @param marker   the HTML marker string to search for
     * @return the comment ID if a matching comment is found
     */
    public Optional<Long> findExistingComment(final JsonNode comments, final String marker) {
        for (final JsonNode comment : comments) {
            final String body = comment.path("body").asText("");
            if (body.contains(marker)) {
                return Optional.of(comment.path("id").asLong());
            }
        }
        return Optional.empty();
    }

    private HttpRequest buildGetRequest(final String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", ACCEPT_HEADER)
                .header("X-GitHub-Api-Version", API_VERSION_HEADER)
                .GET()
                .build();
    }

    private HttpRequest buildPostRequest(final String url, final String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", ACCEPT_HEADER)
                .header("X-GitHub-Api-Version", API_VERSION_HEADER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest buildPatchRequest(final String url, final String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", ACCEPT_HEADER)
                .header("X-GitHub-Api-Version", API_VERSION_HEADER)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private JsonNode executeRequest(final HttpRequest request) {
        try {
            final HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GitHubApiException(
                        "GitHub API request failed: HTTP " + response.statusCode()
                                + " — " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GitHubApiException("GitHub API request failed: " + e.getMessage(), e);
        }
    }

    private String buildCommentPayload(final String body) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("body", body);
        return node.toString();
    }

    /**
     * Thrown when a GitHub API call fails.
     */
    public static class GitHubApiException extends RuntimeException {

        /**
         * Creates an exception with a message.
         *
         * @param message description of the failure
         */
        public GitHubApiException(final String message) {
            super(message);
        }

        /**
         * Creates an exception with a message and cause.
         *
         * @param message description of the failure
         * @param cause   the underlying exception
         */
        public GitHubApiException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
