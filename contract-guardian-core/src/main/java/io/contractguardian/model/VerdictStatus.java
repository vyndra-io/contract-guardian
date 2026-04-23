package io.contractguardian.model;

/**
 * Overall result status after evaluating scan findings against the gate policy.
 */
public enum VerdictStatus {

    /** All scanned files passed compatibility checks. */
    PASS,

    /** Warnings were found but no breaking changes. */
    WARN,

    /** Breaking changes detected; merge should be blocked. */
    FAIL
}
