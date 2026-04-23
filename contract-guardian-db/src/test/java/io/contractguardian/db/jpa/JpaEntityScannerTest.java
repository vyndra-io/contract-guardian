package io.contractguardian.db.jpa;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Severity;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.DbRuleConfig;
import io.contractguardian.policy.SourceConfig;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JpaEntityScannerTest {

    private final JpaEntityScanner scanner = new JpaEntityScanner();
    private final SourceConfig databaseSource = new SourceConfig("database",
            List.of("src/main/java/**/*.java"), "branch:main");
    private final DbRuleConfig defaultConfig = DbRuleConfig.defaultConfig();

    @Test
    void fieldRemoved_producesBreakingFinding() throws Exception {
        final Path current = jpaScenario("field-removed/current/OrderEntity.java");
        final Path baseline = jpaScenario("field-removed/baseline/OrderEntity.java");
        final ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("column-removed")
                        && f.message().contains("email"));
    }

    @Test
    void notNullAdded_producesBreakingFinding() throws Exception {
        final Path current = jpaScenario("not-null-added/current/PaymentEntity.java");
        final Path baseline = jpaScenario("not-null-added/baseline/PaymentEntity.java");
        final ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("not-null-added-no-default")
                        && f.message().contains("currency"));
    }

    @Test
    void columnRenamed_producesWarningFinding() throws Exception {
        final Path current = jpaScenario("column-renamed/current/UserEntity.java");
        final Path baseline = jpaScenario("column-renamed/baseline/UserEntity.java");
        final ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.WARNING
                        && f.rule().equals("column-renamed")
                        && f.message().contains("user_email")
                        && f.message().contains("email_address"));
    }

    @Test
    void jsonbFieldRemoved_producesBreakingFinding() throws Exception {
        final Path current = jpaScenario("jsonb-field-removed/current/ProductEntity.java");
        final Path baseline = jpaScenario("jsonb-field-removed/baseline/ProductEntity.java");
        final ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("jsonb-field-removed")
                        && f.message().contains("attributes"));
    }

    // -------------------------------------------------------------------------
    // JSONB value class scenarios
    // -------------------------------------------------------------------------

    @Test
    void jsonbValueClass_fieldRemoved_producesBreakingFinding() throws Exception {
        final Path current = jpaScenario("jsonb-value-field-removed/current/UserPreferences.java");
        final Path baseline = jpaScenario("jsonb-value-field-removed/baseline/UserPreferences.java");
        final ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("jsonb-field-removed")
                        && f.message().contains("theme"));
    }

    @Test
    void jsonbValueClass_fieldTypeChanged_producesBreakingFinding() throws Exception {
        final Path current = jpaScenario("jsonb-value-type-changed/current/UserPreferences.java");
        final Path baseline = jpaScenario("jsonb-value-type-changed/baseline/UserPreferences.java");
        final ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.BREAKING
                        && f.rule().equals("jsonb-field-type-changed")
                        && f.message().contains("theme")
                        && f.message().contains("String")
                        && f.message().contains("Integer"));
    }

    @Test
    void jsonbValueClass_mapField_producesUnenforceableWarning() throws Exception {
        final Path current = jpaScenario("jsonb-value-map-field/current/OrderMetadata.java");
        final Path baseline = jpaScenario("jsonb-value-map-field/baseline/OrderMetadata.java");
        final ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.WARNING
                        && f.rule().equals("jsonb-schema-unenforceable")
                        && f.message().contains("extra"));
    }

    @Test
    void jsonbValueClass_newFile_producesInfoFinding() throws Exception {
        final Path current = jpaScenario("jsonb-value-field-removed/current/UserPreferences.java");
        final ScanResult result = scanner.scan(current, null, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.INFO
                        && f.rule().equals("new-jsonb-class"));
    }

    @Test
    void jsonbEntity_mapTypedJsonbField_producesUnenforceableWarning() throws Exception {
        final Path current = jpaScenario("jsonb-entity-map-field/current/OrderEntity.java");
        final Path baseline = jpaScenario("jsonb-entity-map-field/baseline/OrderEntity.java");
        final ScanResult result = scanner.scan(current, baseline, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.WARNING
                        && f.rule().equals("jsonb-schema-unenforceable")
                        && f.message().contains("metadata"));
    }

    @Test
    void newFile_producesInfoFinding() throws Exception {
        final Path current = jpaScenario("field-removed/current/OrderEntity.java");
        final ScanResult result = scanner.scan(current, null, defaultConfig);

        assertThat(result.findings())
                .anyMatch(f -> f.severity() == Severity.INFO
                        && f.rule().equals("new-entity"));
    }

    @Test
    void supportedTypes_includesDbJpaEntityAndJsonb() {
        assertThat(scanner.supportedTypes())
                .containsExactlyInAnyOrder(ContractType.DB_JPA_ENTITY, ContractType.DB_JSONB);
    }

    @Test
    void canScan_javaFileUnderDatabaseSource_returnsTrue() {
        assertThat(scanner.canScan(
                Path.of("src/main/java/OrderEntity.java"), databaseSource)).isTrue();
    }

    @Test
    void canScan_sqlFileUnderDatabaseSource_returnsFalse() {
        assertThat(scanner.canScan(
                Path.of("db/migrations/V1__init.sql"), databaseSource)).isFalse();
    }

    private Path jpaScenario(final String relative) throws URISyntaxException {
        return Path.of(getClass().getClassLoader()
                .getResource("jpa-scenarios/" + relative)
                .toURI());
    }
}
