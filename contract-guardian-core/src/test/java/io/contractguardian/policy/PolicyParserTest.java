package io.contractguardian.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyParserTest {

    private final PolicyParser parser = new PolicyParser();

    @Test
    void parseValidConfig() {
        Path configFile = Path.of("src/test/resources/policy/valid-config.yml");
        PolicyConfig config = parser.parse(configFile);

        assertThat(config.version()).isEqualTo("1");
        assertThat(config.sources()).containsKey("kafka");
        assertThat(config.sources().get("kafka").paths()).containsExactly("schemas/kafka/**/*.avsc");
        assertThat(config.gate().blockOn()).isEqualTo(GateConfig.BlockOn.BREAKING);
    }

    @Test
    void parseKafkaRules() {
        Path configFile = Path.of("src/test/resources/policy/valid-config.yml");
        PolicyConfig config = parser.parse(configFile);

        KafkaRuleConfig kafkaRules = (KafkaRuleConfig) config.rules().get("kafka");
        assertThat(kafkaRules.compatibility()).isEqualTo(KafkaRuleConfig.CompatibilityMode.BACKWARD);
        assertThat(kafkaRules.overrides()).hasSize(1);
        assertThat(kafkaRules.overrides().get(0).topicPattern()).isEqualTo("internal.*");
    }

    @Test
    void parseOrDefault_missingFile_returnsDefault(@TempDir Path tempDir) {
        PolicyConfig config = parser.parseOrDefault(tempDir.resolve("nonexistent.yml"));
        assertThat(config.version()).isEqualTo("1");
        assertThat(config.sources()).containsKey("kafka");
    }

    @Test
    void parseEmptyFile_throws(@TempDir Path tempDir) throws IOException {
        Path empty = tempDir.resolve("empty.yml");
        Files.writeString(empty, "");
        assertThatThrownBy(() -> parser.parse(empty))
                .isInstanceOf(PolicyParseException.class);
    }

    @Test
    void parseRestRules() {
        Path configFile = Path.of("src/test/resources/policy/valid-config-rest.yml");
        PolicyConfig config = parser.parse(configFile);

        assertThat(config.sources()).containsKey("rest");
        assertThat(config.sources().get("rest").paths()).containsExactly("api/openapi/**/*.yaml");

        RestRuleConfig restRules = (RestRuleConfig) config.rules().get("rest");
        assertThat(restRules.breaking()).containsExactly(
                "endpoint-removed", "required-param-added", "response-field-removed",
                "response-field-type-changed", "status-code-removed");
        assertThat(restRules.warning()).containsExactly(
                "response-field-deprecated", "parameter-renamed");
        assertThat(restRules.ignorePaths()).containsExactly("/internal/**", "/admin/**");
    }

    @Test
    void restRuleConfig_isBreakingAndWarning() {
        RestRuleConfig config = RestRuleConfig.defaultConfig();

        assertThat(config.isBreaking("endpoint-removed")).isTrue();
        assertThat(config.isBreaking("response-field-deprecated")).isFalse();
        assertThat(config.isWarning("response-field-deprecated")).isTrue();
        assertThat(config.isWarning("endpoint-removed")).isFalse();
    }

    @Test
    void restRuleConfig_isIgnored() {
        RestRuleConfig config = new RestRuleConfig(
                List.of(), List.of(), List.of("/internal/**", "/admin/**"), 1);

        assertThat(config.isIgnored("/internal/health")).isTrue();
        assertThat(config.isIgnored("/internal/deep/path")).isTrue();
        assertThat(config.isIgnored("/admin/users")).isTrue();
        assertThat(config.isIgnored("/api/users")).isFalse();
    }

    @Test
    void policyConfig_ruleConfigFor_restDefault() {
        PolicyConfig config = PolicyConfig.defaultConfig();
        SourceConfig restSource = new SourceConfig("rest", List.of("api/**/*.yaml"), "branch:main");

        assertThat(config.ruleConfigFor(restSource))
                .isPresent()
                .get()
                .isInstanceOf(RestRuleConfig.class);
    }

    @Test
    void kafkaRules_nVersionCompatibility_parsedFromFixture() {
        Path configFile = Path.of("src/test/resources/policy/valid-config.yml");
        PolicyConfig config = parser.parse(configFile);

        KafkaRuleConfig kafkaRules = (KafkaRuleConfig) config.rules().get("kafka");
        assertThat(kafkaRules.nVersionCompatibility()).isEqualTo(2);
    }

    @Test
    void kafkaRules_nVersionCompatibility_defaultsToOne(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("kafka-no-n.yml");
        Files.writeString(config, """
                version: "1"
                sources:
                  kafka:
                    paths:
                      - "**/*.avsc"
                rules:
                  kafka:
                    compatibility: BACKWARD
                """);
        KafkaRuleConfig kafkaRules = (KafkaRuleConfig) parser.parse(config).rules().get("kafka");
        assertThat(kafkaRules.nVersionCompatibility()).isEqualTo(1);
    }

    @Test
    void restRules_nVersionCompatibility_parsedFromYaml(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("rest-with-n.yml");
        Files.writeString(config, """
                version: "1"
                sources:
                  rest:
                    paths:
                      - "api/**/*.yaml"
                rules:
                  rest:
                    n-version-compatibility: 3
                """);
        RestRuleConfig restRules = (RestRuleConfig) parser.parse(config).rules().get("rest");
        assertThat(restRules.nVersionCompatibility()).isEqualTo(3);
    }

    @Test
    void restRules_nVersionCompatibility_defaultsToOne(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("rest-no-n.yml");
        Files.writeString(config, """
                version: "1"
                sources:
                  rest:
                    paths:
                      - "api/**/*.yaml"
                rules:
                  rest:
                    breaking:
                      - endpoint-removed
                """);
        RestRuleConfig restRules = (RestRuleConfig) parser.parse(config).rules().get("rest");
        assertThat(restRules.nVersionCompatibility()).isEqualTo(1);
    }

    @Test
    void parseMinimalConfig(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("minimal.yml");
        Files.writeString(config, """
                version: "1"
                sources:
                  kafka:
                    paths:
                      - "**/*.avsc"
                """);
        PolicyConfig parsed = parser.parse(config);
        assertThat(parsed.version()).isEqualTo("1");
        assertThat(parsed.gate().blockOn()).isEqualTo(GateConfig.BlockOn.BREAKING);
    }
}
