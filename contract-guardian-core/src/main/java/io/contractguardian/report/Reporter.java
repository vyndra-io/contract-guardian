package io.contractguardian.report;

import io.contractguardian.model.Verdict;

import java.io.PrintStream;

/**
 * Outputs a scan verdict in a specific format.
 *
 * <p>Implementations include terminal output, JUnit XML, and CI provider comments.
 */
public interface Reporter {

    /**
     * Reports the verdict.
     *
     * @param verdict the scan verdict to report
     * @param out     the output stream for status messages
     */
    void report(Verdict verdict, PrintStream out);
}
