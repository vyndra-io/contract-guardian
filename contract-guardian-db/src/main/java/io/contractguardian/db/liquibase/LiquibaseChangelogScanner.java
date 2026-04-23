package io.contractguardian.db.liquibase;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.DbRuleConfig;
import io.contractguardian.policy.RuleConfig;
import io.contractguardian.policy.SourceConfig;
import io.contractguardian.scanner.ContractScanner;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scans Liquibase changelog files ({@code .xml}, {@code .yaml}, {@code .yml}, {@code .json})
 * for breaking database schema changes.
 *
 * <p>Only newly added changesets (those present in the current branch but not in the baseline)
 * are analyzed. Existing changesets are not re-evaluated. If the file is new (no baseline),
 * all changesets in it are considered new.
 */
public class LiquibaseChangelogScanner implements ContractScanner {

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of(".xml", ".yaml", ".yml", ".json");

    private final LiquibaseChangelogParser parser = new LiquibaseChangelogParser();
    private final LiquibaseChangesetAnalyzer analyzer = new LiquibaseChangesetAnalyzer();

    @Override
    public Set<ContractType> supportedTypes() {
        return Set.of(ContractType.DB_MIGRATION);
    }

    @Override
    public boolean canScan(final Path file, final SourceConfig sourceConfig) {
        if (!"database".equals(sourceConfig.name())) {
            return false;
        }
        final String name = file.getFileName().toString().toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    @Override
    public ScanResult scan(final Path current, final Path baseline, final RuleConfig config) {
        final Instant start = Instant.now();
        final String filePath = current.toString();

        final DbRuleConfig dbConfig = (config instanceof DbRuleConfig dc)
                ? dc : DbRuleConfig.defaultConfig();

        final List<LiquibaseChangeset> currentChangesets = parser.parse(current);

        if (currentChangesets.isEmpty() && baseline == null) {
            return new ScanResult(filePath, ContractType.DB_MIGRATION,
                    List.of(Finding.info(ContractType.DB_MIGRATION, filePath,
                            "parse-skipped", "Changelog could not be parsed or is empty")),
                    Duration.between(start, Instant.now()));
        }

        final List<LiquibaseChangeset> newChangesets = findNewChangesets(currentChangesets, baseline);

        final List<Finding> findings = new ArrayList<>();
        for (final LiquibaseChangeset changeset : newChangesets) {
            findings.addAll(analyzer.analyze(changeset, dbConfig, filePath));
        }

        if (findings.isEmpty()) {
            findings.add(Finding.info(ContractType.DB_MIGRATION, filePath,
                    "compatible", "No breaking changes found in " + newChangesets.size()
                            + " new changeset(s)"));
        }

        return new ScanResult(filePath, ContractType.DB_MIGRATION,
                findings, Duration.between(start, Instant.now()));
    }

    /**
     * Returns only the changesets that appear in {@code currentChangesets} but not in the
     * baseline file. If {@code baseline} is {@code null}, all current changesets are returned.
     *
     * <p>Changesets are matched by their composite {@code id::author} key.
     *
     * @param currentChangesets the changesets parsed from the current branch
     * @param baseline          the baseline file path, or {@code null} if this is a new file
     * @return the list of new changesets that need to be analyzed
     */
    private List<LiquibaseChangeset> findNewChangesets(final List<LiquibaseChangeset> currentChangesets,
                                                        final Path baseline) {
        if (baseline == null) {
            return currentChangesets;
        }

        final Set<String> baselineKeys = parser.parse(baseline).stream()
                .map(LiquibaseChangeset::key)
                .collect(Collectors.toSet());

        return currentChangesets.stream()
                .filter(cs -> !baselineKeys.contains(cs.key()))
                .toList();
    }
}
