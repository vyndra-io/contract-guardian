package io.contractguardian.kafka.avro;

import io.contractguardian.model.Finding;
import io.contractguardian.policy.KafkaRuleConfig.CompatibilityMode;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.Incompatibility;
import org.apache.avro.SchemaCompatibility.SchemaPairCompatibility;

import java.util.ArrayList;
import java.util.List;

import static org.apache.avro.SchemaCompatibility.SchemaCompatibilityType.INCOMPATIBLE;

/**
 * Checks compatibility between two Avro schemas using the Apache Avro library.
 */
public class AvroCompatibilityChecker {

    private final AvroFindingFactory findingFactory = new AvroFindingFactory();

    /**
     * Checks compatibility between the current and baseline schemas.
     *
     * @param current  the new schema version
     * @param baseline the previous schema version
     * @param mode     the compatibility mode to check
     * @param filePath the file path for reporting purposes
     * @return a list of findings, empty if compatible
     */
    public List<Finding> check(final Schema current, final Schema baseline,
                               final CompatibilityMode mode, final String filePath) {
        final List<Finding> findings = new ArrayList<>();

        if (mode == CompatibilityMode.NONE) {
            return findings;
        }

        if (mode == CompatibilityMode.BACKWARD || mode == CompatibilityMode.FULL) {
            final SchemaPairCompatibility result =
                    SchemaCompatibility.checkReaderWriterCompatibility(current, baseline);
            if (result.getType() == INCOMPATIBLE) {
                for (final Incompatibility incompat : result.getResult().getIncompatibilities()) {
                    findings.add(findingFactory.fromIncompatibility(incompat, filePath, "backward"));
                }
            }
        }

        if (mode == CompatibilityMode.FORWARD || mode == CompatibilityMode.FULL) {
            final SchemaPairCompatibility result =
                    SchemaCompatibility.checkReaderWriterCompatibility(baseline, current);
            if (result.getType() == INCOMPATIBLE) {
                for (final Incompatibility incompat : result.getResult().getIncompatibilities()) {
                    findings.add(findingFactory.fromIncompatibility(incompat, filePath, "forward"));
                }
            }
        }

        return findings;
    }
}
