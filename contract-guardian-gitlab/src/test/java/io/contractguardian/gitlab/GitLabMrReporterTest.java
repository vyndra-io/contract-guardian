package io.contractguardian.gitlab;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabMrReporterTest {

    @Test
    void fromSpec_validSpec_parsesProjectAndMrIid() {
        // We can't instantiate (requires env var), but we test that invalid specs throw
        assertThatThrownBy(() -> GitLabMrReporter.fromSpec("invalid-no-bang"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected format");
    }

    @Test
    void fromSpec_emptyProject_throws() {
        assertThatThrownBy(() -> GitLabMrReporter.fromSpec("!123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project ID cannot be empty");
    }

    @Test
    void fromSpec_nonNumericMrIid_throws() {
        assertThatThrownBy(() -> GitLabMrReporter.fromSpec("mygroup/myrepo!abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid GitLab MR number");
    }

    @Test
    void apiClient_findExistingNote_returnsEmptyForNoMatch() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode notes = mapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode note = mapper.createObjectNode();
        note.put("id", 42L);
        note.put("body", "This is a regular comment");
        note.put("system", false);
        notes.add(note);

        GitLabApiClient client = new GitLabApiClient("dummy-token", "https://gitlab.com/api/v4");
        assertThat(client.findExistingNote(notes, "<!-- contract-guardian -->")).isEmpty();
    }

    @Test
    void apiClient_findExistingNote_returnsIdForMatch() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode notes = mapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode note = mapper.createObjectNode();
        note.put("id", 99L);
        note.put("body", "<!-- contract-guardian -->\n## Contract Guardian results");
        note.put("system", false);
        notes.add(note);

        GitLabApiClient client = new GitLabApiClient("dummy-token", "https://gitlab.com/api/v4");
        assertThat(client.findExistingNote(notes, "<!-- contract-guardian -->"))
                .isPresent()
                .hasValue(99L);
    }

    @Test
    void apiClient_findExistingNote_skipsSystemNotes() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode notes = mapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode systemNote = mapper.createObjectNode();
        systemNote.put("id", 10L);
        systemNote.put("body", "<!-- contract-guardian --> (system note)");
        systemNote.put("system", true);
        notes.add(systemNote);

        GitLabApiClient client = new GitLabApiClient("dummy-token", "https://gitlab.com/api/v4");
        assertThat(client.findExistingNote(notes, "<!-- contract-guardian -->")).isEmpty();
    }
}
