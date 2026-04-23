package io.contractguardian.model;

import java.time.Duration;
import java.util.List;

/**
 * Result of scanning a single file for contract compatibility.
 *
 * @param file         the file path that was scanned
 * @param contractType the type of contract detected
 * @param findings     the list of findings from the scan
 * @param scanDuration how long the scan took
 */
public record ScanResult(
        String file,
        ContractType contractType,
        List<Finding> findings,
        Duration scanDuration
) {

    public ScanResult {
        findings = List.copyOf(findings);
    }

    /**
     * Returns {@code true} if any finding has {@link Severity#BREAKING}.
     */
    public boolean hasBreaking() {
        return findings.stream().anyMatch(f -> f.severity() == Severity.BREAKING);
    }

    /**
     * Returns {@code true} if any finding has {@link Severity#WARNING}.
     */
    public boolean hasWarnings() {
        return findings.stream().anyMatch(f -> f.severity() == Severity.WARNING);
    }

    /**
     * Returns the total number of findings.
     */
    public long findingCount() {
        return findings.size();
    }
}
