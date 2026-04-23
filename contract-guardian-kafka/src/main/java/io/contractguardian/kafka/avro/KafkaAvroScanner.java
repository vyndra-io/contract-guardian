package io.contractguardian.kafka.avro;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.KafkaRuleConfig;
import io.contractguardian.policy.RuleConfig;
import io.contractguardian.policy.SourceConfig;
import io.contractguardian.scanner.ContractScanner;
import org.apache.avro.Schema;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Scans Apache Avro schema files ({@code .avsc}) for compatibility issues.
 *
 * <p>Uses the Avro {@link org.apache.avro.SchemaCompatibility} API to check
 * backward, forward, and full compatibility between schema versions.
 */
public class KafkaAvroScanner implements ContractScanner {

    private final AvroSchemaLoader schemaLoader = new AvroSchemaLoader();
    private final AvroCompatibilityChecker checker = new AvroCompatibilityChecker();

    @Override
    public Set<ContractType> supportedTypes() {
        return Set.of(ContractType.KAFKA_AVRO);
    }

    @Override
    public boolean canScan(final Path file, final SourceConfig sourceConfig) {
        return file.toString().endsWith(".avsc");
    }

    @Override
    public ScanResult scan(final Path current, final Path baseline, final RuleConfig config) {
        final Instant start = Instant.now();
        final String filePath = current.toString();

        if (baseline == null) {
            return new ScanResult(filePath, ContractType.KAFKA_AVRO,
                    List.of(Finding.info(ContractType.KAFKA_AVRO, filePath,
                            "new-schema", "New schema file — no baseline to compare")),
                    Duration.between(start, Instant.now()));
        }

        final KafkaRuleConfig kafkaConfig = (config instanceof KafkaRuleConfig kc)
                ? kc : KafkaRuleConfig.defaultConfig();

        final Schema currentSchema = schemaLoader.load(current);
        final Schema baselineSchema = schemaLoader.load(baseline);

        final KafkaRuleConfig.CompatibilityMode mode = kafkaConfig.compatibilityFor(filePath);
        List<Finding> findings = checker.check(currentSchema, baselineSchema, mode, filePath);

        if (findings.isEmpty()) {
            findings = List.of(Finding.info(ContractType.KAFKA_AVRO, filePath,
                    "compatible", "Schema change is compatible"));
        }

        return new ScanResult(filePath, ContractType.KAFKA_AVRO,
                findings, Duration.between(start, Instant.now()));
    }
}
