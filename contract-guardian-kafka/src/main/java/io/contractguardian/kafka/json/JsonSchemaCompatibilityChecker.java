package io.contractguardian.kafka.json;

import com.fasterxml.jackson.databind.JsonNode;
import io.contractguardian.model.Finding;
import io.contractguardian.policy.KafkaRuleConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks compatibility between two JSON Schema versions using structural diff.
 *
 * <p>Applies rules based on the {@link KafkaRuleConfig.CompatibilityMode}:
 * <ul>
 *   <li><b>BACKWARD</b>: new schema can read data written by old schema.
 *       Checks that no properties are removed and no properties become required.</li>
 *   <li><b>FORWARD</b>: old schema can read data written by new schema.
 *       Checks that new required properties are not added.</li>
 *   <li><b>FULL</b>: both backward and forward checks.</li>
 * </ul>
 */
public class JsonSchemaCompatibilityChecker {

    private final JsonSchemaFindingFactory findingFactory = new JsonSchemaFindingFactory();

    /**
     * Checks compatibility between the current and baseline JSON Schemas.
     *
     * @param current  the new schema version
     * @param baseline the previous schema version
     * @param mode     the compatibility mode to enforce
     * @param filePath the file path for reporting purposes
     * @return a list of findings, empty if compatible
     */
    public List<Finding> check(final JsonNode current, final JsonNode baseline,
                               final KafkaRuleConfig.CompatibilityMode mode,
                               final String filePath) {
        final List<Finding> findings = new ArrayList<>();

        if (mode == KafkaRuleConfig.CompatibilityMode.NONE) {
            return findings;
        }

        if (mode == KafkaRuleConfig.CompatibilityMode.BACKWARD
                || mode == KafkaRuleConfig.CompatibilityMode.FULL) {
            findings.addAll(checkBackward(current, baseline, filePath));
        }

        if (mode == KafkaRuleConfig.CompatibilityMode.FORWARD
                || mode == KafkaRuleConfig.CompatibilityMode.FULL) {
            findings.addAll(checkForward(current, baseline, filePath));
        }

        return findings;
    }

    /**
     * Backward check: new schema can read old data.
     * Old data won't have new required properties.
     * Old data may have properties that are removed in the new schema — consumers
     * depending on those fields will break.
     */
    private List<Finding> checkBackward(final JsonNode current, final JsonNode baseline,
                                        final String filePath) {
        final List<Finding> findings = new ArrayList<>();
        checkProperties(current, baseline, filePath, findings, true);
        return findings;
    }

    /**
     * Forward check: old schema can read new data.
     * New data won't have properties that old consumers expect.
     */
    private List<Finding> checkForward(final JsonNode current, final JsonNode baseline,
                                       final String filePath) {
        final List<Finding> findings = new ArrayList<>();
        checkProperties(baseline, current, filePath, findings, false);
        return findings;
    }

    /**
     * Performs the property-level compatibility check between reader and writer schemas.
     *
     * @param reader     the schema acting as the reader
     * @param writer     the schema acting as the writer
     * @param filePath   the file path for reporting
     * @param findings   the list to add findings to
     * @param isBackward {@code true} for backward direction (used for message clarity)
     */
    private void checkProperties(final JsonNode reader, final JsonNode writer,
                                 final String filePath, final List<Finding> findings,
                                 final boolean isBackward) {
        final JsonNode readerProps = reader.path("properties");
        final JsonNode writerProps = writer.path("properties");

        if (readerProps.isMissingNode() || writerProps.isMissingNode()) {
            return;
        }

        if (isBackward) {
            // Backward: check properties removed from new schema that existed in old schema
            checkRemovedProperties(readerProps, writerProps, filePath, findings);
            // Backward: check enum values removed in new schema
            checkEnumValues(readerProps, writerProps, filePath, findings);
            // Backward: check type changes in properties
            checkTypeChanges(readerProps, writerProps, filePath, findings);
        } else {
            // Forward: check new required properties added to new schema that old readers won't send
            checkNewRequiredProperties(reader, writer, writerProps, filePath, findings);
        }
    }

    private void checkRemovedProperties(final JsonNode current, final JsonNode baseline,
                                        final String filePath, final List<Finding> findings) {
        baseline.fieldNames().forEachRemaining(name -> {
            if (!current.has(name)) {
                findings.add(findingFactory.propertyRemoved(name, filePath));
            }
        });
    }

    private void checkTypeChanges(final JsonNode current, final JsonNode baseline,
                                  final String filePath, final List<Finding> findings) {
        baseline.fieldNames().forEachRemaining(name -> {
            if (!current.has(name)) {
                return;
            }
            final String oldType = getType(baseline.get(name));
            final String newType = getType(current.get(name));
            if (oldType != null && newType != null && !oldType.equals(newType)) {
                findings.add(findingFactory.propertyTypeChanged(name, oldType, newType, filePath));
            }
        });
    }

    private void checkEnumValues(final JsonNode current, final JsonNode baseline,
                                 final String filePath, final List<Finding> findings) {
        baseline.fieldNames().forEachRemaining(name -> {
            if (!current.has(name)) {
                return;
            }
            final JsonNode baselineEnum = baseline.get(name).path("enum");
            final JsonNode currentEnum = current.get(name).path("enum");
            if (baselineEnum.isMissingNode() || !baselineEnum.isArray()) {
                return;
            }

            final Set<String> currentValues = new HashSet<>();
            if (!currentEnum.isMissingNode() && currentEnum.isArray()) {
                currentEnum.forEach(v -> currentValues.add(v.asText()));
            }

            baselineEnum.forEach(v -> {
                if (!currentValues.contains(v.asText())) {
                    findings.add(findingFactory.enumValueRemoved(v.asText(), name, filePath));
                }
            });
        });
    }

    private void checkNewRequiredProperties(final JsonNode reader, final JsonNode writer,
                                            final JsonNode writerProps, final String filePath,
                                            final List<Finding> findings) {
        final JsonNode writerRequired = writer.path("required");
        final JsonNode readerRequired = reader.path("required");

        if (writerRequired.isMissingNode() || !writerRequired.isArray()) {
            return;
        }

        final Set<String> readerRequiredSet = new HashSet<>();
        if (!readerRequired.isMissingNode() && readerRequired.isArray()) {
            readerRequired.forEach(v -> readerRequiredSet.add(v.asText()));
        }

        final Set<String> readerProps = new HashSet<>();
        reader.path("properties").fieldNames().forEachRemaining(readerProps::add);

        writerRequired.forEach(v -> {
            final String name = v.asText();
            // It's breaking if the writer requires a property that the reader doesn't know about
            if (!readerProps.contains(name) && !readerRequiredSet.contains(name)) {
                findings.add(findingFactory.requiredPropertyAdded(name, filePath));
            }
        });
    }

    private String getType(final JsonNode propertyNode) {
        if (propertyNode == null) {
            return null;
        }
        final JsonNode typeNode = propertyNode.path("type");
        return typeNode.isMissingNode() ? null : typeNode.asText();
    }
}
