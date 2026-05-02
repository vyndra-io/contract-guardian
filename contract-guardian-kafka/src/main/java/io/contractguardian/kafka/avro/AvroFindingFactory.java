package io.contractguardian.kafka.avro;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.model.Severity;
import org.apache.avro.SchemaCompatibility.Incompatibility;
import org.apache.avro.SchemaCompatibility.SchemaIncompatibilityType;

/**
 * Converts Avro {@link Incompatibility} objects into human-readable {@link Finding} records.
 */
public class AvroFindingFactory {

    /**
     * Creates a finding from an Avro incompatibility.
     *
     * @param incompat  the Avro incompatibility
     * @param filePath  the file path for reporting
     * @param direction the compatibility direction ("backward" or "forward")
     * @return a breaking finding with a human-readable message and optional fix suggestion
     */
    public Finding fromIncompatibility(final Incompatibility incompat,
                                       final String filePath, final String direction) {
        final SchemaIncompatibilityType type = incompat.getType();
        final String location = incompat.getLocation();
        final String message = incompat.getMessage();

        final String fieldName = message != null && !message.isBlank() ? message : location;
        final String humanMessage = switch (type) {
            case READER_FIELD_MISSING_DEFAULT_VALUE ->
                    String.format("Field '%s' has no default value — breaks %s compatibility",
                            fieldName, direction);
            case TYPE_MISMATCH ->
                    String.format("Type changed at '%s' — breaks %s compatibility: %s",
                            location, direction, message);
            case NAME_MISMATCH ->
                    String.format("Name changed at '%s' — breaks %s compatibility",
                            location, direction);
            case FIXED_SIZE_MISMATCH ->
                    String.format("Fixed size changed at '%s' — breaks %s compatibility",
                            location, direction);
            case MISSING_ENUM_SYMBOLS ->
                    String.format("Enum symbols removed at '%s' — breaks %s compatibility: %s",
                            location, direction, message);
            case MISSING_UNION_BRANCH ->
                    String.format("Union branch removed at '%s' — breaks %s compatibility: %s",
                            location, direction, message);
            default ->
                    String.format("%s incompatibility at '%s': %s", direction, location, message);
        };

        final String fix = suggestFix(type, direction);
        final String rule = type.name().toLowerCase().replace('_', '-');

        return new Finding(ContractType.KAFKA_AVRO, Severity.BREAKING,
                filePath, -1, rule, humanMessage, message, fix);
    }

    private String suggestFix(final SchemaIncompatibilityType type, final String direction) {
        return switch (type) {
            case READER_FIELD_MISSING_DEFAULT_VALUE ->
                    "Add a \"default\" to the field declaration (e.g. \"default\": null) " +
                            "so existing consumers can safely skip it";
            case TYPE_MISMATCH ->
                    "Use a union type to support both old and new types during migration";
            case MISSING_ENUM_SYMBOLS ->
                    "Enum symbols can only be added, not removed, for " + direction + " compatibility. " +
                            "Consider adding a new enum type if the values need to change";
            case MISSING_UNION_BRANCH ->
                    "Union branches can only be added for backward compatibility. " +
                            "Consider using a wider union type";
            default -> null;
        };
    }
}
