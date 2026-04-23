package io.contractguardian.db.liquibase;

import java.util.List;

/**
 * A parsed Liquibase {@code <changeSet>} with its constituent change operations.
 *
 * @param id      the changeset id attribute
 * @param author  the changeset author attribute
 * @param changes the ordered list of change operations within this changeset
 */
record LiquibaseChangeset(String id, String author, List<LiquibaseChange> changes) {

    /**
     * Returns a stable key combining id and author, used to detect new vs existing changesets.
     *
     * @return the composite key
     */
    String key() {
        return id + "::" + author;
    }
}
