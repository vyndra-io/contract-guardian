package io.contractguardian.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanResultTest {

    @Test
    void hasBreaking_true() {
        ScanResult result = new ScanResult("test.avsc", ContractType.KAFKA_AVRO,
                List.of(Finding.breaking(ContractType.KAFKA_AVRO, "test.avsc", "rule", "msg")),
                Duration.ofMillis(10));
        assertThat(result.hasBreaking()).isTrue();
    }

    @Test
    void hasBreaking_false() {
        ScanResult result = new ScanResult("test.avsc", ContractType.KAFKA_AVRO,
                List.of(Finding.info(ContractType.KAFKA_AVRO, "test.avsc", "rule", "msg")),
                Duration.ofMillis(10));
        assertThat(result.hasBreaking()).isFalse();
    }

    @Test
    void hasWarnings_true() {
        ScanResult result = new ScanResult("test.avsc", ContractType.KAFKA_AVRO,
                List.of(Finding.warning(ContractType.KAFKA_AVRO, "test.avsc", "rule", "msg")),
                Duration.ofMillis(10));
        assertThat(result.hasWarnings()).isTrue();
    }

    @Test
    void findingsListIsImmutable() {
        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.info(ContractType.KAFKA_AVRO, "test.avsc", "rule", "msg"));
        ScanResult result = new ScanResult("test.avsc", ContractType.KAFKA_AVRO, findings, Duration.ofMillis(10));

        assertThatThrownBy(() -> result.findings().add(
                Finding.breaking(ContractType.KAFKA_AVRO, "test.avsc", "rule", "msg")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
