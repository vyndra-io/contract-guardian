package io.contractguardian.kafka.json;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;

/**
 * Creates {@link Finding} records for JSON Schema compatibility issues.
 */
public class JsonSchemaFindingFactory {

    /**
     * Creates a breaking finding for a removed property.
     *
     * @param property the name of the removed property
     * @param filePath the file path for reporting
     * @return a breaking finding
     */
    public Finding propertyRemoved(final String property, final String filePath) {
        return Finding.breaking(ContractType.KAFKA_JSON_SCHEMA, filePath,
                "property-removed",
                String.format("Property '%s' removed — consumers expecting this field will break", property),
                null,
                "Mark the property as deprecated before removing it in a future version");
    }

    /**
     * Creates a breaking finding for a new required property.
     *
     * @param property the name of the new required property
     * @param filePath the file path for reporting
     * @return a breaking finding
     */
    public Finding requiredPropertyAdded(final String property, final String filePath) {
        return Finding.breaking(ContractType.KAFKA_JSON_SCHEMA, filePath,
                "required-property-added",
                String.format("Required property '%s' added — existing messages without this field will be invalid",
                        property),
                null,
                "Make the property optional or provide a default value");
    }

    /**
     * Creates a breaking finding for a property type change.
     *
     * @param property the name of the changed property
     * @param oldType  the old type
     * @param newType  the new type
     * @param filePath the file path for reporting
     * @return a breaking finding
     */
    public Finding propertyTypeChanged(final String property, final String oldType,
                                       final String newType, final String filePath) {
        return Finding.breaking(ContractType.KAFKA_JSON_SCHEMA, filePath,
                "property-type-changed",
                String.format("Property '%s' type changed: %s → %s", property, oldType, newType),
                null,
                "Introduce a new field with the new type rather than changing the existing field type");
    }

    /**
     * Creates a breaking finding for a removed enum value.
     *
     * @param value    the removed enum value
     * @param property the property whose enum lost the value
     * @param filePath the file path for reporting
     * @return a breaking finding
     */
    public Finding enumValueRemoved(final String value, final String property, final String filePath) {
        return Finding.breaking(ContractType.KAFKA_JSON_SCHEMA, filePath,
                "enum-value-removed",
                String.format("Enum value '%s' removed from property '%s' — consumers using this value will break",
                        value, property),
                null,
                "Enum values can only be added, not removed, for backward compatibility");
    }
}
