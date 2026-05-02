package io.contractguardian.engine;

import io.contractguardian.model.Finding;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.PolicyConfig;
import io.contractguardian.policy.RuleConfig;
import io.contractguardian.policy.SourceConfig;
import io.contractguardian.scanner.ContractScanner;
import io.contractguardian.scanner.ScannerRegistry;
import io.contractguardian.util.GitHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates scanning of changed files in a git diff.
 *
 * <p>For each changed file, finds the appropriate scanner, extracts
 * the baseline version from git, and runs the compatibility check.
 */
public class DiffAnalyzer {

    private final GitHelper git;
    private final ScannerRegistry registry;
    private final PolicyConfig policy;

    /**
     * Creates a diff analyzer.
     *
     * @param git      helper for git operations
     * @param registry registry of available scanners
     * @param policy   the policy configuration
     */
    public DiffAnalyzer(final GitHelper git, final ScannerRegistry registry,
                        final PolicyConfig policy) {
        this.git = git;
        this.registry = registry;
        this.policy = policy;
    }

    /**
     * Analyzes all changed files in the given git diff spec.
     *
     * @param diffSpec the git diff specification (e.g. "origin/main..HEAD")
     * @return the list of scan results for files that matched a source and scanner
     */
    public List<ScanResult> analyze(final String diffSpec) {
        final String baseRef = git.baseRef(diffSpec);
        final List<String> changedFiles = git.changedFiles(diffSpec);
        final List<ScanResult> results = new ArrayList<>();

        for (final String file : changedFiles) {
            final Path currentFile = git.workingDir().resolve(file);

            final Optional<SourceConfig> sourceConfig = policy.findSourceFor(file);
            if (sourceConfig.isEmpty()) {
                continue;
            }

            final Optional<ContractScanner> scanner = registry.findScanner(currentFile, sourceConfig.get());
            if (scanner.isEmpty()) {
                continue;
            }

            final Optional<RuleConfig> ruleConfig = policy.ruleConfigFor(sourceConfig.get());
            if (ruleConfig.isEmpty()) {
                continue;
            }

            final int n = ruleConfig.get().nVersionCompatibility();
            final List<Path> baselines = new ArrayList<>();
            try {
                if (n <= 1) {
                    final Path baseline = git.extractFileAtRef(baseRef, file);
                    if (baseline != null) {
                        baselines.add(baseline);
                    }
                } else {
                    baselines.addAll(git.fileHistoryAtRef(baseRef, file, n));
                }
                final ScanResult result = runAndMerge(
                        scanner.get(), currentFile, baselines, ruleConfig.get());
                results.add(result);
            } finally {
                for (final Path baseline : baselines) {
                    try {
                        Files.deleteIfExists(baseline);
                    } catch (IOException e) {
                        System.err.println("Warning: failed to delete temp file "
                                + baseline + ": " + e.getMessage());
                    }
                }
            }
        }

        return results;
    }

    private ScanResult runAndMerge(final ContractScanner scanner, final Path current,
                                   final List<Path> baselines, final RuleConfig config) {
        if (baselines.isEmpty()) {
            return scanner.scan(current, null, config);
        }
        final long startNanos = System.nanoTime();
        final LinkedHashSet<Finding> merged = new LinkedHashSet<>();
        ScanResult last = null;
        for (final Path baseline : baselines) {
            last = scanner.scan(current, baseline, config);
            merged.addAll(last.findings());
        }
        return new ScanResult(
                last.file(),
                last.contractType(),
                List.copyOf(merged),
                Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
