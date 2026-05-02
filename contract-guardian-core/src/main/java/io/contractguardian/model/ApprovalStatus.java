package io.contractguardian.model;

/**
 * Carries the result of checking whether an override approval is in place for a PR/MR.
 *
 * <p>When {@link #approved()} is {@code true}, the verdict is downgraded from
 * {@link VerdictStatus#FAIL} to {@link VerdictStatus#WARN} so the PR can merge
 * despite breaking changes, with a visible audit trail in the output.
 *
 * @param approved   {@code true} when the required label was applied by an authorised approver
 * @param approvedBy the username who applied the label, or empty string when not approved
 */
public record ApprovalStatus(boolean approved, String approvedBy) {

    /**
     * Returns an unapproved status, used when no CI integration is configured or the
     * approval label is absent.
     *
     * @return an unapproved {@link ApprovalStatus}
     */
    public static ApprovalStatus none() {
        return new ApprovalStatus(false, "");
    }
}
