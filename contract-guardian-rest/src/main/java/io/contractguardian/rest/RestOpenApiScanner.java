package io.contractguardian.rest;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.RestRuleConfig;
import io.contractguardian.policy.RuleConfig;
import io.contractguardian.policy.SourceConfig;
import io.contractguardian.scanner.ContractScanner;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Scans OpenAPI specification files ({@code .yaml} or {@code .json}) for breaking REST API changes.
 *
 * <p>Uses the openapi-diff library to compare the current spec against a baseline,
 * classifying changes according to the {@link RestRuleConfig} rules.
 */
public class RestOpenApiScanner implements ContractScanner {

    private final OpenApiSchemaLoader schemaLoader = new OpenApiSchemaLoader();
    private final OpenApiCompatibilityChecker checker = new OpenApiCompatibilityChecker();

    @Override
    public Set<ContractType> supportedTypes() {
        return Set.of(ContractType.REST_OPENAPI);
    }

    @Override
    public boolean canScan(final Path file, final SourceConfig sourceConfig) {
        if (!"rest".equals(sourceConfig.name())) {
            return false;
        }
        final String name = file.toString();
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }

    @Override
    public ScanResult scan(final Path current, final Path baseline, final RuleConfig config) {
        final Instant start = Instant.now();
        final String filePath = current.toString();

        if (baseline == null) {
            return new ScanResult(filePath, ContractType.REST_OPENAPI,
                    List.of(Finding.info(ContractType.REST_OPENAPI, filePath,
                            "new-spec", "New OpenAPI spec — no baseline to compare")),
                    Duration.between(start, Instant.now()));
        }

        final RestRuleConfig restConfig = (config instanceof RestRuleConfig rc)
                ? rc : RestRuleConfig.defaultConfig();

        final String currentContent = schemaLoader.load(current);
        final String baselineContent = schemaLoader.load(baseline);

        List<Finding> findings = checker.check(currentContent, baselineContent, restConfig, filePath);

        if (findings.isEmpty()) {
            findings = List.of(Finding.info(ContractType.REST_OPENAPI, filePath,
                    "compatible", "No breaking changes detected"));
        }

        return new ScanResult(filePath, ContractType.REST_OPENAPI,
                findings, Duration.between(start, Instant.now()));
    }
}
