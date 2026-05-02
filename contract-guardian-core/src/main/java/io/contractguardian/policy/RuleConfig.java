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

    /**
     * Returns the number of previous schema versions the current version must remain compatible with.
     *
     * <p>A value of {@code 1} (the default) means compatibility is checked only against the
     * immediately preceding version. A value greater than {@code 1} causes the scanner to
     * check against the last N committed versions of the file on the base branch.
     *
     * <p>Subclasses override this method when they store the configured value.
     *
     * @return the n-version compatibility window; defaults to {@code 1}
     */
    public int nVersionCompatibility() {
        return 1;
    }
}
