package io.contractguardian.engine;

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
import java.util.ArrayList;
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

            Path baseline = null;
            try {
                baseline = git.extractFileAtRef(baseRef, file);
                final ScanResult result = scanner.get().scan(currentFile, baseline, ruleConfig.get());
                results.add(result);
            } finally {
                if (baseline != null) {
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
}
