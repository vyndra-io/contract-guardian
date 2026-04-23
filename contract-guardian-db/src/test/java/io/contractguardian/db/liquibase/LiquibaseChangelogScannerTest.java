package io.contractguardian.db.liquibase;

import io.contractguardian.model.Severity;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.DbRuleConfig;
import io.contractguardian.policy.SourceConfig;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiquibaseChangelogScannerTest {

    private final LiquibaseChangelogScanner scanner = new LiquibaseChangelogScanner();
    private final SourceConfig databaseSource = new SourceConfig("database",
            List.of("db/changelog/**/*.xml", "db/changelog/**/*.yaml", "db/changelog/**/*.json"),
            "branch:main");
    private final DbRuleConfig defaultConfig = DbRuleConfig.defaultConfig();

    // -------------------------------------------------------------------------
    // XML scenarios
    // -------------------------------------------------------------------------

    @Test
    void xml_dropColumn_producesBreakingFinding() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/xml/drop-column/changelog.xml");

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("column-removed")
                        && f.message().contains("customer_email"));
    }

    @Test
    void xml_dropTable_producesBreakingFinding() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/xml/drop-table/changelog.xml");

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("table-removed")
                        && f.message().contains("legacy_events"));
    }

    @Test
    void xml_addNotNullWithoutDefault_producesBreakingFinding() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/xml/add-not-null-no-default/changelog.xml");

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("not-null-added-no-default")
                        && f.message().contains("region"));
    }

    @Test
    void xml_addNotNullWithDefault_producesNoBreakingFindings() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/xml/add-not-null-with-default/changelog.xml");

        assertThat(result.findings()).noneMatch(f -> f.severity() == Severity.BREAKING);
    }

    @Test
    void xml_renameColumn_producesWarningFinding() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/xml/rename-column/changelog.xml");

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.WARNING
                        && f.rule().equals("column-renamed")
                        && f.message().contains("email")
                        && f.message().contains("email_address"));
    }

    @Test
    void xml_addNotNullConstraint_producesBreakingFinding() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/xml/add-not-null-constraint/changelog.xml");

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("not-null-added-no-default")
                        && f.message().contains("status"));
    }

    @Test
    void xml_modifyDataType_producesBreakingFinding() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/xml/modify-datatype/changelog.xml");

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("column-type-changed")
                        && f.message().contains("amount"));
    }

    @Test
    void xml_incrementalChangelog_onlyAnalyzesNewChangesets() throws Exception {
        final Path baseline = path("liquibase-scenarios/xml/incremental-baseline/changelog.xml");
        final ScanResult result = scan(baseline, "liquibase-scenarios/xml/incremental-current/changelog.xml");

        // Changeset 10 is in both baseline and current — must not be re-flagged
        // Changeset 11 is new — must be flagged for dropColumn
        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("column-removed")
                        && f.message().contains("customer_email"));

        // No duplicate findings from the pre-existing changeset
        assertThat(result.findings()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // YAML scenarios
    // -------------------------------------------------------------------------

    @Test
    void yaml_dropColumn_producesBreakingFinding() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/yaml/drop-column/changelog.yaml");

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("column-removed")
                        && f.message().contains("card_number"));
    }

    @Test
    void yaml_addNotNullWithoutDefault_producesBreakingFinding() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/yaml/add-not-null-no-default/changelog.yaml");

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("not-null-added-no-default")
                        && f.message().contains("country_code"));
    }

    // -------------------------------------------------------------------------
    // JSON scenarios
    // -------------------------------------------------------------------------

    @Test
    void json_dropColumn_producesBreakingFinding() throws Exception {
        final ScanResult result = scan(null, "liquibase-scenarios/json/drop-column/changelog.json");

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("column-removed")
                        && f.message().contains("tracking_code"));
    }

    // -------------------------------------------------------------------------
    // canScan
    // -------------------------------------------------------------------------

    @Test
    void canScan_xmlUnderDatabaseSource_returnsTrue() {
        assertThat(scanner.canScan(Path.of("db/changelog/V1.xml"), databaseSource)).isTrue();
    }

    @Test
    void canScan_yamlUnderDatabaseSource_returnsTrue() {
        assertThat(scanner.canScan(Path.of("db/changelog/changes.yaml"), databaseSource)).isTrue();
    }

    @Test
    void canScan_jsonUnderDatabaseSource_returnsTrue() {
        assertThat(scanner.canScan(Path.of("db/changelog/changes.json"), databaseSource)).isTrue();
    }

    @Test
    void canScan_xmlUnderRestSource_returnsFalse() {
        final SourceConfig restSource = new SourceConfig("rest",
                List.of("api/openapi/**/*.yaml"), "branch:main");
        assertThat(scanner.canScan(Path.of("api/openapi/service.yaml"), restSource)).isFalse();
    }

    @Test
    void canScan_sqlUnderDatabaseSource_returnsFalse() {
        assertThat(scanner.canScan(Path.of("db/migrations/V1.sql"), databaseSource)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ScanResult scan(final Path baseline, final String currentResource)
            throws URISyntaxException {
        return scanner.scan(path(currentResource), baseline, defaultConfig);
    }

    private Path path(final String resource) throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource(resource).toURI());
    }
}
