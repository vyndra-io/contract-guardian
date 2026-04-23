package io.contractguardian.model;

import java.time.Duration;
import java.util.List;

/**
 * Final verdict after evaluating all scan results against the gate policy.
 *
 * @param status        the overall pass/warn/fail status
 * @param results       the individual scan results
 * @param totalDuration the total time taken for all scans
 */
public record Verdict(
        VerdictStatus status,
        List<ScanResult> results,
        Duration totalDuration
) {

    public Verdict {
        results = List.copyOf(results);
    }

    /**
     * Returns the CLI exit code: 1 for {@link VerdictStatus#FAIL}, 0 otherwise.
     */
    public int exitCode() {
        return status == VerdictStatus.FAIL ? 1 : 0;
    }

    /**
     * Returns the total number of breaking findings across all results.
     */
    public long breakingCount() {
        return results.stream()
                .flatMap(r -> r.findings().stream())
                .filter(f -> f.severity() == Severity.BREAKING)
                .count();
    }

    /**
     * Returns the total number of warning findings across all results.
     */
    public long warningCount() {
        return results.stream()
                .flatMap(r -> r.findings().stream())
                .filter(f -> f.severity() == Severity.WARNING)
                .count();
    }

    /**
     * Returns the number of scanned files that have no breaking or warning findings.
     */
    public long passCount() {
        return results.stream()
                .filter(r -> !r.hasBreaking() && !r.hasWarnings())
                .count();
    }
}
