package io.contractguardian.kafka.proto;

import io.contractguardian.model.ScanResult;
import io.contractguardian.model.Severity;
import io.contractguardian.policy.KafkaRuleConfig;
import io.contractguardian.policy.KafkaRuleConfig.CompatibilityMode;
import io.contractguardian.policy.SourceConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProtobufScannerTest {

    private final KafkaProtobufScanner scanner = new KafkaProtobufScanner();
    private final KafkaRuleConfig backwardConfig = new KafkaRuleConfig(CompatibilityMode.BACKWARD, List.of(), 1);
    private final KafkaRuleConfig noneConfig = new KafkaRuleConfig(CompatibilityMode.NONE, List.of(), 1);
    private final SourceConfig kafkaSource = new SourceConfig("kafka",
            List.of("schemas/**/*.proto"), "branch:main");

    private Path scenario(final String name, final String file) {
        return Path.of("src/test/resources/proto-scenarios", name, file);
    }

    @Test
    void canScan_protoFile_returnsTrue() {
        assertThat(scanner.canScan(Path.of("schemas/order.proto"), kafkaSource)).isTrue();
    }

    @Test
    void canScan_avscFile_returnsFalse() {
        assertThat(scanner.canScan(Path.of("schemas/order.avsc"), kafkaSource)).isFalse();
    }

    @Test
    void scan_noBaseline_returnsNewSchemaInfo() {
        ScanResult result = scanner.scan(
                scenario("field-removed-without-reserved", "current.proto"), null, backwardConfig);

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(result.findings().get(0).rule()).isEqualTo("new-schema");
    }

    @Test
    void fieldRemovedWithoutReserved_isBreaking() {
        ScanResult result = scanner.scan(
                scenario("field-removed-without-reserved", "current.proto"),
                scenario("field-removed-without-reserved", "baseline.proto"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("field-removed-without-reserved")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("total"));
    }

    @Test
    void fieldRemovedWithReserved_isCompatible() {
        ScanResult result = scanner.scan(
                scenario("field-removed-with-reserved", "current.proto"),
                scenario("field-removed-with-reserved", "baseline.proto"),
                backwardConfig);

        assertThat(result.hasBreaking()).isFalse();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("compatible"));
    }

    @Test
    void fieldTypeChanged_isBreaking() {
        ScanResult result = scanner.scan(
                scenario("field-type-changed", "current.proto"),
                scenario("field-type-changed", "baseline.proto"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("field-type-changed")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("amount"));
    }

    @Test
    void enumValueRemoved_isBreaking() {
        ScanResult result = scanner.scan(
                scenario("enum-value-removed", "current.proto"),
                scenario("enum-value-removed", "baseline.proto"),
                backwardConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("enum-value-removed")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("CLOSED"));
    }

    @Test
    void noneMode_noFindings() {
        ScanResult result = scanner.scan(
                scenario("field-removed-without-reserved", "current.proto"),
                scenario("field-removed-without-reserved", "baseline.proto"),
                noneConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("compatible"));
    }
}
