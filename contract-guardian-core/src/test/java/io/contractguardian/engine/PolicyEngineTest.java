package io.contractguardian.engine;

import io.contractguardian.model.*;
import io.contractguardian.policy.GateConfig;
import io.contractguardian.policy.KafkaRuleConfig;
import io.contractguardian.policy.PolicyConfig;
import io.contractguardian.policy.SourceConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    private PolicyConfig configWithBlockOn(GateConfig.BlockOn blockOn) {
        return new PolicyConfig("1",
                Map.of("kafka", new SourceConfig("kafka", List.of("**/*.avsc"), "branch:main")),
                new LinkedHashMap<>(),
                new GateConfig(blockOn, false, null, List.of()));
    }

    private ScanResult breakingResult() {
        return new ScanResult("test.avsc", ContractType.KAFKA_AVRO,
                List.of(Finding.breaking(ContractType.KAFKA_AVRO, "test.avsc", "rule", "msg")),
                Duration.ofMillis(10));
    }

    private ScanResult warningResult() {
        return new ScanResult("test.avsc", ContractType.KAFKA_AVRO,
                List.of(Finding.warning(ContractType.KAFKA_AVRO, "test.avsc", "rule", "msg")),
                Duration.ofMillis(10));
    }

    private ScanResult passResult() {
        return new ScanResult("test.avsc", ContractType.KAFKA_AVRO,
                List.of(Finding.info(ContractType.KAFKA_AVRO, "test.avsc", "compatible", "ok")),
                Duration.ofMillis(10));
    }

    @Test
    void allBreaking_blockOnBreaking_fail() {
        PolicyEngine engine = new PolicyEngine(configWithBlockOn(GateConfig.BlockOn.BREAKING));
        Verdict verdict = engine.evaluate(List.of(breakingResult()), Duration.ofMillis(100));
        assertThat(verdict.status()).isEqualTo(VerdictStatus.FAIL);
        assertThat(verdict.exitCode()).isEqualTo(1);
    }

    @Test
    void onlyWarnings_blockOnBreaking_warn() {
        PolicyEngine engine = new PolicyEngine(configWithBlockOn(GateConfig.BlockOn.BREAKING));
        Verdict verdict = engine.evaluate(List.of(warningResult()), Duration.ofMillis(100));
        assertThat(verdict.status()).isEqualTo(VerdictStatus.WARN);
        assertThat(verdict.exitCode()).isEqualTo(0);
    }

    @Test
    void onlyWarnings_blockOnWarning_fail() {
        PolicyEngine engine = new PolicyEngine(configWithBlockOn(GateConfig.BlockOn.WARNING));
        Verdict verdict = engine.evaluate(List.of(warningResult()), Duration.ofMillis(100));
        assertThat(verdict.status()).isEqualTo(VerdictStatus.FAIL);
    }

    @Test
    void noFindings_pass() {
        PolicyEngine engine = new PolicyEngine(configWithBlockOn(GateConfig.BlockOn.BREAKING));
        Verdict verdict = engine.evaluate(List.of(), Duration.ofMillis(100));
        assertThat(verdict.status()).isEqualTo(VerdictStatus.PASS);
    }

    @Test
    void passResults_blockOnBreaking_pass() {
        PolicyEngine engine = new PolicyEngine(configWithBlockOn(GateConfig.BlockOn.BREAKING));
        Verdict verdict = engine.evaluate(List.of(passResult()), Duration.ofMillis(100));
        assertThat(verdict.status()).isEqualTo(VerdictStatus.PASS);
    }

    @Test
    void infoFindings_blockOnAny_fail() {
        PolicyEngine engine = new PolicyEngine(configWithBlockOn(GateConfig.BlockOn.ANY));
        Verdict verdict = engine.evaluate(List.of(passResult()), Duration.ofMillis(100));
        assertThat(verdict.status()).isEqualTo(VerdictStatus.FAIL);
    }
}
