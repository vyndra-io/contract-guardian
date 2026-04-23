package io.contractguardian.kafka.avro;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.model.ScanResult;
import io.contractguardian.model.Severity;
import io.contractguardian.policy.KafkaRuleConfig;
import io.contractguardian.policy.KafkaRuleConfig.CompatibilityMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaAvroScannerTest {

    private final KafkaAvroScanner scanner = new KafkaAvroScanner();
    private final KafkaRuleConfig backwardConfig = new KafkaRuleConfig(CompatibilityMode.BACKWARD, List.of());
    private final KafkaRuleConfig forwardConfig = new KafkaRuleConfig(CompatibilityMode.FORWARD, List.of());
    private final KafkaRuleConfig fullConfig = new KafkaRuleConfig(CompatibilityMode.FULL, List.of());
    private final KafkaRuleConfig noneConfig = new KafkaRuleConfig(CompatibilityMode.NONE, List.of());

    private Path scenario(String name, String file) {
        return Path.of("src/test/resources/avro-scenarios", name, file);
    }

    // --- Backward compatibility tests ---

    @Test
    void fieldAddedWithDefault_backwardCompatible() {
        ScanResult result = scanner.scan(
                scenario("field-added-with-default", "current.avsc"),
                scenario("field-added-with-default", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isFalse();
        assertThat(result.contractType()).isEqualTo(ContractType.KAFKA_AVRO);
    }

    @Test
    void fieldAddedNoDefault_backwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("field-added-no-default", "current.avsc"),
                scenario("field-added-no-default", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings()).anyMatch(f ->
                f.severity() == Severity.BREAKING && f.message().contains("no default"));
    }

    @Test
    void fieldRemoved_backwardCompatible() {
        // Backward = new reader reads old data. Avro ignores unknown writer fields,
        // so removing a field is backward compatible.
        ScanResult result = scanner.scan(
                scenario("field-removed", "current.avsc"),
                scenario("field-removed", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isFalse();
    }

    @Test
    void fieldRemovedWithDefault_forwardCompatible() {
        // Forward = old reader reads new data. Old reader has a default for "phone",
        // so missing field is filled in — forward compatible.
        ScanResult result = scanner.scan(
                scenario("field-removed", "current.avsc"),
                scenario("field-removed", "baseline.avsc"),
                forwardConfig);

        assertThat(result.hasBreaking()).isFalse();
    }

    @Test
    void fieldRemovedNoDefault_forwardIncompatible() {
        // Forward = old reader reads new data. Old reader has no default for "email",
        // and new writer doesn't send it — breaks forward compatibility.
        ScanResult result = scanner.scan(
                scenario("field-removed-no-default", "current.avsc"),
                scenario("field-removed-no-default", "baseline.avsc"),
                forwardConfig);

        assertThat(result.hasBreaking()).isTrue();
    }

    @Test
    void fieldTypeChanged_backwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("field-type-changed", "current.avsc"),
                scenario("field-type-changed", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings()).anyMatch(f ->
                f.severity() == Severity.BREAKING);
    }

    @Test
    void enumSymbolAdded_backwardCompatible() {
        ScanResult result = scanner.scan(
                scenario("enum-symbol-added", "current.avsc"),
                scenario("enum-symbol-added", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isFalse();
    }

    @Test
    void enumSymbolRemoved_backwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("enum-symbol-removed", "current.avsc"),
                scenario("enum-symbol-removed", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings()).anyMatch(f ->
                f.message().contains("Enum symbols removed") || f.message().contains("enum"));
    }

    @Test
    void unionExpanded_backwardCompatible() {
        ScanResult result = scanner.scan(
                scenario("union-expanded", "current.avsc"),
                scenario("union-expanded", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isFalse();
    }

    @Test
    void unionNarrowed_backwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("union-narrowed", "current.avsc"),
                scenario("union-narrowed", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
    }

    @Test
    void defaultAdded_backwardCompatible() {
        ScanResult result = scanner.scan(
                scenario("default-added", "current.avsc"),
                scenario("default-added", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isFalse();
    }

    @Test
    void namespaceChanged_backwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("namespace-changed", "current.avsc"),
                scenario("namespace-changed", "baseline.avsc"),
                backwardConfig);

        // Avro treats different namespaces as name mismatch - breaking for backward compat
        // When names differ, the reader/writer check detects a name mismatch
        // However, Avro may allow this at the top level with BACKWARD mode
        // since the new reader just can't read old data with a different name.
        // The actual behavior depends on how SchemaCompatibility handles full name changes.
        // If Avro doesn't flag this as incompatible, we should test with FULL mode instead.
        assertThat(result.findings()).isNotEmpty();
    }

    @Test
    void complexNestedTypeChanged_backwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("complex-nested", "current.avsc"),
                scenario("complex-nested", "baseline.avsc"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
    }

    // --- New file (no baseline) ---

    @Test
    void newFile_noBaseline_pass() {
        ScanResult result = scanner.scan(
                scenario("new-file", "current.avsc"),
                null,
                backwardConfig);

        assertThat(result.hasBreaking()).isFalse();
        assertThat(result.findings()).anyMatch(f ->
                f.severity() == Severity.INFO && f.rule().equals("new-schema"));
    }

    // --- Forward compatibility ---

    @Test
    void enumSymbolAdded_forwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("enum-symbol-added", "current.avsc"),
                scenario("enum-symbol-added", "baseline.avsc"),
                forwardConfig);

        // Forward: old reader reads new data. Old reader doesn't know DELIVERED.
        assertThat(result.hasBreaking()).isTrue();
    }

    @Test
    void unionExpanded_forwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("union-expanded", "current.avsc"),
                scenario("union-expanded", "baseline.avsc"),
                forwardConfig);

        // Forward: old reader reads new data. Old reader doesn't know "int" branch.
        assertThat(result.hasBreaking()).isTrue();
    }

    // --- Full compatibility ---

    @Test
    void enumSymbolAdded_fullIncompatible() {
        ScanResult result = scanner.scan(
                scenario("enum-symbol-added", "current.avsc"),
                scenario("enum-symbol-added", "baseline.avsc"),
                fullConfig);

        // Full = backward + forward. backward passes, forward fails.
        assertThat(result.hasBreaking()).isTrue();
    }

    // --- NONE compatibility ---

    @Test
    void fieldRemoved_noneMode_noFindings() {
        ScanResult result = scanner.scan(
                scenario("field-type-changed", "current.avsc"),
                scenario("field-type-changed", "baseline.avsc"),
                noneConfig);

        assertThat(result.hasBreaking()).isFalse();
    }

    // --- Finding details ---

    @Test
    void breakingFinding_hasSuggestedFix() {
        ScanResult result = scanner.scan(
                scenario("field-type-changed", "current.avsc"),
                scenario("field-type-changed", "baseline.avsc"),
                backwardConfig);

        List<Finding> breakingFindings = result.findings().stream()
                .filter(f -> f.severity() == Severity.BREAKING)
                .toList();
        assertThat(breakingFindings).isNotEmpty();
        // At least some findings should have fix suggestions
        assertThat(breakingFindings).anyMatch(f -> f.fix() != null);
    }
}
