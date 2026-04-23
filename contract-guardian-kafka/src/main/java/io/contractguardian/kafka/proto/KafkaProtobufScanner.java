package io.contractguardian.kafka.proto;

import com.squareup.wire.schema.internal.parser.ProtoFileElement;
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
 * Scans Protobuf schema files ({@code .proto}) for breaking changes.
 *
 * <p>Uses the Wire schema parser to detect field removals without {@code reserved} declarations,
 * field type changes, tag number reuse, and enum value removals.
 */
public class KafkaProtobufScanner implements ContractScanner {

    private final ProtobufSchemaLoader schemaLoader = new ProtobufSchemaLoader();
    private final ProtobufCompatibilityChecker checker = new ProtobufCompatibilityChecker();

    @Override
    public Set<ContractType> supportedTypes() {
        return Set.of(ContractType.KAFKA_PROTOBUF);
    }

    @Override
    public boolean canScan(final Path file, final SourceConfig sourceConfig) {
        return file.toString().endsWith(".proto");
    }

    @Override
    public ScanResult scan(final Path current, final Path baseline, final RuleConfig config) {
        final Instant start = Instant.now();
        final String filePath = current.toString();

        if (baseline == null) {
            return new ScanResult(filePath, ContractType.KAFKA_PROTOBUF,
                    List.of(Finding.info(ContractType.KAFKA_PROTOBUF, filePath,
                            "new-schema", "New Protobuf schema — no baseline to compare")),
                    Duration.between(start, Instant.now()));
        }

        final KafkaRuleConfig kafkaConfig = (config instanceof KafkaRuleConfig kc)
                ? kc : KafkaRuleConfig.defaultConfig();

        final ProtoFileElement currentSchema = schemaLoader.load(current);
        final ProtoFileElement baselineSchema = schemaLoader.load(baseline);

        final KafkaRuleConfig.CompatibilityMode mode = kafkaConfig.compatibilityFor(filePath);
        List<Finding> findings = checker.check(currentSchema, baselineSchema, mode, filePath);

        if (findings.isEmpty()) {
            findings = List.of(Finding.info(ContractType.KAFKA_PROTOBUF, filePath,
                    "compatible", "Protobuf schema change is compatible"));
        }

        return new ScanResult(filePath, ContractType.KAFKA_PROTOBUF,
                findings, Duration.between(start, Instant.now()));
    }
}
