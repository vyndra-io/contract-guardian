package io.contractguardian.engine;

import io.contractguardian.model.*;
import io.contractguardian.policy.GateConfig;
import io.contractguardian.policy.PolicyConfig;

import java.time.Duration;
import java.util.List;

/**
 * Evaluates scan results against the gate policy to produce a final verdict.
 */
public class PolicyEngine {

    private final PolicyConfig policy;

    /**
     * Creates a policy engine with the given configuration.
     *
     * @param policy the policy configuration containing gate rules
     */
    public PolicyEngine(final PolicyConfig policy) {
        this.policy = policy;
    }

    /**
     * Evaluates the scan results against the gate policy.
     *
     * @param results       the scan results to evaluate
     * @param totalDuration the total time taken for all scans
     * @return the verdict with pass/warn/fail status
     */
    public Verdict evaluate(final List<ScanResult> results, final Duration totalDuration) {
        final boolean hasBreaking = results.stream().anyMatch(ScanResult::hasBreaking);
        final boolean hasWarnings = results.stream().anyMatch(ScanResult::hasWarnings);

        final GateConfig.BlockOn blockOn = policy.gate().blockOn();
        final VerdictStatus status = switch (blockOn) {
            case BREAKING -> hasBreaking ? VerdictStatus.FAIL
                    : (hasWarnings ? VerdictStatus.WARN : VerdictStatus.PASS);
            case WARNING -> (hasBreaking || hasWarnings) ? VerdictStatus.FAIL
                    : VerdictStatus.PASS;
            case ANY -> results.stream()
                    .anyMatch(r -> !r.findings().isEmpty()) ? VerdictStatus.FAIL
                    : VerdictStatus.PASS;
        };

        return new Verdict(status, results, totalDuration);
    }
}
