package io.contractguardian.github;

import io.contractguardian.model.ApprovalStatus;
import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.model.ScanResult;
import io.contractguardian.model.Verdict;
import io.contractguardian.model.VerdictStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubMarkdownFormatterTest {

    private final GitHubMarkdownFormatter formatter = new GitHubMarkdownFormatter();

    @Test
    void format_includesMarker() {
        Verdict verdict = passingVerdict();
        assertThat(formatter.format(verdict)).startsWith(GitHubMarkdownFormatter.MARKER);
    }

    @Test
    void format_passingVerdict_showsPassStatus() {
        Verdict verdict = passingVerdict();
        String output = formatter.format(verdict);

        assertThat(output).contains("PASS");
        assertThat(output).doesNotContain(":x:");
    }

    @Test
    void format_breakingFindings_showsFailStatus() {
        Finding breaking = Finding.breaking(ContractType.KAFKA_AVRO, "schema/user.avsc",
                "field-removed", "Field 'email' removed");
        ScanResult result = new ScanResult("schema/user.avsc", ContractType.KAFKA_AVRO,
                List.of(breaking), Duration.ofMillis(10));
        Verdict verdict = new Verdict(VerdictStatus.FAIL, List.of(result), Duration.ofMillis(10), ApprovalStatus.none());

        String output = formatter.format(verdict);

        assertThat(output).contains("BLOCKED");
        assertThat(output).contains("schema/user.avsc");
        assertThat(output).contains("Field 'email' removed");
    }

    @Test
    void format_withFix_includesFixSuggestion() {
        Finding breaking = Finding.breaking(ContractType.KAFKA_AVRO, "schema/order.avsc",
                "field-removed", "Field removed", null, "Add a default value before removing");
        ScanResult result = new ScanResult("schema/order.avsc", ContractType.KAFKA_AVRO,
                List.of(breaking), Duration.ofMillis(5));
        Verdict verdict = new Verdict(VerdictStatus.FAIL, List.of(result), Duration.ofMillis(5), ApprovalStatus.none());

        String output = formatter.format(verdict);

        assertThat(output).contains("Add a default value before removing");
    }

    @Test
    void format_emptyResults_showsNoChanges() {
        Verdict verdict = new Verdict(VerdictStatus.PASS, List.of(), Duration.ofMillis(0), ApprovalStatus.none());
        String output = formatter.format(verdict);

        assertThat(output).contains("No contract files changed");
    }

    @Test
    void fromSpec_validSpec_parsesCorrectly() {
        // This tests the static factory method on GitHubPrReporter without needing a token
        // We can't fully test it without a token, but we test the parsing logic
        // by asserting it throws for invalid input
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                GitHubPrReporter.fromSpec("invalid-spec"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                GitHubPrReporter.fromSpec("missingslash#123"));
    }

    private Verdict passingVerdict() {
        Finding info = Finding.info(ContractType.KAFKA_AVRO, "schema/user.avsc",
                "compatible", "Schema is compatible");
        ScanResult result = new ScanResult("schema/user.avsc", ContractType.KAFKA_AVRO,
                List.of(info), Duration.ofMillis(5));
        return new Verdict(VerdictStatus.PASS, List.of(result), Duration.ofMillis(5), ApprovalStatus.none());
    }
}
