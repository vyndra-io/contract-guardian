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
     * Evaluates the scan results against the gate policy with no approval override.
     *
     * @param results       the scan results to evaluate
     * @param totalDuration the total time taken for all scans
     * @return the verdict with pass/warn/fail status
     */
    public Verdict evaluate(final List<ScanResult> results, final Duration totalDuration) {
        return evaluate(results, totalDuration, ApprovalStatus.none());
    }

    /**
     * Evaluates the scan results against the gate policy, applying an approval override
     * when the gate is configured to allow it and the approval is confirmed.
     *
     * <p>When breaking changes are found and the gate would normally produce
     * {@link VerdictStatus#FAIL}, but {@code approval.approved()} is {@code true} and
     * {@code approvalRequiredToBypass} is enabled in the gate config, the verdict is
     * downgraded to {@link VerdictStatus#WARN} so the PR can merge with a visible
     * audit trail in the output.
     *
     * @param results        the scan results to evaluate
     * @param totalDuration  the total time taken for all scans
     * @param approval       the resolved approval status from the CI platform
     * @return the verdict with pass/warn/fail status
     */
    public Verdict evaluate(final List<ScanResult> results, final Duration totalDuration,
                            final ApprovalStatus approval) {
        final boolean hasBreaking = results.stream().anyMatch(ScanResult::hasBreaking);
        final boolean hasWarnings = results.stream().anyMatch(ScanResult::hasWarnings);
        final boolean hasAny = results.stream().anyMatch(r -> !r.findings().isEmpty());

        final GateConfig gate = policy.gate();
        VerdictStatus status = computeStatus(gate.blockOn(), hasBreaking, hasWarnings, hasAny);

        if (status == VerdictStatus.FAIL
                && gate.approvalRequiredToBypass()
                && approval.approved()) {
            status = VerdictStatus.WARN;
        }

        return new Verdict(status, results, totalDuration, approval);
    }

    private VerdictStatus computeStatus(final GateConfig.BlockOn blockOn,
                                        final boolean hasBreaking,
                                        final boolean hasWarnings,
                                        final boolean hasAny) {
        return switch (blockOn) {
            case BREAKING -> hasBreaking ? VerdictStatus.FAIL
                    : (hasWarnings ? VerdictStatus.WARN : VerdictStatus.PASS);
            case WARNING -> (hasBreaking || hasWarnings) ? VerdictStatus.FAIL
                    : VerdictStatus.PASS;
            case ANY -> hasAny ? VerdictStatus.FAIL : VerdictStatus.PASS;
        };
    }
}
