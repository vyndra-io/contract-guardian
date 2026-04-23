package io.contractguardian.policy;

import java.util.List;

/**
 * Gate configuration that determines when a merge should be blocked.
 *
 * <p>Controls which finding severities block the merge and whether
 * approval-based overrides are available.
 */
public class GateConfig {

    /**
     * Defines which severity levels cause the gate to fail.
     */
    public enum BlockOn {

        /** Fail only when breaking changes are detected. */
        BREAKING,

        /** Fail on breaking changes or warnings. */
        WARNING,

        /** Fail on any findings, including informational. */
        ANY
    }

    private final BlockOn blockOn;
    private final boolean approvalRequiredToBypass;
    private final String approvalLabel;
    private final List<String> approvers;

    /**
     * Creates a gate configuration.
     *
     * @param blockOn                   the severity threshold for blocking
     * @param approvalRequiredToBypass   whether approval can override a block
     * @param approvalLabel             the label required for approval, may be {@code null}
     * @param approvers                 the list of users or teams who can approve
     */
    public GateConfig(final BlockOn blockOn, final boolean approvalRequiredToBypass,
                      final String approvalLabel, final List<String> approvers) {
        this.blockOn = blockOn;
        this.approvalRequiredToBypass = approvalRequiredToBypass;
        this.approvalLabel = approvalLabel;
        this.approvers = approvers != null ? List.copyOf(approvers) : List.of();
    }

    /**
     * Returns the severity threshold that blocks the merge.
     *
     * @return the block-on level
     */
    public BlockOn blockOn() {
        return blockOn;
    }

    /**
     * Returns whether approval is required to bypass a blocked merge.
     *
     * @return {@code true} if approval is required
     */
    public boolean approvalRequiredToBypass() {
        return approvalRequiredToBypass;
    }

    /**
     * Returns the label required for approval, or {@code null} if not configured.
     *
     * @return the approval label
     */
    public String approvalLabel() {
        return approvalLabel;
    }

    /**
     * Returns the list of users or teams who can approve schema changes.
     *
     * @return an unmodifiable list of approver identifiers
     */
    public List<String> approvers() {
        return approvers;
    }

    /**
     * Returns a default gate configuration that blocks on breaking changes only.
     *
     * @return the default config
     */
    public static GateConfig defaultConfig() {
        return new GateConfig(BlockOn.BREAKING, false, null, List.of());
    }
}
