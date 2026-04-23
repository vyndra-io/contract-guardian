package io.contractguardian.policy;

import java.util.List;

/**
 * Configuration for a contract source defined in the policy file.
 *
 * <p>A source maps a set of file glob patterns to a baseline reference
 * for comparison during scanning.
 */
public class SourceConfig {

    private final String name;
    private final List<String> paths;
    private final String baseline;

    /**
     * Creates a source configuration.
     *
     * @param name     the source name (e.g. "kafka", "rest")
     * @param paths    glob patterns identifying files belonging to this source
     * @param baseline the git baseline reference (e.g. "branch:main")
     */
    public SourceConfig(final String name, final List<String> paths, final String baseline) {
        this.name = name;
        this.paths = List.copyOf(paths);
        this.baseline = baseline;
    }

    /**
     * Returns the source name.
     *
     * @return the source name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the file glob patterns for this source.
     *
     * @return an unmodifiable list of glob patterns
     */
    public List<String> paths() {
        return paths;
    }

    /**
     * Returns the git baseline reference for comparison.
     *
     * @return the baseline reference string
     */
    public String baseline() {
        return baseline;
    }
}
