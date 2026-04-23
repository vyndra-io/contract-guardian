package io.contractguardian.model;

/**
 * A single finding produced by a contract scanner.
 *
 * @param contractType the type of contract that was scanned
 * @param severity     the severity of the finding
 * @param file         the file path where the finding was detected
 * @param line         the line number, or -1 if not applicable
 * @param rule         the rule identifier (e.g. "field-removed", "type-changed")
 * @param message      human-readable description of the finding
 * @param detail       additional context, may be {@code null}
 * @param fix          suggested fix, may be {@code null}
 */
public record Finding(
        ContractType contractType,
        Severity severity,
        String file,
        int line,
        String rule,
        String message,
        String detail,
        String fix
) {

    /**
     * Creates a breaking finding with full detail.
     *
     * @param type    the contract type
     * @param file    the file path
     * @param rule    the rule identifier
     * @param message human-readable description
     * @param detail  additional context
     * @param fix     suggested fix
     * @return a new breaking finding
     */
    public static Finding breaking(final ContractType type, final String file, final String rule,
                                   final String message, final String detail, final String fix) {
        return new Finding(type, Severity.BREAKING, file, -1, rule, message, detail, fix);
    }

    /**
     * Creates a breaking finding without detail or fix.
     *
     * @param type    the contract type
     * @param file    the file path
     * @param rule    the rule identifier
     * @param message human-readable description
     * @return a new breaking finding
     */
    public static Finding breaking(final ContractType type, final String file,
                                   final String rule, final String message) {
        return new Finding(type, Severity.BREAKING, file, -1, rule, message, null, null);
    }

    /**
     * Creates a warning finding with full detail.
     *
     * @param type    the contract type
     * @param file    the file path
     * @param rule    the rule identifier
     * @param message human-readable description
     * @param detail  additional context
     * @param fix     suggested fix
     * @return a new warning finding
     */
    public static Finding warning(final ContractType type, final String file, final String rule,
                                  final String message, final String detail, final String fix) {
        return new Finding(type, Severity.WARNING, file, -1, rule, message, detail, fix);
    }

    /**
     * Creates a warning finding without detail or fix.
     *
     * @param type    the contract type
     * @param file    the file path
     * @param rule    the rule identifier
     * @param message human-readable description
     * @return a new warning finding
     */
    public static Finding warning(final ContractType type, final String file,
                                  final String rule, final String message) {
        return new Finding(type, Severity.WARNING, file, -1, rule, message, null, null);
    }

    /**
     * Creates an informational finding.
     *
     * @param type    the contract type
     * @param file    the file path
     * @param rule    the rule identifier
     * @param message human-readable description
     * @return a new info finding
     */
    public static Finding info(final ContractType type, final String file,
                               final String rule, final String message) {
        return new Finding(type, Severity.INFO, file, -1, rule, message, null, null);
    }
}
