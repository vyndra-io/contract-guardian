package io.contractguardian.policy;

import java.util.List;

/**
 * Rule configuration for REST API compatibility checks.
 *
 * <p>Defines which OpenAPI changes are considered breaking, which are warnings,
 * and which API paths should be ignored during scanning.
 */
public class RestRuleConfig extends RuleConfig {

    /** Default breaking change rules for REST APIs. */
    private static final List<String> DEFAULT_BREAKING = List.of(
            "endpoint-removed",
            "required-param-added",
            "response-field-removed",
            "response-field-type-changed",
            "status-code-removed"
    );

    /** Default warning rules for REST APIs. */
    private static final List<String> DEFAULT_WARNING = List.of(
            "response-field-deprecated",
            "parameter-renamed"
    );

    private final List<String> breaking;
    private final List<String> warning;
    private final List<String> ignorePaths;

    /**
     * Creates a REST rule configuration.
     *
     * @param breaking    change types classified as breaking
     * @param warning     change types classified as warnings
     * @param ignorePaths API path patterns to exclude from scanning
     */
    public RestRuleConfig(final List<String> breaking,
                          final List<String> warning,
                          final List<String> ignorePaths) {
        super("rest");
        this.breaking = List.copyOf(breaking);
        this.warning = List.copyOf(warning);
        this.ignorePaths = List.copyOf(ignorePaths);
    }

    /**
     * Returns the list of change types classified as breaking.
     *
     * @return an unmodifiable list of breaking change type identifiers
     */
    public List<String> breaking() {
        return breaking;
    }

    /**
     * Returns the list of change types classified as warnings.
     *
     * @return an unmodifiable list of warning change type identifiers
     */
    public List<String> warning() {
        return warning;
    }

    /**
     * Returns the API path patterns to exclude from scanning.
     *
     * @return an unmodifiable list of ignore path patterns
     */
    public List<String> ignorePaths() {
        return ignorePaths;
    }

    /**
     * Checks whether the given change type is classified as breaking.
     *
     * @param changeType the change type identifier to check
     * @return {@code true} if the change type is breaking
     */
    public boolean isBreaking(final String changeType) {
        return breaking.contains(changeType);
    }

    /**
     * Checks whether the given change type is classified as a warning.
     *
     * @param changeType the change type identifier to check
     * @return {@code true} if the change type is a warning
     */
    public boolean isWarning(final String changeType) {
        return warning.contains(changeType);
    }

    /**
     * Checks whether the given API path should be ignored during scanning.
     *
     * @param apiPath the API path to check
     * @return {@code true} if the path matches any ignore pattern
     */
    public boolean isIgnored(final String apiPath) {
        for (final String pattern : ignorePaths) {
            if (matchesPathPattern(apiPath, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a default REST rule configuration with standard breaking and warning rules.
     *
     * @return the default config
     */
    public static RestRuleConfig defaultConfig() {
        return new RestRuleConfig(DEFAULT_BREAKING, DEFAULT_WARNING, List.of());
    }

    private boolean matchesPathPattern(final String apiPath, final String pattern) {
        // Escape regex metacharacters, then restore glob wildcards
        final String escaped = pattern
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace(".", "\\.");
        final String regex = escaped
                .replace("/**", "/.*")
                .replace("/*", "/[^/]*");
        return apiPath.matches(regex);
    }
}
