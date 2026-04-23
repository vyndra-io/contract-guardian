package io.contractguardian.rest;

import io.contractguardian.model.ScanResult;
import io.contractguardian.model.Severity;
import io.contractguardian.policy.RestRuleConfig;
import io.contractguardian.policy.SourceConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RestOpenApiScannerTest {

    private final RestOpenApiScanner scanner = new RestOpenApiScanner();
    private final RestRuleConfig defaultConfig = RestRuleConfig.defaultConfig();
    private final SourceConfig restSource = new SourceConfig("rest",
            List.of("api/openapi/**/*.yaml"), "branch:main");

    @Test
    void canScan_yamlInRestSource_returnsTrue() {
        assertThat(scanner.canScan(Path.of("api/openapi/service.yaml"), restSource)).isTrue();
    }

    @Test
    void canScan_yamlInKafkaSource_returnsFalse() {
        SourceConfig kafkaSource = new SourceConfig("kafka",
                List.of("schemas/**/*.avsc"), "branch:main");
        assertThat(scanner.canScan(Path.of("api/openapi/service.yaml"), kafkaSource)).isFalse();
    }

    @Test
    void canScan_avscFile_returnsFalse() {
        assertThat(scanner.canScan(Path.of("schemas/user.avsc"), restSource)).isFalse();
    }

    @Test
    void scan_noBaseline_returnsNewSpecInfo() {
        Path current = Path.of("src/test/resources/openapi-scenarios/endpoint-removed/current.yaml");
        ScanResult result = scanner.scan(current, null, defaultConfig);

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(result.findings().get(0).rule()).isEqualTo("new-spec");
    }

    @Test
    void endpointRemoved_isBreaking() {
        Path baseline = Path.of("src/test/resources/openapi-scenarios/endpoint-removed/baseline.yaml");
        Path current = Path.of("src/test/resources/openapi-scenarios/endpoint-removed/current.yaml");

        ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("endpoint-removed")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("GET /payments/{id}"));
    }

    @Test
    void requiredParamAdded_isBreaking() {
        Path baseline = Path.of("src/test/resources/openapi-scenarios/required-param-added/baseline.yaml");
        Path current = Path.of("src/test/resources/openapi-scenarios/required-param-added/current.yaml");

        ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("required-param-added")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("tenant-id"));
    }

    @Test
    void responseFieldTypeChanged_isBreaking() {
        Path baseline = Path.of("src/test/resources/openapi-scenarios/response-field-type-changed/baseline.yaml");
        Path current = Path.of("src/test/resources/openapi-scenarios/response-field-type-changed/current.yaml");

        ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.hasBreaking()).isTrue();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("response-field-type-changed")
                        && f.severity() == Severity.BREAKING
                        && f.message().contains("total"));
    }

    @Test
    void newEndpointAdded_isInfo() {
        Path baseline = Path.of("src/test/resources/openapi-scenarios/new-endpoint/baseline.yaml");
        Path current = Path.of("src/test/resources/openapi-scenarios/new-endpoint/current.yaml");

        ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.hasBreaking()).isFalse();
        assertThat(result.findings())
                .anyMatch(f -> f.rule().equals("endpoint-added")
                        && f.severity() == Severity.INFO);
    }

    @Test
    void endpointRemoved_ignoredPath_noFindings() {
        Path baseline = Path.of("src/test/resources/openapi-scenarios/endpoint-removed/baseline.yaml");
        Path current = Path.of("src/test/resources/openapi-scenarios/endpoint-removed/current.yaml");

        RestRuleConfig configWithIgnore = new RestRuleConfig(
                defaultConfig.breaking(), defaultConfig.warning(),
                List.of("/payments/{id}"));

        ScanResult result = scanner.scan(current, baseline, configWithIgnore);

        assertThat(result.findings())
                .noneMatch(f -> f.rule().equals("endpoint-removed"));
    }
}
