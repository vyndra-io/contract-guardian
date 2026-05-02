package io.contractguardian.engine;

import io.contractguardian.model.ContractType;
import io.contractguardian.model.Finding;
import io.contractguardian.model.ScanResult;
import io.contractguardian.policy.GateConfig;
import io.contractguardian.policy.KafkaRuleConfig;
import io.contractguardian.policy.KafkaRuleConfig.CompatibilityMode;
import io.contractguardian.policy.PolicyConfig;
import io.contractguardian.policy.RuleConfig;
import io.contractguardian.policy.SourceConfig;
import io.contractguardian.scanner.ContractScanner;
import io.contractguardian.scanner.ScannerRegistry;
import io.contractguardian.util.GitHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiffAnalyzerTest {

    private static final String DIFF_SPEC = "origin/main..HEAD";
    private static final String BASE_REF = "origin/main";
    private static final String FILE = "schemas/orders.avsc";

    @Test
    void nVersionOne_scannerCalledOnceWithSingleBaseline(@TempDir Path workDir) throws IOException {
        final Path baseline = Files.createTempFile(workDir, "baseline-", ".avsc");
        final KafkaRuleConfig config = new KafkaRuleConfig(CompatibilityMode.BACKWARD, List.of(), 1);

        final ContractScanner scanner = stubScanner(singleFinding("field-removed", "Field id removed"));
        final ScannerRegistry registry = registryWith(scanner);
        final StubGitHelper git = new StubGitHelper(workDir, List.of(FILE), BASE_REF, baseline, List.of());

        final DiffAnalyzer analyzer = new DiffAnalyzer(git, registry, policy(config));
        final List<ScanResult> results = analyzer.analyze(DIFF_SPEC);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).findings()).hasSize(1);
        verify(scanner, times(1)).scan(any(), any(), any());
    }

    @Test
    void nVersionTwo_scannerCalledForEachBaseline(@TempDir Path workDir) throws IOException {
        final Path baseline1 = Files.createTempFile(workDir, "baseline1-", ".avsc");
        final Path baseline2 = Files.createTempFile(workDir, "baseline2-", ".avsc");
        final KafkaRuleConfig config = new KafkaRuleConfig(CompatibilityMode.BACKWARD, List.of(), 2);

        final ContractScanner scanner = stubScanner(singleFinding("field-removed", "Field id removed"));
        final ScannerRegistry registry = registryWith(scanner);
        final StubGitHelper git = new StubGitHelper(workDir, List.of(FILE), BASE_REF, null,
                List.of(baseline1, baseline2));

        final DiffAnalyzer analyzer = new DiffAnalyzer(git, registry, policy(config));
        final List<ScanResult> results = analyzer.analyze(DIFF_SPEC);

        assertThat(results).hasSize(1);
        verify(scanner, times(2)).scan(any(), any(), any());
    }

    @Test
    void nVersionTwo_duplicateFindingsDeduplicatedInResult(@TempDir Path workDir) throws IOException {
        final Path baseline1 = Files.createTempFile(workDir, "baseline1-", ".avsc");
        final Path baseline2 = Files.createTempFile(workDir, "baseline2-", ".avsc");
        final KafkaRuleConfig config = new KafkaRuleConfig(CompatibilityMode.BACKWARD, List.of(), 2);

        final Finding duplicate = Finding.breaking(ContractType.KAFKA_AVRO, FILE, "field-removed", "Field id removed");
        final ContractScanner scanner = mock(ContractScanner.class);
        when(scanner.canScan(any(), any())).thenReturn(true);
        when(scanner.scan(any(), any(), any()))
                .thenReturn(scanResult(List.of(duplicate)))
                .thenReturn(scanResult(List.of(duplicate)));
        final ScannerRegistry registry = registryWith(scanner);
        final StubGitHelper git = new StubGitHelper(workDir, List.of(FILE), BASE_REF, null,
                List.of(baseline1, baseline2));

        final DiffAnalyzer analyzer = new DiffAnalyzer(git, registry, policy(config));
        final List<ScanResult> results = analyzer.analyze(DIFF_SPEC);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).findings()).hasSize(1);
    }

    @Test
    void nVersionTwo_distinctFindingsFromDifferentBaselinesMerged(@TempDir Path workDir) throws IOException {
        final Path baseline1 = Files.createTempFile(workDir, "baseline1-", ".avsc");
        final Path baseline2 = Files.createTempFile(workDir, "baseline2-", ".avsc");
        final KafkaRuleConfig config = new KafkaRuleConfig(CompatibilityMode.BACKWARD, List.of(), 2);

        final Finding finding1 = Finding.breaking(ContractType.KAFKA_AVRO, FILE, "field-removed", "Field id removed");
        final Finding finding2 = Finding.breaking(ContractType.KAFKA_AVRO, FILE, "type-changed", "Type of amount changed");
        final ContractScanner scanner = mock(ContractScanner.class);
        when(scanner.canScan(any(), any())).thenReturn(true);
        when(scanner.scan(any(), any(), any()))
                .thenReturn(scanResult(List.of(finding1)))
                .thenReturn(scanResult(List.of(finding2)));
        final ScannerRegistry registry = registryWith(scanner);
        final StubGitHelper git = new StubGitHelper(workDir, List.of(FILE), BASE_REF, null,
                List.of(baseline1, baseline2));

        final DiffAnalyzer analyzer = new DiffAnalyzer(git, registry, policy(config));
        final List<ScanResult> results = analyzer.analyze(DIFF_SPEC);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).findings()).containsExactlyInAnyOrder(finding1, finding2);
    }

    @Test
    void noBaseline_scannerCalledWithNull(@TempDir Path workDir) {
        final KafkaRuleConfig config = new KafkaRuleConfig(CompatibilityMode.BACKWARD, List.of(), 1);

        final ContractScanner scanner = stubScanner(List.of());
        final ScannerRegistry registry = registryWith(scanner);
        // null baseline simulates a newly added file
        final StubGitHelper git = new StubGitHelper(workDir, List.of(FILE), BASE_REF, null, List.of());

        final DiffAnalyzer analyzer = new DiffAnalyzer(git, registry, policy(config));
        final List<ScanResult> results = analyzer.analyze(DIFF_SPEC);

        assertThat(results).hasSize(1);
        verify(scanner, times(1)).scan(any(), any(), any());
    }

    // --- helpers ---

    private PolicyConfig policy(final KafkaRuleConfig config) {
        final Map<String, SourceConfig> sources = new LinkedHashMap<>();
        sources.put("kafka", new SourceConfig("kafka", List.of("schemas/**/*.avsc"), "branch:main"));
        final Map<String, RuleConfig> rules = new LinkedHashMap<>();
        rules.put("kafka", config);
        return new PolicyConfig("1", sources, rules, GateConfig.defaultConfig());
    }

    private ContractScanner stubScanner(final Finding finding) {
        return stubScanner(List.of(finding));
    }

    private ContractScanner stubScanner(final List<Finding> findings) {
        final ContractScanner scanner = mock(ContractScanner.class);
        when(scanner.canScan(any(), any())).thenReturn(true);
        when(scanner.scan(any(), any(), any())).thenReturn(scanResult(findings));
        return scanner;
    }

    private ScanResult scanResult(final List<Finding> findings) {
        return new ScanResult(FILE, ContractType.KAFKA_AVRO, findings, Duration.ofMillis(1));
    }

    private ScannerRegistry registryWith(final ContractScanner scanner) {
        return new ScannerRegistry(List.of(scanner));
    }

    private Finding singleFinding(final String rule, final String message) {
        return Finding.breaking(ContractType.KAFKA_AVRO, FILE, rule, message);
    }

    /**
     * Stub GitHelper that returns pre-configured values without running git commands.
     * Used in place of mocking since GitHelper is a concrete class on Java 25.
     */
    private static final class StubGitHelper extends GitHelper {

        private final Path workDir;
        private final List<String> changedFiles;
        private final String baseRef;
        private final Path singleBaseline;
        private final List<Path> multiBaselines;

        StubGitHelper(final Path workDir, final List<String> changedFiles, final String baseRef,
                      final Path singleBaseline, final List<Path> multiBaselines) {
            super(workDir);
            this.workDir = workDir;
            this.changedFiles = changedFiles;
            this.baseRef = baseRef;
            this.singleBaseline = singleBaseline;
            this.multiBaselines = List.copyOf(multiBaselines);
        }

        @Override
        public Path workingDir() {
            return workDir;
        }

        @Override
        public String baseRef(final String diffSpec) {
            return baseRef;
        }

        @Override
        public List<String> changedFiles(final String diffSpec) {
            return changedFiles;
        }

        @Override
        public Path extractFileAtRef(final String ref, final String filePath) {
            return singleBaseline;
        }

        @Override
        public List<Path> fileHistoryAtRef(final String ref, final String filePath, final int n) {
            return multiBaselines;
        }
    }
}
