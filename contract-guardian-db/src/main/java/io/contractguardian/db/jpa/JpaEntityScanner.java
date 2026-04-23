package io.contractguardian.db.jpa;

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
 * Scans Java source files ({@code .java}) under the {@code database} source for breaking
 * database schema changes.
 *
 * <p>Handles two kinds of files:
 * <ul>
 *   <li><b>JPA entities</b> (classes annotated with {@code @Entity}): compares
 *       {@code @Column} and {@code @JdbcTypeCode} metadata to detect column removals,
 *       nullable constraint additions, renames, and Map-typed JSONB fields.</li>
 *   <li><b>JSONB value classes</b> (plain classes without {@code @Entity}): compares
 *       fields by name and type to detect removals, type changes, and Map-typed fields
 *       whose schema cannot be statically enforced.</li>
 * </ul>
 */
public class JpaEntityScanner implements ContractScanner {

    private final JpaCompatibilityChecker checker = new JpaCompatibilityChecker();

    @Override
    public Set<ContractType> supportedTypes() {
        return Set.of(ContractType.DB_JPA_ENTITY, ContractType.DB_JSONB);
    }

    @Override
    public boolean canScan(final Path file, final SourceConfig sourceConfig) {
        return "database".equals(sourceConfig.name()) && file.toString().endsWith(".java");
    }

    @Override
    public ScanResult scan(final Path current, final Path baseline, final RuleConfig config) {
        final Instant start = Instant.now();
        final String filePath = current.toString();
        final boolean isEntity = hasEntityAnnotation(current);
        final ContractType contractType = isEntity ? ContractType.DB_JPA_ENTITY : ContractType.DB_JSONB;

        if (baseline == null) {
            final String rule = isEntity ? "new-entity" : "new-jsonb-class";
            final String message = isEntity
                    ? "New entity file — no baseline to compare"
                    : "New JSONB value class — no baseline to compare";
            return new ScanResult(filePath, contractType,
                    List.of(Finding.info(contractType, filePath, rule, message)),
                    Duration.between(start, Instant.now()));
        }

        final DbRuleConfig dbConfig = (config instanceof DbRuleConfig dc)
                ? dc : DbRuleConfig.defaultConfig();

        List<Finding> findings = isEntity
                ? checker.check(current, baseline, dbConfig, filePath)
                : checker.checkJsonbValueClass(current, baseline, dbConfig, filePath);

        if (findings.isEmpty()) {
            final String message = isEntity
                    ? "No breaking JPA entity changes detected"
                    : "No breaking JSONB value class changes detected";
            findings = List.of(Finding.info(contractType, filePath, "compatible", message));
        }

        return new ScanResult(filePath, contractType, findings, Duration.between(start, Instant.now()));
    }

    private boolean hasEntityAnnotation(final Path file) {
        try {
            return Files.readString(file).contains("@Entity");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file: " + file, e);
        }
    }
}
