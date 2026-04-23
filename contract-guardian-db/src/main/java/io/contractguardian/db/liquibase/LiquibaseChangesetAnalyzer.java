package io.contractguardian.db.liquibase;

import io.contractguardian.model.Finding;
import io.contractguardian.policy.DbRuleConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a {@link LiquibaseChangeset} against a {@link DbRuleConfig} and produces findings.
 *
 * <p>Handles the following Liquibase change types:
 * <ul>
 *   <li>{@code dropColumn} → {@code column-removed} (breaking)</li>
 *   <li>{@code dropTable} → {@code table-removed} (breaking)</li>
 *   <li>{@code addColumn} with NOT NULL and no default → {@code not-null-added-no-default} (breaking)</li>
 *   <li>{@code addNotNullConstraint} without defaultNullValue → {@code not-null-added-no-default} (breaking)</li>
 *   <li>{@code renameColumn} → {@code column-renamed} (warning)</li>
 *   <li>{@code modifyDataType} → {@code column-type-changed} (breaking)</li>
 * </ul>
 */
class LiquibaseChangesetAnalyzer {

    /**
     * Analyzes the given changeset and returns any findings.
     *
     * @param changeset the changeset to analyze
     * @param config    the rule config controlling which changes are breaking or warnings
     * @param filePath  the changelog file path for reporting
     * @return a list of findings, empty if no issues detected
     */
    List<Finding> analyze(final LiquibaseChangeset changeset, final DbRuleConfig config,
                          final String filePath) {
        final List<Finding> findings = new ArrayList<>();
        final String changesetId = changeset.id();

        for (final LiquibaseChange change : changeset.changes()) {
            findings.addAll(analyzeChange(change, changesetId, config, filePath));
        }

        return findings;
    }

    @SuppressWarnings("unchecked")
    private List<Finding> analyzeChange(final LiquibaseChange change, final String changesetId,
                                        final DbRuleConfig config, final String filePath) {
        final Map<String, Object> attrs = change.attributes();
        final String table = (String) attrs.get("tableName");

        return switch (change.type()) {
            case "dropColumn" -> {
                final String column = (String) attrs.get("columnName");
                if (column != null && config.isBreaking("column-removed")) {
                    yield List.of(LiquibaseFindingFactory.columnRemoved(filePath, changesetId, table, column));
                }
                yield List.of();
            }
            case "dropTable" -> {
                if (table != null && config.isBreaking("table-removed")) {
                    yield List.of(LiquibaseFindingFactory.tableRemoved(filePath, changesetId, table));
                }
                yield List.of();
            }
            case "addColumn" -> {
                final List<Map<String, Object>> columns =
                        (List<Map<String, Object>>) attrs.get("columns");
                if (columns == null) {
                    yield List.of();
                }
                final List<Finding> columnFindings = new ArrayList<>();
                for (final Map<String, Object> col : columns) {
                    final String columnName = (String) col.get("name");
                    if (isNotNull(col) && !hasDefault(col)
                            && config.isBreaking("not-null-added-no-default")) {
                        columnFindings.add(LiquibaseFindingFactory.notNullAddedNoDefault(
                                filePath, changesetId, table, columnName));
                    }
                }
                yield columnFindings;
            }
            case "addNotNullConstraint" -> {
                final String column = (String) attrs.get("columnName");
                final boolean hasDefault = attrs.containsKey("defaultNullValue");
                if (column != null && !hasDefault && config.isBreaking("not-null-added-no-default")) {
                    yield List.of(LiquibaseFindingFactory.notNullAddedNoDefault(
                            filePath, changesetId, table, column));
                }
                yield List.of();
            }
            case "renameColumn" -> {
                final String oldName = (String) attrs.get("oldColumnName");
                final String newName = (String) attrs.get("newColumnName");
                if (oldName != null && newName != null && config.isWarning("column-renamed")) {
                    yield List.of(LiquibaseFindingFactory.columnRenamed(
                            filePath, changesetId, table, oldName, newName));
                }
                yield List.of();
            }
            case "modifyDataType" -> {
                final String column = (String) attrs.get("columnName");
                final String newType = (String) attrs.getOrDefault("newDataType", "unknown");
                if (column != null && config.isBreaking("column-type-changed")) {
                    yield List.of(LiquibaseFindingFactory.columnTypeChanged(
                            filePath, changesetId, table, column, newType));
                }
                yield List.of();
            }
            default -> List.of();
        };
    }

    /**
     * Determines if a parsed column definition has a NOT NULL constraint.
     *
     * <p>Handles both XML (String "false") and YAML/JSON (Boolean false) attribute values,
     * and both {@code nullable=false} and {@code notNullable=true} Liquibase variants.
     *
     * @param column the column attribute map
     * @return {@code true} if the column is NOT NULL
     */
    @SuppressWarnings("unchecked")
    private boolean isNotNull(final Map<String, Object> column) {
        final Map<String, Object> constraints = (Map<String, Object>) column.get("constraints");
        if (constraints == null) {
            return false;
        }
        final Object nullable = constraints.get("nullable");
        if (nullable != null && isFalsy(nullable)) {
            return true;
        }
        final Object notNullable = constraints.get("notNullable");
        return notNullable != null && isTruthy(notNullable);
    }

    /**
     * Determines if a parsed column definition has any default value set.
     *
     * @param column the column attribute map
     * @return {@code true} if any default value attribute is present
     */
    private boolean hasDefault(final Map<String, Object> column) {
        return column.containsKey("defaultValue")
                || column.containsKey("defaultValueNumeric")
                || column.containsKey("defaultValueBoolean")
                || column.containsKey("defaultValueDate")
                || column.containsKey("defaultValueComputed");
    }

    private boolean isFalsy(final Object value) {
        if (value instanceof Boolean b) {
            return !b;
        }
        return "false".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean isTruthy(final Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }
}
