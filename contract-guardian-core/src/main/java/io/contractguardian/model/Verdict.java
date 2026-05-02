package io.contractguardian.model;

import java.time.Duration;
import java.util.List;

/**
 * Final verdict after evaluating all scan results against the gate policy.
 *
 * @param status         the overall pass/warn/fail status
 * @param results        the individual scan results
 * @param totalDuration  the total time taken for all scans
 * @param approvalStatus the approval status when an override was checked; never {@code null}
 */
public record Verdict(
        VerdictStatus status,
        List<ScanResult> results,
        Duration totalDuration,
        ApprovalStatus approvalStatus
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
     * Returns {@code true} when the verdict was downgraded from FAIL to WARN due to an
     * explicit approval override.
     */
    public boolean isOverridden() {
        return approvalStatus.approved() && status == VerdictStatus.WARN;
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
