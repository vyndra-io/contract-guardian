package io.contractguardian.policy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyValidatorTest {

    private final PolicyValidator validator = new PolicyValidator();

    @Test
    void validConfig_noErrors() {
        PolicyConfig config = PolicyConfig.defaultConfig();
        List<String> errors = validator.validate(config);
        assertThat(errors).isEmpty();
    }

    @Test
    void invalidVersion_error() {
        PolicyConfig config = new PolicyConfig("2",
                Map.of("kafka", new SourceConfig("kafka", List.of("**/*.avsc"), "branch:main")),
                new LinkedHashMap<>(), GateConfig.defaultConfig());
        List<String> errors = validator.validate(config);
        assertThat(errors).anyMatch(e -> e.contains("version"));
    }

    @Test
    void noSources_error() {
        PolicyConfig config = new PolicyConfig("1", Map.of(), new LinkedHashMap<>(), GateConfig.defaultConfig());
        List<String> errors = validator.validate(config);
        assertThat(errors).anyMatch(e -> e.contains("source"));
    }

    @Test
    void emptyPaths_error() {
        PolicyConfig config = new PolicyConfig("1",
                Map.of("kafka", new SourceConfig("kafka", List.of(), "branch:main")),
                new LinkedHashMap<>(), GateConfig.defaultConfig());
        List<String> errors = validator.validate(config);
        assertThat(errors).anyMatch(e -> e.contains("paths"));
    }
}
