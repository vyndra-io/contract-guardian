package io.contractguardian.policy;

/**
 * Abstract base class for scanner-specific rule configurations.
 *
 * <p>Each scanner type (Kafka, REST, DB) provides its own subclass
 * with domain-specific rules parsed from the policy file.
 */
public abstract class RuleConfig {

    private final String sourceName;

    /**
     * Creates a rule config for the given source name.
     *
     * @param sourceName the source name matching the key in the YAML rules section
     */
    protected RuleConfig(final String sourceName) {
        this.sourceName = sourceName;
    }

    /**
     * Returns the source name this rule config applies to.
     *
     * @return the source name (e.g. "kafka", "rest")
     */
    public String sourceName() {
        return sourceName;
    }
}
