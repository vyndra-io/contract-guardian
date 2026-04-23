package io.contractguardian.report;

import io.contractguardian.model.*;

import java.io.PrintStream;

/**
 * Reports scan results as colored terminal output with Unicode status icons.
 */
public class TerminalReporter implements Reporter {

    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN = "\u001B[32m";
    private static final String BOLD = "\u001B[1m";
    private static final String RESET = "\u001B[0m";

    private static final String CROSS = "\u2717";
    private static final String WARNING_SIGN = "\u26A0";
    private static final String CHECK = "\u2713";

    private final boolean useColor;

    /**
     * Creates a terminal reporter with automatic color detection.
     */
    public TerminalReporter() {
        this(System.console() != null);
    }

    /**
     * Creates a terminal reporter with explicit color control.
     *
     * @param useColor {@code true} to enable ANSI color codes
     */
    public TerminalReporter(final boolean useColor) {
        this.useColor = useColor;
    }

    @Override
    public void report(final Verdict verdict, final PrintStream out) {
        out.println();
        out.println("  Scanning " + verdict.results().size() + " changed files...");
        out.println();

        for (final ScanResult result : verdict.results()) {
            printResult(result, out);
        }

        out.println();
        printSummary(verdict, out);
        out.println();
    }

    private void printResult(final ScanResult result, final PrintStream out) {
        if (result.hasBreaking()) {
            out.println("  " + colorize(CROSS + " BREAKING", RED, true) + "  " + result.file());
            for (final Finding f : result.findings()) {
                if (f.severity() == Severity.BREAKING) {
                    out.println("    " + f.message());
                    if (f.detail() != null) {
                        out.println("    " + f.detail());
                    }
                }
            }
        } else if (result.hasWarnings()) {
            out.println("  " + colorize(WARNING_SIGN + " WARNING", YELLOW, true) + "   " + result.file());
            for (final Finding f : result.findings()) {
                if (f.severity() == Severity.WARNING) {
                    out.println("    " + f.message());
                }
            }
        } else {
            out.println("  " + colorize(CHECK + " PASS", GREEN, true) + "      " + result.file());
            if (!result.findings().isEmpty()) {
                for (final Finding f : result.findings()) {
                    out.println("    " + f.message());
                }
            }
        }
        out.println();
    }

    private void printSummary(final Verdict verdict, final PrintStream out) {
        final long breaking = verdict.breakingCount();
        final long warnings = verdict.warningCount();
        final long pass = verdict.passCount();

        final String status = switch (verdict.status()) {
            case FAIL -> colorize("FAIL", RED, true);
            case WARN -> colorize("WARN", YELLOW, true);
            case PASS -> colorize("PASS", GREEN, true);
        };

        out.printf("  Result: %s (%d breaking, %d warning, %d pass)%n",
                status, breaking, warnings, pass);

        if (verdict.status() == VerdictStatus.FAIL) {
            out.println("  Policy requires 0 breaking changes to merge.");
        }
    }

    private String colorize(final String text, final String color, final boolean bold) {
        if (!useColor) {
            return text;
        }
        final String prefix = bold ? BOLD + color : color;
        return prefix + text + RESET;
    }
}
