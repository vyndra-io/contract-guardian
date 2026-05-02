package io.contractguardian.policy;

import io.contractguardian.model.ApprovalStatus;

/**
 * Checks whether an override approval is in place for the current PR or MR.
 *
 * <p>Implementations fetch label and event data from the host platform (GitHub, GitLab)
 * and return an {@link ApprovalStatus} indicating whether an authorised approver has
 * applied the configured override label.
 */
public interface ApprovalChecker {

    /**
     * Checks the current PR/MR for an active override approval.
     *
     * @param gate the gate configuration specifying the label name and permitted approvers
     * @return the resolved approval status; never {@code null}
     */
    ApprovalStatus check(GateConfig gate);
}
