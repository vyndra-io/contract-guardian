package io.contractguardian.db.sql;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.policy.DbRuleConfig;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterExpression.ColumnDataType;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.drop.Drop;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks SQL migration files for breaking DDL changes using JSqlParser.
 *
 * <p>Detected change types: {@code column-removed}, {@code table-removed},
 * {@code not-null-added-no-default}, {@code column-renamed}.
 */
class SqlCompatibilityChecker {

    /**
     * Analyzes the given SQL content for breaking database changes.
     *
     * @param sql      the SQL migration content
     * @param config   the rule config controlling which changes are breaking or warnings
     * @param filePath the source file path for reporting
     * @return a list of findings, empty if no issues detected
     */
    List<Finding> check(final String sql, final DbRuleConfig config, final String filePath) {
        final List<Finding> findings = new ArrayList<>();

        try {
            final Statements statements = CCJSqlParserUtil.parseStatements(sql);
            for (final Statement stmt : statements.getStatements()) {
                findings.addAll(analyzeStatement(stmt, config, filePath));
            }
        } catch (Exception e) {
            findings.add(Finding.info(ContractType.DB_MIGRATION, filePath,
                    "parse-skipped",
                    "SQL could not be fully parsed — some checks may be skipped: " + e.getMessage()));
        }

        return findings;
    }

    private List<Finding> analyzeStatement(final Statement stmt, final DbRuleConfig config,
                                           final String filePath) {
        if (stmt instanceof Alter alter) {
            return analyzeAlter(alter, config, filePath);
        }
        if (stmt instanceof Drop drop) {
            return analyzeDrop(drop, config, filePath);
        }
        return List.of();
    }

    private List<Finding> analyzeAlter(final Alter alter, final DbRuleConfig config,
                                       final String filePath) {
        final List<Finding> findings = new ArrayList<>();
        final String tableName = alter.getTable().getName();

        for (final AlterExpression expr : alter.getAlterExpressions()) {
            final AlterOperation op = expr.getOperation();

            if (op == AlterOperation.DROP && expr.getColumnName() != null
                    && config.isBreaking("column-removed")) {
                findings.add(SqlFindingFactory.columnRemoved(filePath, tableName, expr.getColumnName()));

            } else if (op == AlterOperation.ADD && expr.getColDataTypeList() != null) {
                for (final ColumnDataType col : expr.getColDataTypeList()) {
                    if (hasNotNull(col.getColumnSpecs()) && !hasDefault(col.getColumnSpecs())
                            && config.isBreaking("not-null-added-no-default")) {
                        findings.add(SqlFindingFactory.notNullAddedNoDefault(
                                filePath, tableName, col.getColumnName()));
                    }
                }

            } else if (op == AlterOperation.RENAME && expr.getColumnOldName() != null
                    && config.isWarning("column-renamed")) {
                findings.add(SqlFindingFactory.columnRenamed(
                        filePath, tableName,
                        expr.getColumnOldName(), expr.getColumnName()));
            }
        }

        return findings;
    }

    private List<Finding> analyzeDrop(final Drop drop, final DbRuleConfig config,
                                      final String filePath) {
        if ("TABLE".equalsIgnoreCase(drop.getType()) && config.isBreaking("table-removed")) {
            return List.of(SqlFindingFactory.tableRemoved(filePath, drop.getName().getName()));
        }
        return List.of();
    }

    private boolean hasNotNull(final List<String> specs) {
        if (specs == null || specs.isEmpty()) {
            return false;
        }
        return String.join(" ", specs).toUpperCase().contains("NOT NULL");
    }

    private boolean hasDefault(final List<String> specs) {
        if (specs == null || specs.isEmpty()) {
            return false;
        }
        return String.join(" ", specs).toUpperCase().contains("DEFAULT");
    }
}
