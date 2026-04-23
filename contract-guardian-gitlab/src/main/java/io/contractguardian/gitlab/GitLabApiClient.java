package io.contractguardian.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Thin client for the GitLab REST API, scoped to MR note operations.
 *
 * <p>Authentication is via a {@code GITLAB_TOKEN} personal access token
 * sent in the {@code PRIVATE-TOKEN} header.
 */
public class GitLabApiClient {

    private static final String DEFAULT_API_BASE = "https://gitlab.com/api/v4";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String token;
    private final String apiBase;

    /**
     * Creates a GitLab API client pointing at the public GitLab instance.
     *
     * @param token the GitLab personal access token
     */
    public GitLabApiClient(final String token) {
        this(token, DEFAULT_API_BASE);
    }

    /**
     * Creates a GitLab API client for a self-hosted GitLab instance.
     *
     * @param token   the GitLab personal access token
     * @param apiBase the base URL of the GitLab API (e.g. {@code https://gitlab.example.com/api/v4})
     */
    public GitLabApiClient(final String token, final String apiBase) {
        this.token = token;
        this.apiBase = apiBase;
        this.httpClient = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    /**
     * Lists all notes (comments) on a merge request.
     *
     * @param projectId the URL-encoded project path or numeric project ID
     * @param mrIid     the merge request internal ID
     * @return the response body as a JSON array node
     * @throws GitLabApiException if the API call fails
     */
    public JsonNode listNotes(final String projectId, final int mrIid) {
        final String encoded = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        final String url = String.format("%s/projects/%s/merge_requests/%d/notes?per_page=100",
                apiBase, encoded, mrIid);
        return executeRequest(buildGetRequest(url));
    }

    /**
     * Creates a new note on a merge request.
     *
     * @param projectId the project path or numeric ID
     * @param mrIid     the merge request internal ID
     * @param body      the note body (Markdown)
     * @return the created note as a JSON object node
     * @throws GitLabApiException if the API call fails
     */
    public JsonNode createNote(final String projectId, final int mrIid, final String body) {
        final String encoded = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        final String url = String.format("%s/projects/%s/merge_requests/%d/notes",
                apiBase, encoded, mrIid);
        return executeRequest(buildPostRequest(url, buildNotePayload(body)));
    }

    /**
     * Updates an existing note on a merge request.
     *
     * @param projectId the project path or numeric ID
     * @param mrIid     the merge request internal ID
     * @param noteId    the ID of the note to update
     * @param body      the new note body (Markdown)
     * @return the updated note as a JSON object node
     * @throws GitLabApiException if the API call fails
     */
    public JsonNode updateNote(final String projectId, final int mrIid,
                               final long noteId, final String body) {
        final String encoded = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        final String url = String.format("%s/projects/%s/merge_requests/%d/notes/%d",
                apiBase, encoded, mrIid, noteId);
        return executeRequest(buildPutRequest(url, buildNotePayload(body)));
    }

    /**
     * Finds an existing contract-guardian note by looking for the hidden marker.
     *
     * @param notes  the JSON array of existing MR notes
     * @param marker the marker string to search for
     * @return the note ID if a matching note is found
     */
    public Optional<Long> findExistingNote(final JsonNode notes, final String marker) {
        for (final JsonNode note : notes) {
            if (note.path("system").asBoolean(false)) {
                continue;
            }
            final String body = note.path("body").asText("");
            if (body.contains(marker)) {
                return Optional.of(note.path("id").asLong());
            }
        }
        return Optional.empty();
    }

    private HttpRequest buildGetRequest(final String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private HttpRequest buildPostRequest(final String url, final String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest buildPutRequest(final String url, final String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private JsonNode executeRequest(final HttpRequest request) {
        try {
            final HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GitLabApiException(
                        "GitLab API request failed: HTTP " + response.statusCode()
                                + " — " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GitLabApiException("GitLab API request failed: " + e.getMessage(), e);
        }
    }

    private String buildNotePayload(final String body) {
        final ObjectNode node = mapper.createObjectNode();
        node.put("body", body);
        return node.toString();
    }

    /**
     * Thrown when a GitLab API call fails.
     */
    public static class GitLabApiException extends RuntimeException {

        /**
         * Creates an exception with a message.
         *
         * @param message description of the failure
         */
        public GitLabApiException(final String message) {
            super(message);
        }

        /**
         * Creates an exception with a message and cause.
         *
         * @param message description of the failure
         * @param cause   the underlying exception
         */
        public GitLabApiException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
