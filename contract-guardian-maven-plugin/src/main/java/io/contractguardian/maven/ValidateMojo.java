package io.contractguardian.maven;

import io.contractguardian.engine.DiffAnalyzer;
import io.contractguardian.engine.PolicyEngine;
import io.contractguardian.model.ScanResult;
import io.contractguardian.model.Verdict;
import io.contractguardian.model.VerdictStatus;
import io.contractguardian.policy.PolicyConfig;
import io.contractguardian.policy.PolicyParser;
import io.contractguardian.policy.PolicyValidator;
import io.contractguardian.scanner.ScannerRegistry;
import io.contractguardian.util.GitHelper;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Validates schema contracts against a baseline and fails the build
 * if breaking changes are detected according to the configured policy.
 */
@Mojo(name = "validate", defaultPhase = LifecyclePhase.VALIDATE)
public class ValidateMojo extends AbstractMojo {

    /**
     * Path to the contract-guardian policy file.
     */
    @Parameter(property = "contractguardian.policyFile", defaultValue = "${project.basedir}/.contract-guardian.yml")
    private File policyFile;

    /**
     * Git diff spec to determine which files changed (e.g. "origin/main..HEAD").
     */
    @Parameter(property = "contractguardian.diff", defaultValue = "origin/main..HEAD")
    private String diff;

    /**
     * Working directory for git operations.
     */
    @Parameter(property = "contractguardian.workingDir", defaultValue = "${project.basedir}")
    private File workingDir;

    /**
     * Skip the validation.
     */
    @Parameter(property = "contractguardian.skip", defaultValue = "false")
    private boolean skip;

    /**
     * If true, fail the build when the baseline git ref cannot be resolved.
     * If false (default), log a warning and skip validation.
     */
    @Parameter(property = "contractguardian.failOnMissingRef", defaultValue = "false")
    private boolean failOnMissingRef;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Contract Guardian validation skipped.");
            return;
        }

        final Path workingDirPath = workingDir.toPath();
        final Path policyFilePath = policyFile.toPath();

        final GitHelper git = new GitHelper(workingDirPath);
        if (!git.isGitRepo()) {
            throw new MojoExecutionException(
                    "Not a git repository: " + workingDirPath + ". Contract Guardian requires git.");
        }

        final PolicyConfig policy = loadPolicy(policyFilePath);
        validatePolicy(policy);

        getLog().info("Contract Guardian: scanning changes (" + diff + ")...");

        final ScannerRegistry registry = new ScannerRegistry();
        final DiffAnalyzer analyzer = new DiffAnalyzer(git, registry, policy);

        final Instant start = Instant.now();
        final List<ScanResult> results;
        try {
            results = analyzer.analyze(diff);
        } catch (GitHelper.GitException e) {
            if (failOnMissingRef) {
                throw new MojoExecutionException(
                        "Contract Guardian: git ref '" + diff + "' could not be resolved. "
                                + "Ensure the branch/ref exists and has been fetched. "
                                + "You can override with -Dcontractguardian.diff=<ref> "
                                + "or skip with -Dcontractguardian.skip=true.", e);
            }
            getLog().warn("Contract Guardian: git ref '" + diff + "' not found — skipping validation. "
                    + "This is normal for local builds without a remote. "
                    + "Set -Dcontractguardian.failOnMissingRef=true to treat this as an error.");
            return;
        }
        final Duration totalDuration = Duration.between(start, Instant.now());

        if (results.isEmpty()) {
            getLog().info("Contract Guardian: no contract files changed — nothing to validate.");
            return;
        }

        final PolicyEngine engine = new PolicyEngine(policy);
        final Verdict verdict = engine.evaluate(results, totalDuration);

        logVerdict(verdict);

        if (verdict.status() == VerdictStatus.FAIL) {
            throw new MojoFailureException(
                    "Contract Guardian: BUILD FAILED — " + verdict.breakingCount()
                            + " breaking change(s) detected. Fix the schema incompatibilities or update the policy.");
        }
    }

    private PolicyConfig loadPolicy(final Path policyFilePath) throws MojoExecutionException {
        try {
            final PolicyParser parser = new PolicyParser();
            return parser.parseOrDefault(policyFilePath);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to load policy file: " + policyFilePath, e);
        }
    }

    private void validatePolicy(final PolicyConfig policy) throws MojoExecutionException {
        final PolicyValidator validator = new PolicyValidator();
        final List<String> errors = validator.validate(policy);
        if (!errors.isEmpty()) {
            throw new MojoExecutionException(
                    "Invalid policy configuration:\n  - " + String.join("\n  - ", errors));
        }
    }

    private void logVerdict(final Verdict verdict) {
        for (final ScanResult result : verdict.results()) {
            result.findings().forEach(finding -> {
                final String msg = String.format("[%s] %s — %s",
                        finding.severity(), finding.file(), finding.message());
                switch (finding.severity()) {
                    case BREAKING -> getLog().error(msg);
                    case WARNING -> getLog().warn(msg);
                    case INFO -> getLog().info(msg);
                }
            });
        }

        getLog().info(String.format("Contract Guardian: %s (%d breaking, %d warning, %d pass)",
                verdict.status(), verdict.breakingCount(), verdict.warningCount(), verdict.passCount()));
    }
}
