package io.contractguardian.model;

/**
 * Severity level of a finding produced by a contract scanner.
 */
public enum Severity {

    /** Change will cause runtime failures for consumers. */
    BREAKING,

    /** Change might cause issues and needs review. */
    WARNING,

    /** Informational finding, no action needed. */
    INFO
}
