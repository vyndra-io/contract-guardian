package io.contractguardian.policy;

import java.util.List;

/**
 * Rule configuration for database schema compatibility checks.
 *
 * <p>Defines which DDL and JPA changes are breaking or warnings,
 * and controls additional enforcement like expand-migrate-contract.
 */
public class DbRuleConfig extends RuleConfig {

    /**
     * Default breaking change rules for database schemas.
     *
     * <ul>
     *   <li>{@code column-removed} — DDL/JPA: column dropped from a table or entity</li>
     *   <li>{@code table-removed} — DDL: table dropped</li>
     *   <li>{@code not-null-added-no-default} — DDL/JPA: NOT NULL constraint added without a default</li>
     *   <li>{@code jsonb-field-removed} — JSONB value class: field removed from the Java type</li>
     *   <li>{@code jsonb-field-type-changed} — JSONB value class: field type changed incompatibly</li>
     *   <li>{@code column-type-changed} — DDL: column data type changed</li>
     * </ul>
     */
    private static final List<String> DEFAULT_BREAKING = List.of(
            "column-removed",
            "table-removed",
            "not-null-added-no-default",
            "jsonb-field-removed",
            "jsonb-field-type-changed",
            "column-type-changed"
    );

    /**
     * Default warning rules for database schemas.
     *
     * <ul>
     *   <li>{@code column-renamed} — DDL/JPA: column renamed (breaks queries using the old name)</li>
     *   <li>{@code jsonb-field-renamed} — JSONB: field renamed in the value class</li>
     *   <li>{@code migration-modified} — SQL: an existing migration file was edited after creation</li>
     *   <li>{@code jsonb-schema-unenforceable} — JSONB: column mapped as {@code Map<>};
     *       schema is dynamic and cannot be statically verified</li>
     * </ul>
     */
    private static final List<String> DEFAULT_WARNING = List.of(
            "column-renamed",
            "jsonb-field-renamed",
            "migration-modified",
            "jsonb-schema-unenforceable"
    );

    private final List<String> breaking;
    private final List<String> warning;
    private final boolean requireExpandMigrateContract;
    private final int nVersionCompatibility;

    /**
     * Creates a database rule configuration.
     *
     * @param breaking                     change types classified as breaking
     * @param warning                      change types classified as warnings
     * @param requireExpandMigrateContract whether to enforce the expand-migrate-contract pattern
     * @param nVersionCompatibility        number of previous versions that must remain compatible
     */
    public DbRuleConfig(final List<String> breaking,
                        final List<String> warning,
                        final boolean requireExpandMigrateContract,
                        final int nVersionCompatibility) {
        super("database");
        this.breaking = List.copyOf(breaking);
        this.warning = List.copyOf(warning);
        this.requireExpandMigrateContract = requireExpandMigrateContract;
        this.nVersionCompatibility = nVersionCompatibility;
    }

    /**
     * Returns the change types classified as breaking.
     *
     * @return an unmodifiable list of breaking change type identifiers
     */
    public List<String> breaking() {
        return breaking;
    }

    /**
     * Returns the change types classified as warnings.
     *
     * @return an unmodifiable list of warning change type identifiers
     */
    public List<String> warning() {
        return warning;
    }

    /**
     * Returns whether the expand-migrate-contract pattern is required.
     *
     * @return {@code true} if the pattern is enforced
     */
    public boolean requireExpandMigrateContract() {
        return requireExpandMigrateContract;
    }

    /**
     * Returns the number of previous schema versions that must remain compatible.
     *
     * @return the n-version compatibility number
     */
    public int nVersionCompatibility() {
        return nVersionCompatibility;
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
     * Returns a default database rule configuration with standard breaking and warning rules.
     *
     * @return the default config
     */
    public static DbRuleConfig defaultConfig() {
        return new DbRuleConfig(DEFAULT_BREAKING, DEFAULT_WARNING, false, 1);
    }
}
