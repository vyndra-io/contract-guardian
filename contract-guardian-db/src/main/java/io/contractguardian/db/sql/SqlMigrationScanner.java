package io.contractguardian.db.sql;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.DbRuleConfig;
import io.contractguardian.policy.RuleConfig;
import io.contractguardian.policy.SourceConfig;
import io.contractguardian.scanner.ContractScanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Scans SQL migration files ({@code .sql}) for breaking database schema changes.
 *
 * <p>Detects dangerous DDL operations such as column drops, table drops, and
 * NOT NULL columns added without a DEFAULT value. Also warns when a migration
 * file is modified after creation, as it may already be applied in production.
 */
public class SqlMigrationScanner implements ContractScanner {

    private final SqlCompatibilityChecker checker = new SqlCompatibilityChecker();

    @Override
    public Set<ContractType> supportedTypes() {
        return Set.of(ContractType.DB_MIGRATION);
    }

    @Override
    public boolean canScan(final Path file, final SourceConfig sourceConfig) {
        return "database".equals(sourceConfig.name()) && file.toString().endsWith(".sql");
    }

    @Override
    public ScanResult scan(final Path current, final Path baseline, final RuleConfig config) {
        final Instant start = Instant.now();
        final String filePath = current.toString();

        final DbRuleConfig dbConfig = (config instanceof DbRuleConfig dc)
                ? dc : DbRuleConfig.defaultConfig();

        if (baseline != null && dbConfig.isWarning("migration-modified")) {
            return new ScanResult(filePath, ContractType.DB_MIGRATION,
                    List.of(SqlFindingFactory.migrationModified(filePath)),
                    Duration.between(start, Instant.now()));
        }

        final String sql = readFile(current);
        List<Finding> findings = checker.check(sql, dbConfig, filePath);

        if (findings.isEmpty()) {
            findings = List.of(Finding.info(ContractType.DB_MIGRATION, filePath,
                    "compatible", "No breaking DDL changes detected"));
        }

        return new ScanResult(filePath, ContractType.DB_MIGRATION,
                findings, Duration.between(start, Instant.now()));
    }

    private String readFile(final Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read migration file: " + file, e);
        }
    }
}
