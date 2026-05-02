package io.contractguardian.policy;

import java.util.List;

/**
 * Rule configuration for Kafka schema compatibility checks.
 *
 * <p>Defines the default compatibility mode and optional per-topic overrides.
 */
public class KafkaRuleConfig extends RuleConfig {

    /**
     * Schema compatibility modes supported by Kafka scanners.
     */
    public enum CompatibilityMode {

        /** New schema can read data written by old schema. */
        BACKWARD,

        /** Old schema can read data written by new schema. */
        FORWARD,

        /** Both backward and forward compatible. */
        FULL,

        /** No compatibility checks performed. */
        NONE
    }

    private final CompatibilityMode compatibility;
    private final List<CompatibilityOverride> overrides;
    private final int nVersionCompatibility;

    /**
     * Creates a Kafka rule configuration.
     *
     * @param compatibility        the default compatibility mode
     * @param overrides            per-topic compatibility overrides
     * @param nVersionCompatibility number of previous versions that must remain compatible
     */
    public KafkaRuleConfig(final CompatibilityMode compatibility,
                           final List<CompatibilityOverride> overrides,
                           final int nVersionCompatibility) {
        super("kafka");
        this.compatibility = compatibility;
        this.overrides = List.copyOf(overrides);
        this.nVersionCompatibility = nVersionCompatibility;
    }

    /**
     * Returns the default compatibility mode.
     *
     * @return the compatibility mode
     */
    public CompatibilityMode compatibility() {
        return compatibility;
    }

    /**
     * Returns the per-topic compatibility overrides.
     *
     * @return an unmodifiable list of overrides
     */
    public List<CompatibilityOverride> overrides() {
        return overrides;
    }

    /**
     * Returns the number of previous schema versions the current version must remain compatible with.
     *
     * @return the n-version compatibility window
     */
    @Override
    public int nVersionCompatibility() {
        return nVersionCompatibility;
    }

    /**
     * Resolves the effective compatibility mode for a given file path.
     *
     * <p>If an override's topic pattern matches the file path, that override's
     * mode is returned. Otherwise the default mode is used.
     *
     * @param filePath the file path to check against override patterns
     * @return the effective compatibility mode
     */
    public CompatibilityMode compatibilityFor(final String filePath) {
        return overrides.stream()
                .filter(o -> o.matches(filePath))
                .map(CompatibilityOverride::compatibility)
                .findFirst()
                .orElse(compatibility);
    }

    /**
     * Returns a default Kafka rule configuration with {@link CompatibilityMode#BACKWARD}
     * and no overrides.
     *
     * @return the default config
     */
    public static KafkaRuleConfig defaultConfig() {
        return new KafkaRuleConfig(CompatibilityMode.BACKWARD, List.of(), 1);
    }
}
