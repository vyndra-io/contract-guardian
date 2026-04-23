package io.contractguardian.policy;

import io.contractguardian.util.GlobMatcher;

import java.util.List;

/**
 * A per-topic override for Kafka schema compatibility mode.
 *
 * <p>Allows different topics to use different compatibility rules
 * (e.g. internal topics may use {@code NONE} while public topics require {@code FULL}).
 */
public class CompatibilityOverride {

    private final String topicPattern;
    private final KafkaRuleConfig.CompatibilityMode compatibility;
    private final GlobMatcher matcher;

    /**
     * Creates a compatibility override for topics matching the given glob pattern.
     *
     * @param topicPattern  glob pattern to match against file paths
     * @param compatibility the compatibility mode to apply for matching topics
     */
    public CompatibilityOverride(final String topicPattern,
                                 final KafkaRuleConfig.CompatibilityMode compatibility) {
        this.topicPattern = topicPattern;
        this.compatibility = compatibility;
        this.matcher = new GlobMatcher(List.of(topicPattern));
    }

    /**
     * Tests whether the given file path matches this override's topic pattern.
     *
     * @param filePath the file path to test
     * @return {@code true} if the path matches
     */
    public boolean matches(final String filePath) {
        return matcher.matches(filePath);
    }

    /**
     * Returns the compatibility mode for this override.
     *
     * @return the compatibility mode
     */
    public KafkaRuleConfig.CompatibilityMode compatibility() {
        return compatibility;
    }

    /**
     * Returns the topic glob pattern.
     *
     * @return the topic pattern
     */
    public String topicPattern() {
        return topicPattern;
    }
}
