package io.contractguardian.report;

import io.contractguardian.model.*;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reports scan results as JUnit XML for CI dashboard integration.
 */
public class JUnitXmlReporter implements Reporter {

    private final Path outputFile;

    /**
     * Creates a JUnit XML reporter that writes to the given file.
     *
     * @param outputFile the output file path for the XML report
     */
    public JUnitXmlReporter(final Path outputFile) {
        this.outputFile = outputFile;
    }

    @Override
    public void report(final Verdict verdict, final PrintStream out) {
        try (OutputStream os = openOutput(outputFile)) {
            final XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(os, "UTF-8");
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("testsuites");
            xml.writeAttribute("name", "Contract Guardian");

            for (final ScanResult result : verdict.results()) {
                final int tests = Math.max(1, result.findings().size());
                final int failures = (int) result.findings().stream()
                        .filter(f -> f.severity() == Severity.BREAKING)
                        .count();

                xml.writeStartElement("testsuite");
                xml.writeAttribute("name", result.file());
                xml.writeAttribute("tests", String.valueOf(tests));
                xml.writeAttribute("failures", String.valueOf(failures));
                xml.writeAttribute("time",
                        String.format("%.3f", result.scanDuration().toMillis() / 1000.0));

                if (result.findings().isEmpty()) {
                    writePassingTestCase(xml, result);
                } else {
                    writeFindings(xml, result);
                }

                xml.writeEndElement(); // testsuite
            }

            xml.writeEndElement(); // testsuites
            xml.writeEndDocument();
            xml.flush();
            xml.close();

            out.println("JUnit XML report written to: " + outputFile);
        } catch (IOException | XMLStreamException e) {
            out.println("Failed to write JUnit XML report: " + e.getMessage());
        }
    }

    private static OutputStream openOutput(final Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        return Files.newOutputStream(file);
    }

    private void writePassingTestCase(final XMLStreamWriter xml,
                                      final ScanResult result) throws XMLStreamException {
        xml.writeStartElement("testcase");
        xml.writeAttribute("name", "compatibility-check");
        xml.writeAttribute("classname", result.file());
        xml.writeEndElement();
    }

    private void writeFindings(final XMLStreamWriter xml,
                               final ScanResult result) throws XMLStreamException {
        for (final Finding finding : result.findings()) {
            xml.writeStartElement("testcase");
            xml.writeAttribute("name", finding.rule());
            xml.writeAttribute("classname", result.file());

            if (finding.severity() == Severity.BREAKING) {
                xml.writeStartElement("failure");
                xml.writeAttribute("message", finding.message());
                xml.writeAttribute("type", finding.rule());
                if (finding.detail() != null) {
                    xml.writeCharacters(finding.detail());
                }
                xml.writeEndElement();
            }

            xml.writeEndElement();
        }
    }
}
