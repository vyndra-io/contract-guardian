package io.contractguardian.scanner;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.RuleConfig;
import io.contractguardian.policy.SourceConfig;

import java.nio.file.Path;
import java.util.Set;

/**
 * Extension point for contract compatibility scanners.
 *
 * <p>Implementations are discovered at runtime via {@link java.util.ServiceLoader}.
 * Each scanner declares which contract types it handles, checks file applicability,
 * and performs the actual compatibility analysis.
 */
public interface ContractScanner {

    /**
     * Returns the contract types this scanner can handle.
     *
     * @return a non-empty set of supported contract types
     */
    Set<ContractType> supportedTypes();

    /**
     * Checks whether this scanner can process the given file.
     *
     * @param file         the file path to check
     * @param sourceConfig the source configuration for context
     * @return {@code true} if this scanner should handle the file
     */
    boolean canScan(Path file, SourceConfig sourceConfig);

    /**
     * Scans a file for compatibility issues against its baseline version.
     *
     * @param current  the file as it exists in the current branch
     * @param baseline the file as it exists in the baseline branch, or {@code null} if newly added
     * @param config   the rule configuration for this scanner
     * @return the scan result with any findings
     */
    ScanResult scan(Path current, Path baseline, RuleConfig config);
}
