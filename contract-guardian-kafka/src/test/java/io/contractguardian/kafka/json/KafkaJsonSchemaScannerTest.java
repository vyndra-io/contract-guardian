package io.contractguardian.kafka.json;

import io.contractguardian.model.ScanResult;
import io.contractguardian.model.Severity;
import io.contractguardian.policy.KafkaRuleConfig;
import io.contractguardian.policy.KafkaRuleConfig.CompatibilityMode;
import io.contractguardian.policy.SourceConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaJsonSchemaScannerTest {

    private final KafkaJsonSchemaScanner scanner = new KafkaJsonSchemaScanner();
    private final KafkaRuleConfig backwardConfig = new KafkaRuleConfig(CompatibilityMode.BACKWARD, List.of());
    private final KafkaRuleConfig forwardConfig = new KafkaRuleConfig(CompatibilityMode.FORWARD, List.of());
    private final KafkaRuleConfig noneConfig = new KafkaRuleConfig(CompatibilityMode.NONE, List.of());
    private final SourceConfig kafkaSource = new SourceConfig("kafka",
            List.of("schemas/**/*.json"), "branch:main");

    private Path scenario(final String name, final String file) {
        return Path.of("src/test/resources/json-scenarios", name, file);
    }

    @Test
    void canScan_jsonSchemaFile_returnsTrue() {
        Path jsonSchema = scenario("property-removed", "baseline.json");
        assertThat(scanner.canScan(jsonSchema, kafkaSource)).isTrue();
    }

    @Test
    void canScan_avscFile_returnsFalse() {
        assertThat(scanner.canScan(Path.of("schemas/user.avsc"), kafkaSource)).isFalse();
    }

    @Test
    void scan_noBaseline_returnsNewSchemaInfo() {
        ScanResult result = scanner.scan(scenario("property-removed", "current.json"), null, backwardConfig);

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(result.findings().get(0).rule()).isEqualTo("new-schema");
    }

    @Test
    void propertyRemoved_backwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("property-removed", "current.json"),
                scenario("property-removed", "baseline.json"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("property-removed")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("email"));
    }

    @Test
    void requiredPropertyAdded_forwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("required-property-added", "current.json"),
                scenario("required-property-added", "baseline.json"),
                forwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("required-property-added")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("tenant"));
    }

    @Test
    void propertyTypeChanged_backwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("property-type-changed", "current.json"),
                scenario("property-type-changed", "baseline.json"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("property-type-changed")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("amount"));
    }

    @Test
    void enumValueRemoved_backwardIncompatible() {
        ScanResult result = scanner.scan(
                scenario("enum-value-removed", "current.json"),
                scenario("enum-value-removed", "baseline.json"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("enum-value-removed")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("CLOSED"));
    }

    @Test
    void optionalPropertyAdded_backwardCompatible() {
        ScanResult result = scanner.scan(
                scenario("optional-property-added", "current.json"),
                scenario("optional-property-added", "baseline.json"),
                backwardConfig);

        assertThat(result.hasBreaking()).isFalse();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("compatible") && f.severity() == Severity.INFO);
    }

    @Test
    void noneMode_noFindings() {
        ScanResult result = scanner.scan(
                scenario("property-removed", "current.json"),
                scenario("property-removed", "baseline.json"),
                noneConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("compatible"));
    }
}
