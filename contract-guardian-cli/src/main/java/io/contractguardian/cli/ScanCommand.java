package io.contractguardian.cli;

import io.contractguardian.engine.DiffAnalyzer;
import io.contractguardian.engine.PolicyEngine;
import io.contractguardian.github.GitHubApprovalChecker;
import io.contractguardian.github.GitHubPrReporter;
import io.contractguardian.gitlab.GitLabApprovalChecker;
import io.contractguardian.gitlab.GitLabMrReporter;
import io.contractguardian.model.ApprovalStatus;
import io.contractguardian.model.ScanResult;
import io.contractguardian.model.Verdict;
import io.contractguardian.policy.ApprovalChecker;
import io.contractguardian.policy.GateConfig;
import io.contractguardian.policy.PolicyConfig;
import io.contractguardian.policy.PolicyParser;
import io.contractguardian.report.JUnitXmlReporter;
import io.contractguardian.report.Reporter;
import io.contractguardian.report.TerminalReporter;
import io.contractguardian.scanner.ScannerRegistry;
import io.contractguardian.util.GitHelper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Scans changed files for breaking contract changes.
 */
@Command(name = "scan", description = "Scan for breaking contract changes")
public class ScanCommand implements Callable<Integer> {

    @Option(names = {"--diff", "-d"}, required = true,
            description = "Git diff spec (e.g., origin/main..HEAD)")
    private String diffSpec;

    @Option(names = {"--config", "-c"}, defaultValue = ".contract-guardian.yml",
            description = "Path to config file")
    private String configFile;

    @Option(names = {"--reporter", "-r"},
            description = "Reporter: terminal, junit:<path>")
    private List<String> reporters;

    @Option(names = {"--github-pr"},
            description = "Post results as GitHub PR comment (format: owner/repo#123)")
    private String githubPr;

    @Option(names = {"--gitlab-mr"},
            description = "Post results as GitLab MR note (format: project-id!123)")
    private String gitlabMr;

    @Option(names = {"--working-dir", "-w"},
            description = "Working directory (defaults to current)")
    private Path workingDir;

    @Override
    public Integer call() {
        final Path root = workingDir != null ? workingDir : Path.of(".");
        final Path configPath = root.resolve(configFile);

        final PolicyParser parser = new PolicyParser();
        final PolicyConfig policy = parser.parseOrDefault(configPath);

        final GitHelper git = new GitHelper(root.toAbsolutePath());
        final ScannerRegistry registry = new ScannerRegistry();
        final DiffAnalyzer analyzer = new DiffAnalyzer(git, registry, policy);

        final Instant start = Instant.now();
        final List<ScanResult> results = analyzer.analyze(diffSpec);
        final Duration totalDuration = Duration.between(start, Instant.now());

        final ApprovalStatus approval = resolveApproval(policy.gate());
        final PolicyEngine engine = new PolicyEngine(policy);
        final Verdict verdict = engine.evaluate(results, totalDuration, approval);

        final List<Reporter> reporterList = buildReporters();
        for (final Reporter reporter : reporterList) {
            reporter.report(verdict, System.out);
        }

        return verdict.exitCode();
    }

    private ApprovalStatus resolveApproval(final GateConfig gate) {
        if (!gate.approvalRequiredToBypass()) {
            return ApprovalStatus.none();
        }
        if (githubPr != null && !githubPr.isBlank()) {
            try {
                final ApprovalChecker checker = GitHubApprovalChecker.fromSpec(githubPr);
                return checker.check(gate);
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println("Cannot check GitHub PR approval: " + e.getMessage());
            }
        }
        if (gitlabMr != null && !gitlabMr.isBlank()) {
            try {
                final ApprovalChecker checker = GitLabApprovalChecker.fromSpec(gitlabMr);
                return checker.check(gate);
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println("Cannot check GitLab MR approval: " + e.getMessage());
            }
        }
        return ApprovalStatus.none();
    }

    private List<Reporter> buildReporters() {
        final List<Reporter> result = new ArrayList<>();

        if (reporters == null || reporters.isEmpty()) {
            result.add(new TerminalReporter());
        } else {
            for (final String spec : reporters) {
                if ("terminal".equals(spec)) {
                    result.add(new TerminalReporter());
                } else if (spec.startsWith("junit:")) {
                    result.add(new JUnitXmlReporter(Path.of(spec.substring("junit:".length()))));
                } else {
                    System.err.println("Unknown reporter: " + spec);
                }
            }
            if (result.isEmpty()) {
                result.add(new TerminalReporter());
            }
        }

        if (githubPr != null && !githubPr.isBlank()) {
            try {
                result.add(GitHubPrReporter.fromSpec(githubPr));
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println("Cannot create GitHub PR reporter: " + e.getMessage());
            }
        }

        if (gitlabMr != null && !gitlabMr.isBlank()) {
            try {
                result.add(GitLabMrReporter.fromSpec(gitlabMr));
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println("Cannot create GitLab MR reporter: " + e.getMessage());
            }
        }

        return result;
    }
}
