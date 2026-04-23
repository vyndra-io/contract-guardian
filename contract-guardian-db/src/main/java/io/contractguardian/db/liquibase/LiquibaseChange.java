package io.contractguardian.db.liquibase;

import java.util.Map;

/**
 * A single change operation extracted from a Liquibase changeset.
 *
 * @param type       the Liquibase change type (e.g. {@code "dropColumn"}, {@code "addColumn"})
 * @param attributes the change attributes; values may be {@link String}, {@link Boolean},
 *                   {@link java.util.List}, or nested {@link Map}
 */
record LiquibaseChange(String type, Map<String, Object> attributes) {
}
