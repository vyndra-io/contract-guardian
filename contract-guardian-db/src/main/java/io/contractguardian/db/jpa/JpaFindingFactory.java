package io.contractguardian.db.jpa;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;

/**
 * Factory for creating {@link Finding} instances from JPA entity analysis.
 */
final class JpaFindingFactory {

    private JpaFindingFactory() {
    }

    /**
     * Creates a breaking finding for a removed column-mapped field.
     *
     * @param file   the entity source file path
     * @param entity the entity class name
     * @param field  the removed field name
     * @param column the mapped column name
     * @return a breaking finding
     */
    static Finding columnRemoved(final String file, final String entity,
                                 final String field, final String column) {
        return Finding.breaking(
                ContractType.DB_JPA_ENTITY, file, "column-removed",
                "Field '" + entity + "." + field + "' removed — mapped column '" + column
                        + "' will no longer be read or written",
                "Removing a mapped field breaks any code path that depends on this column",
                "Deprecate the field first; remove after all consumers have been updated");
    }

    /**
     * Creates a breaking finding for a JSONB field that was removed.
     *
     * @param file   the entity source file path
     * @param entity the entity class name
     * @param field  the removed field name
     * @return a breaking finding
     */
    static Finding jsonbFieldRemoved(final String file, final String entity, final String field) {
        return Finding.breaking(
                ContractType.DB_JPA_ENTITY, file, "jsonb-field-removed",
                "JSONB field '" + entity + "." + field + "' removed — consumers reading this JSONB shape will break",
                "Removing a JSONB-mapped field skips the contract step of expand-migrate-contract",
                "Follow expand-migrate-contract: ensure all consumers have stopped reading this field before removing it");
    }

    /**
     * Creates a breaking finding for a NOT NULL constraint added without a default.
     *
     * @param file   the entity source file path
     * @param entity the entity class name
     * @param field  the affected field name
     * @param column the mapped column name
     * @return a breaking finding
     */
    static Finding notNullAddedNoDefault(final String file, final String entity,
                                         final String field, final String column) {
        return Finding.breaking(
                ContractType.DB_JPA_ENTITY, file, "not-null-added-no-default",
                "Field '" + entity + "." + field + "' changed to nullable=false on column '"
                        + column + "' — existing rows without a value will violate the constraint",
                "Setting nullable=false without a DEFAULT means old application versions cannot insert rows",
                "Add a @ColumnDefault or supply a DEFAULT in a migration before enforcing NOT NULL");
    }

    /**
     * Creates a warning finding for a renamed column.
     *
     * @param file      the entity source file path
     * @param entity    the entity class name
     * @param field     the field name
     * @param oldColumn the old column name
     * @param newColumn the new column name
     * @return a warning finding
     */
    static Finding columnRenamed(final String file, final String entity, final String field,
                                 final String oldColumn, final String newColumn) {
        return Finding.warning(
                ContractType.DB_JPA_ENTITY, file, "column-renamed",
                "Field '" + entity + "." + field + "' column renamed from '" + oldColumn
                        + "' to '" + newColumn + "' — any query referencing the old name will break",
                "Column renames require simultaneous updates to all consumers",
                "Add the new column, copy data, update consumers, then drop the old column");
    }

    /**
     * Creates a breaking finding for a field type change within a JSONB value class.
     *
     * @param file      the source file path
     * @param className the JSONB value class name
     * @param field     the field name
     * @param oldType   the previous field type
     * @param newType   the new field type
     * @return a breaking finding
     */
    static Finding jsonbValueFieldTypeChanged(final String file, final String className,
                                              final String field,
                                              final String oldType, final String newType) {
        return Finding.breaking(
                ContractType.DB_JSONB, file, "jsonb-field-type-changed",
                "JSONB value class '" + className + "." + field + "' type changed from '"
                        + oldType + "' to '" + newType
                        + "' — deserialisation will fail for stored documents with the old type",
                "Type changes in a JSONB value class break existing stored documents and any consumer "
                        + "that reads this field",
                "Add a new field with the new type, migrate stored data, then remove the old field");
    }

    /**
     * Creates a warning finding when a JSONB field uses an untyped {@code Map<>},
     * meaning its schema cannot be statically enforced.
     *
     * @param file      the source file path
     * @param className the entity or value class name
     * @param field     the field name
     * @return a warning finding
     */
    static Finding jsonbSchemaUnenforceable(final String file, final String className,
                                            final String field) {
        return Finding.warning(
                ContractType.DB_JSONB, file, "jsonb-schema-unenforceable",
                "JSONB field '" + className + "." + field
                        + "' is typed as Map<> — schema is dynamic and cannot be statically verified",
                "Map<> JSONB fields have no fixed schema; breaking changes to stored keys are invisible "
                        + "to static analysis",
                "Consider replacing Map<> with a typed value class so field-level changes can be detected");
    }
}
