package io.contractguardian.db.liquibase;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;

/**
 * Factory for creating {@link Finding} instances from Liquibase changeset analysis.
 */
final class LiquibaseFindingFactory {

    private LiquibaseFindingFactory() {
    }

    /**
     * Creates a breaking finding for a {@code dropColumn} change.
     *
     * @param file       the changelog file path
     * @param changesetId the changeset id for context
     * @param table      the table name
     * @param column     the dropped column name
     * @return a breaking finding
     */
    static Finding columnRemoved(final String file, final String changesetId,
                                 final String table, final String column) {
        return Finding.breaking(
                ContractType.DB_MIGRATION, file, "column-removed",
                "Changeset '" + changesetId + "': dropColumn '" + table + "." + column
                        + "' — any consumer referencing this column will fail at runtime",
                "Column drops break all queries, JPA mappings, and application code that reads this column",
                "Deprecate first: remove all references, then drop in a follow-up migration");
    }

    /**
     * Creates a breaking finding for a {@code dropTable} change.
     *
     * @param file        the changelog file path
     * @param changesetId the changeset id for context
     * @param table       the dropped table name
     * @return a breaking finding
     */
    static Finding tableRemoved(final String file, final String changesetId, final String table) {
        return Finding.breaking(
                ContractType.DB_MIGRATION, file, "table-removed",
                "Changeset '" + changesetId + "': dropTable '" + table
                        + "' — all consumers of this table will fail",
                "Dropping a table is irreversible and breaks any query or JPA entity that references it",
                "Ensure all services have removed references to this table before dropping it");
    }

    /**
     * Creates a breaking finding for a column added as NOT NULL without a default value.
     *
     * @param file        the changelog file path
     * @param changesetId the changeset id for context
     * @param table       the table name
     * @param column      the column name
     * @return a breaking finding
     */
    static Finding notNullAddedNoDefault(final String file, final String changesetId,
                                          final String table, final String column) {
        return Finding.breaking(
                ContractType.DB_MIGRATION, file, "not-null-added-no-default",
                "Changeset '" + changesetId + "': column '" + table + "." + column
                        + "' is NOT NULL with no defaultValue — existing rows and old app versions will fail",
                "Without a default, Liquibase cannot populate existing rows and old INSERT statements will violate the constraint",
                "Add a defaultValue to the column definition, or follow expand-migrate-contract: "
                        + "add nullable, backfill, then add the constraint in a separate step");
    }

    /**
     * Creates a warning finding for a {@code renameColumn} change.
     *
     * @param file        the changelog file path
     * @param changesetId the changeset id for context
     * @param table       the table name
     * @param oldName     the original column name
     * @param newName     the new column name
     * @return a warning finding
     */
    static Finding columnRenamed(final String file, final String changesetId,
                                  final String table, final String oldName, final String newName) {
        return Finding.warning(
                ContractType.DB_MIGRATION, file, "column-renamed",
                "Changeset '" + changesetId + "': renameColumn '" + table + "." + oldName
                        + "' → '" + newName + "' — any consumer using the old name will break",
                "Column renames require simultaneous updates to all consumers",
                "Add the new column, copy data, update all consumers, then drop the old column");
    }

    /**
     * Creates a breaking finding for a {@code modifyDataType} change.
     *
     * @param file        the changelog file path
     * @param changesetId the changeset id for context
     * @param table       the table name
     * @param column      the column name
     * @param newType     the new data type
     * @return a breaking finding
     */
    static Finding columnTypeChanged(final String file, final String changesetId,
                                      final String table, final String column,
                                      final String newType) {
        return Finding.breaking(
                ContractType.DB_MIGRATION, file, "column-type-changed",
                "Changeset '" + changesetId + "': modifyDataType '" + table + "." + column
                        + "' to " + newType + " — implicit casts may fail and existing data may be truncated",
                "Type changes can silently truncate data or break JDBC type mappings in running services",
                "Verify all consumers can handle the new type; consider adding a new column and migrating data");
    }
}
