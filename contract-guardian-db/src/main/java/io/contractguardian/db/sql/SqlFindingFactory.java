package io.contractguardian.db.sql;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;

/**
 * Factory for creating {@link Finding} instances from SQL migration analysis.
 */
final class SqlFindingFactory {

    private SqlFindingFactory() {
    }

    /**
     * Creates a breaking finding for a dropped column.
     *
     * @param file       the migration file path
     * @param table      the table name
     * @param column     the dropped column name
     * @return a breaking finding
     */
    static Finding columnRemoved(final String file, final String table, final String column) {
        return Finding.breaking(
                ContractType.DB_MIGRATION, file, "column-removed",
                "Column '" + table + "." + column + "' dropped — existing consumers will fail on read",
                "Dropping a column breaks any application or query that references it",
                "Deprecate the column first; remove it only after all consumers have been updated");
    }

    /**
     * Creates a breaking finding for a dropped table.
     *
     * @param file  the migration file path
     * @param table the dropped table name
     * @return a breaking finding
     */
    static Finding tableRemoved(final String file, final String table) {
        return Finding.breaking(
                ContractType.DB_MIGRATION, file, "table-removed",
                "Table '" + table + "' dropped — all consumers of this table will fail",
                "Dropping a table is irreversible and breaks any query against it",
                "Ensure all services have stopped using this table before dropping it");
    }

    /**
     * Creates a breaking finding for a NOT NULL column added without a DEFAULT value.
     *
     * @param file   the migration file path
     * @param table  the table name
     * @param column the added column name
     * @return a breaking finding
     */
    static Finding notNullAddedNoDefault(final String file, final String table, final String column) {
        return Finding.breaking(
                ContractType.DB_MIGRATION, file, "not-null-added-no-default",
                "Column '" + table + "." + column + "' added as NOT NULL without a DEFAULT — "
                        + "running instances will fail on INSERT",
                "Without a DEFAULT, existing rows violate the constraint and new INSERTs from old "
                        + "application versions will fail",
                "Add a DEFAULT value, or follow expand-migrate-contract: add nullable first, "
                        + "backfill, then add the constraint");
    }

    /**
     * Creates a warning finding for a renamed column.
     *
     * @param file      the migration file path
     * @param table     the table name
     * @param oldName   the original column name
     * @param newName   the new column name
     * @return a warning finding
     */
    static Finding columnRenamed(final String file, final String table,
                                 final String oldName, final String newName) {
        return Finding.warning(
                ContractType.DB_MIGRATION, file, "column-renamed",
                "Column '" + table + "." + oldName + "' renamed to '" + newName
                        + "' — any consumer referencing the old name will break",
                "Column renames require all consumers to be updated simultaneously",
                "Consider adding the new column, migrating data, then removing the old column "
                        + "as separate steps");
    }

    /**
     * Creates a warning finding for a modified migration file.
     *
     * @param file the migration file path
     * @return a warning finding
     */
    static Finding migrationModified(final String file) {
        return Finding.warning(
                ContractType.DB_MIGRATION, file, "migration-modified",
                "Migration file was modified — this file may already be applied to production databases",
                "Modifying an applied migration is dangerous; the change will not be re-run by Flyway or Liquibase",
                "Create a new migration file with the corrected SQL instead of modifying the existing one");
    }
}
