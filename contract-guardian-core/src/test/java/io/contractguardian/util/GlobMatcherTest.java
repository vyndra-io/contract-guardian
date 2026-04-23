package io.contractguardian.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobMatcherTest {

    @Test
    void matchesAvroPattern() {
        GlobMatcher matcher = new GlobMatcher(List.of("schemas/kafka/**/*.avsc"));
        assertThat(matcher.matches("schemas/kafka/orders/value.avsc")).isTrue();
        assertThat(matcher.matches("schemas/kafka/user-value.avsc")).isTrue();
    }

    @Test
    void doesNotMatchDifferentExtension() {
        GlobMatcher matcher = new GlobMatcher(List.of("schemas/kafka/**/*.avsc"));
        assertThat(matcher.matches("schemas/kafka/orders/value.json")).isFalse();
    }

    @Test
    void doesNotMatchDifferentDirectory() {
        GlobMatcher matcher = new GlobMatcher(List.of("schemas/kafka/**/*.avsc"));
        assertThat(matcher.matches("schemas/rest/api.yaml")).isFalse();
    }

    @Test
    void multiplePatterns() {
        GlobMatcher matcher = new GlobMatcher(List.of("**/*.avsc", "**/*.json"));
        assertThat(matcher.matches("schemas/kafka/test.avsc")).isTrue();
        assertThat(matcher.matches("schemas/kafka/test.json")).isTrue();
        assertThat(matcher.matches("schemas/kafka/test.proto")).isFalse();
    }
}
