package io.contractguardian.policy;

import io.contractguardian.util.GlobMatcher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable representation of a parsed {@code .contract-guardian.yml} policy file.
 *
 * <p>Contains source definitions, rule configurations, and gate settings
 * that govern how contract scans are performed and evaluated.
 */
public class PolicyConfig {

    private final String version;
    private final Map<String, SourceConfig> sources;
    private final Map<String, RuleConfig> rules;
    private final GateConfig gate;

    /**
     * Creates a policy configuration.
     *
     * @param version the config version (currently only "1")
     * @param sources the named source configurations
     * @param rules   the rule configurations keyed by source name
     * @param gate    the gate configuration
     */
    public PolicyConfig(final String version, final Map<String, SourceConfig> sources,
                        final Map<String, RuleConfig> rules, final GateConfig gate) {
        this.version = version;
        this.sources = Map.copyOf(sources);
        this.rules = Map.copyOf(rules);
        this.gate = gate;
    }

    /**
     * Returns the config version.
     *
     * @return the version string
     */
    public String version() {
        return version;
    }

    /**
     * Returns the source configurations keyed by name.
     *
     * @return an unmodifiable map of source configs
     */
    public Map<String, SourceConfig> sources() {
        return sources;
    }

    /**
     * Returns the rule configurations keyed by source name.
     *
     * @return an unmodifiable map of rule configs
     */
    public Map<String, RuleConfig> rules() {
        return rules;
    }

    /**
     * Returns the gate configuration.
     *
     * @return the gate config
     */
    public GateConfig gate() {
        return gate;
    }

    /**
     * Finds the source configuration whose glob patterns match the given file path.
     *
     * @param relativePath the file path relative to the working directory
     * @return the matching source config, or empty if no source matches
     */
    public Optional<SourceConfig> findSourceFor(final String relativePath) {
        for (final SourceConfig source : sources.values()) {
            final GlobMatcher matcher = new GlobMatcher(source.paths());
            if (matcher.matches(relativePath)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the rule configuration for the given source.
     *
     * <p>Falls back to a default config if no rules are explicitly defined
     * for the source name.
     *
     * @param source the source config to look up rules for
     * @return the matching rule config, or empty if the source type is unknown
     */
    public Optional<RuleConfig> ruleConfigFor(final SourceConfig source) {
        final RuleConfig config = rules.get(source.name());
        if (config != null) {
            return Optional.of(config);
        }
        if ("kafka".equals(source.name())) {
            return Optional.of(KafkaRuleConfig.defaultConfig());
        }
        if ("rest".equals(source.name())) {
            return Optional.of(RestRuleConfig.defaultConfig());
        }
        if ("database".equals(source.name())) {
            return Optional.of(DbRuleConfig.defaultConfig());
        }
        return Optional.empty();
    }

    /**
     * Returns a default policy configuration with a Kafka Avro source.
     *
     * @return the default config
     */
    public static PolicyConfig defaultConfig() {
        final Map<String, SourceConfig> sources = new LinkedHashMap<>();
        sources.put("kafka", new SourceConfig("kafka",
                List.of("schemas/kafka/**/*.avsc"), "branch:main"));

        final Map<String, RuleConfig> rules = new LinkedHashMap<>();
        rules.put("kafka", KafkaRuleConfig.defaultConfig());

        return new PolicyConfig("1", sources, rules, GateConfig.defaultConfig());
    }
}
