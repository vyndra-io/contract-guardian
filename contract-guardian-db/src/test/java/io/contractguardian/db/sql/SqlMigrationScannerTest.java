package io.contractguardian.db.sql;

import io.contractguardian.model.Severity;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.DbRuleConfig;
import io.contractguardian.policy.SourceConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlMigrationScannerTest {

    private final SqlMigrationScanner scanner = new SqlMigrationScanner();
    private final SourceConfig databaseSource = new SourceConfig("database",
            List.of("db/migrations/**/*.sql"), "branch:main");
    private final DbRuleConfig defaultConfig = DbRuleConfig.defaultConfig();

    @Test
    void dropColumn_producesBreakingFinding() throws Exception {
        final Path migration = sqlScenario("drop-column/migration.sql");
        final ScanResult result = scanner.scan(migration, null, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("column-removed")
                        && f.message().contains("customer_email"));
    }

    @Test
    void dropTable_producesBreakingFinding() throws Exception {
        final Path migration = sqlScenario("drop-table/migration.sql");
        final ScanResult result = scanner.scan(migration, null, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("table-removed")
                        && f.message().contains("legacy_events"));
    }

    @Test
    void addNotNullWithoutDefault_producesBreakingFinding() throws Exception {
        final Path migration = sqlScenario("add-not-null-no-default/migration.sql");
        final ScanResult result = scanner.scan(migration, null, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("not-null-added-no-default")
                        && f.message().contains("region"));
    }

    @Test
    void addNotNullWithDefault_producesNoBreakingFindings() throws Exception {
        final Path migration = sqlScenario("add-not-null-with-default/migration.sql");
        final ScanResult result = scanner.scan(migration, null, defaultConfig);

        assertThat(result.findings())
                .noneMatch(f -> f.severity() == Severity.BREAKING);
    }

    @Test
    void addNullableColumn_producesNoBreakingFindings() throws Exception {
        final Path migration = sqlScenario("add-nullable-column/migration.sql");
        final ScanResult result = scanner.scan(migration, null, defaultConfig);

        assertThat(result.findings())
                .noneMatch(f -> f.severity() == Severity.BREAKING);
    }

    @Test
    void renameColumn_producesWarningFinding() throws Exception {
        final Path migration = sqlScenario("rename-column/migration.sql");
        final ScanResult result = scanner.scan(migration, null, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.WARNING
                        && f.rule().equals("column-renamed"));
    }

    @Test
    void modifiedMigration_producesWarningFinding(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws IOException {
        final Path migration = tempDir.resolve("V1__init.sql");
        final Path baseline = tempDir.resolve("V1__init_baseline.sql");
        Files.writeString(migration, "ALTER TABLE orders ADD COLUMN notes TEXT;");
        Files.writeString(baseline, "ALTER TABLE orders ADD COLUMN notes TEXT;");

        final ScanResult result = scanner.scan(migration, baseline, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.WARNING
                        && f.rule().equals("migration-modified"));
    }

    @Test
    void canScan_sqlFileUnderDatabaseSource_returnsTrue() {
        assertThat(scanner.canScan(Path.of("db/migrations/V1__init.sql"), databaseSource)).isTrue();
    }

    @Test
    void canScan_javaFileUnderDatabaseSource_returnsFalse() {
        assertThat(scanner.canScan(Path.of("src/main/java/OrderEntity.java"), databaseSource)).isFalse();
    }

    @Test
    void canScan_sqlFileUnderKafkaSource_returnsFalse() {
        final SourceConfig kafkaSource = new SourceConfig("kafka",
                List.of("schemas/kafka/**/*.avsc"), "branch:main");
        assertThat(scanner.canScan(Path.of("db/migrations/V1__init.sql"), kafkaSource)).isFalse();
    }

    private Path sqlScenario(final String relative) throws URISyntaxException {
        return Path.of(getClass().getClassLoader()
                .getResource("sql-scenarios/" + relative)
                .toURI());
    }
}
