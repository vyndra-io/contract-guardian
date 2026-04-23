package io.contractguardian.kafka.proto;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;

/**
 * Creates {@link Finding} records for Protobuf schema compatibility issues.
 */
public class ProtobufFindingFactory {

    /**
     * Creates a breaking finding for a removed field that is not in a {@code reserved} statement.
     *
     * @param fieldName   the removed field name
     * @param messageName the parent message name
     * @param tag         the field tag number
     * @param filePath    the file path for reporting
     * @return a breaking finding
     */
    public Finding fieldRemovedWithoutReserved(final String fieldName, final String messageName,
                                               final int tag, final String filePath) {
        return Finding.breaking(ContractType.KAFKA_PROTOBUF, filePath,
                "field-removed-without-reserved",
                String.format("Field '%s' (tag %d) removed from message '%s' without reserved declaration",
                        fieldName, tag, messageName),
                "Field removal without reserved allows tag reuse, breaking binary deserialization",
                String.format("Add 'reserved %d; reserved \"%s\";' to message '%s' before removing the field",
                        tag, fieldName, messageName));
    }

    /**
     * Creates a breaking finding for a field type change.
     *
     * @param fieldName   the changed field name
     * @param messageName the parent message name
     * @param oldType     the old field type
     * @param newType     the new field type
     * @param filePath    the file path for reporting
     * @return a breaking finding
     */
    public Finding fieldTypeChanged(final String fieldName, final String messageName,
                                    final String oldType, final String newType, final String filePath) {
        return Finding.breaking(ContractType.KAFKA_PROTOBUF, filePath,
                "field-type-changed",
                String.format("Field '%s' in message '%s' type changed: %s → %s",
                        fieldName, messageName, oldType, newType),
                "Changing a field type breaks binary wire compatibility",
                "Deprecate the existing field and add a new field with a new tag number");
    }

    /**
     * Creates a breaking finding when a field tag number is reused.
     *
     * @param tag         the reused tag number
     * @param messageName the parent message name
     * @param oldName     the field name that previously owned this tag
     * @param newName     the field name that now owns this tag
     * @param filePath    the file path for reporting
     * @return a breaking finding
     */
    public Finding tagReused(final int tag, final String messageName,
                             final String oldName, final String newName, final String filePath) {
        return Finding.breaking(ContractType.KAFKA_PROTOBUF, filePath,
                "tag-reused",
                String.format("Tag %d reused in message '%s': was '%s', now '%s'",
                        tag, messageName, oldName, newName),
                "Reusing a tag number with a different field type or name corrupts existing serialized data",
                "Use a new unique tag number for the new field; mark the old tag as reserved");
    }

    /**
     * Creates a breaking finding for a removed enum value.
     *
     * @param valueName  the removed enum value name
     * @param enumName   the parent enum name
     * @param number     the enum value number
     * @param filePath   the file path for reporting
     * @return a breaking finding
     */
    public Finding enumValueRemoved(final String valueName, final String enumName,
                                    final int number, final String filePath) {
        return Finding.breaking(ContractType.KAFKA_PROTOBUF, filePath,
                "enum-value-removed",
                String.format("Enum value '%s' (number %d) removed from '%s' — consumers using this value will fail",
                        valueName, number, enumName),
                "Removing enum values breaks deserializers expecting those values",
                String.format("Add 'reserved %d; reserved \"%s\";' to enum '%s' instead of removing",
                        number, valueName, enumName));
    }
}
