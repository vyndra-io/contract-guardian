package io.contractguardian.kafka.json;

import com.fasterxml.jackson.databind.JsonNode;
import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.KafkaRuleConfig;
import io.contractguardian.policy.RuleConfig;
import io.contractguardian.policy.SourceConfig;
import io.contractguardian.scanner.ContractScanner;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Scans JSON Schema files ({@code .json}) used with Kafka topics for compatibility issues.
 *
 * <p>Uses a structural diff approach to detect removed properties, required property additions,
 * type changes, and enum value removals.
 */
public class KafkaJsonSchemaScanner implements ContractScanner {

    private final JsonSchemaLoader schemaLoader = new JsonSchemaLoader();
    private final JsonSchemaCompatibilityChecker checker = new JsonSchemaCompatibilityChecker();

    @Override
    public Set<ContractType> supportedTypes() {
        return Set.of(ContractType.KAFKA_JSON_SCHEMA);
    }

    @Override
    public boolean canScan(final Path file, final SourceConfig sourceConfig) {
        if (!file.toString().endsWith(".json")) {
            return false;
        }
        try {
            final JsonNode node = schemaLoader.load(file);
            return schemaLoader.isJsonSchema(node);
        } catch (JsonSchemaLoader.JsonScanException e) {
            return false;
        }
    }

    @Override
    public ScanResult scan(final Path current, final Path baseline, final RuleConfig config) {
        final Instant start = Instant.now();
        final String filePath = current.toString();

        if (baseline == null) {
            return new ScanResult(filePath, ContractType.KAFKA_JSON_SCHEMA,
                    List.of(Finding.info(ContractType.KAFKA_JSON_SCHEMA, filePath,
                            "new-schema", "New JSON Schema — no baseline to compare")),
                    Duration.between(start, Instant.now()));
        }

        final KafkaRuleConfig kafkaConfig = (config instanceof KafkaRuleConfig kc)
                ? kc : KafkaRuleConfig.defaultConfig();

        final JsonNode currentSchema = schemaLoader.load(current);
        final JsonNode baselineSchema = schemaLoader.load(baseline);

        final KafkaRuleConfig.CompatibilityMode mode = kafkaConfig.compatibilityFor(filePath);
        List<Finding> findings = checker.check(currentSchema, baselineSchema, mode, filePath);

        if (findings.isEmpty()) {
            findings = List.of(Finding.info(ContractType.KAFKA_JSON_SCHEMA, filePath,
                    "compatible", "JSON Schema change is compatible"));
        }

        return new ScanResult(filePath, ContractType.KAFKA_JSON_SCHEMA,
                findings, Duration.between(start, Instant.now()));
    }
}
