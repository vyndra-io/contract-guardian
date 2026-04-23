# Database Liquibase Changelog Scanner

The Liquibase scanner parses changelog files in XML, YAML, and JSON formats and flags breaking change operations **before the PR is merged**. Only changesets that are **new in the PR** are analyzed — pre-existing changesets already on the baseline branch are skipped automatically.

---

## Setup

Add a `database` source pointing at your Liquibase changelogs:

```yaml
# .contract-guardian.yml
version: "1"

sources:
  database:
    paths:
      - "db/changelog/**/*.xml"
      - "db/changelog/**/*.yaml"
      - "db/changelog/**/*.json"
      - "src/main/resources/db/changelog/**/*.xml"
    baseline: branch:main

rules:
  database:
    breaking:
      - column-removed
      - table-removed
      - not-null-added-no-default
      - column-type-changed
    warning:
      - column-renamed

gate:
  block-on: breaking
```

Contract Guardian discovers the Liquibase scanner automatically at runtime via `ServiceLoader`.

---

## Supported Formats

All three Liquibase changelog formats are supported. The same breaking change detection applies to all of them.

### XML

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog>
    <changeSet id="42" author="vj">
        <dropColumn tableName="orders" columnName="customer_email"/>
    </changeSet>
</databaseChangeLog>
```

### YAML

```yaml
databaseChangeLog:
  - changeSet:
      id: "42"
      author: vj
      changes:
        - dropColumn:
            tableName: orders
            columnName: customer_email
```

### JSON

```json
{
  "databaseChangeLog": [
    {
      "changeSet": {
        "id": "42",
        "author": "vj",
        "changes": [
          {
            "dropColumn": {
              "tableName": "orders",
              "columnName": "customer_email"
            }
          }
        ]
      }
    }
  ]
}
```

---

## What Is Checked

### Breaking Changes

| Rule | Liquibase change type | Why it breaks |
|---|---|---|
| `column-removed` | `dropColumn` | Any query, JPA mapping, or application code referencing the column fails at runtime |
| `table-removed` | `dropTable` | All queries and JPA entities backed by this table fail |
| `not-null-added-no-default` | `addColumn` with `nullable=false` and no `defaultValue` | Existing rows cannot satisfy the constraint; old app versions cannot INSERT |
| `not-null-added-no-default` | `addNotNullConstraint` without `defaultNullValue` | Liquibase cannot backfill existing NULL rows |
| `column-type-changed` | `modifyDataType` | Type changes can truncate data or break JDBC type mappings |

### Warnings

| Rule | Liquibase change type | Why it warrants attention |
|---|---|---|
| `column-renamed` | `renameColumn` | Consumers using the old column name break; requires coordinated deployment |

---

## Common Scenarios

### Drop a column — breaking

**XML:**
```xml
<changeSet id="42" author="vj">
    <dropColumn tableName="orders" columnName="customer_email"/>
</changeSet>
```

```
BREAKING  db/changelog/V42__cleanup.xml
  Changeset '42': dropColumn 'orders.customer_email'
  Fix: Deprecate first; remove only after all consumers have stopped referencing the column
```

---

### Add a NOT NULL column without a default — breaking

**XML:**
```xml
<changeSet id="43" author="vj">
    <addColumn tableName="orders">
        <column name="region" type="VARCHAR(50)">
            <constraints nullable="false"/>
        </column>
    </addColumn>
</changeSet>
```

**YAML equivalent:**
```yaml
- changeSet:
    id: "43"
    author: vj
    changes:
      - addColumn:
          tableName: orders
          columns:
            - column:
                name: region
                type: VARCHAR(50)
                constraints:
                  nullable: false
```

```
BREAKING  db/changelog/V43__add_region.xml
  Changeset '43': column 'orders.region' is NOT NULL with no defaultValue
  Fix: Add a defaultValue, or follow expand-migrate-contract
```

**Fix — add a `defaultValue`:**

```xml
<column name="region" type="VARCHAR(50)" defaultValue="US">
    <constraints nullable="false"/>
</column>
```

---

### Add a NOT NULL constraint to an existing column — breaking

```xml
<changeSet id="44" author="vj">
    <addNotNullConstraint tableName="orders" columnName="status"
                          columnDataType="VARCHAR(50)"/>
</changeSet>
```

```
BREAKING  db/changelog/V44__add_constraint.xml
  Changeset '44': column 'orders.status' is NOT NULL with no defaultValue
  Fix: Supply a defaultNullValue to backfill existing NULL rows before adding the constraint
```

**Fix:**

```xml
<addNotNullConstraint tableName="orders" columnName="status"
                      columnDataType="VARCHAR(50)"
                      defaultNullValue="UNKNOWN"/>
```

---

### Rename a column — warning

```xml
<changeSet id="45" author="vj">
    <renameColumn tableName="users"
                  oldColumnName="email"
                  newColumnName="email_address"
                  columnDataType="VARCHAR(255)"/>
</changeSet>
```

```
WARNING  db/changelog/V45__rename_email.xml
  Changeset '45': renameColumn 'users.email' → 'email_address'
  Fix: Add the new column, copy data, update consumers, then drop the old column
```

---

### Modify a column data type — breaking

```xml
<changeSet id="46" author="vj">
    <modifyDataType tableName="orders" columnName="amount"
                    newDataType="DECIMAL(10,2)"/>
</changeSet>
```

```
BREAKING  db/changelog/V46__change_amount_type.xml
  Changeset '46': modifyDataType 'orders.amount' to DECIMAL(10,2)
  Fix: Verify all consumers handle the new type; consider adding a new column and migrating data
```

---

## How New Changesets Are Identified

Changesets are matched by their composite `id::author` key. When the current changelog has a changeset that's not in the baseline version of the same file, it is considered new and analyzed. Changesets present in both versions are skipped.

**Example — adding changeset 11 to an existing changelog:**

```
Baseline (on main):              Current branch (PR adds changeset 11):
  changeSet id="10" author="vj"    changeSet id="10" author="vj"  ← skipped
                                    changeSet id="11" author="vj"  ← analyzed
```

This correctly handles the most common Liquibase pattern: appending changesets to a shared `db.changelog-master.xml` file.

---

## Mixed Sources

If you use both raw Flyway SQL and Liquibase changelogs in the same repository, configure them under the same `database` source:

```yaml
sources:
  database:
    paths:
      - "db/flyway/**/*.sql"
      - "db/liquibase/**/*.xml"
      - "db/liquibase/**/*.yaml"
    baseline: branch:main
```

The SQL Migration Scanner handles `.sql` files and the Liquibase scanner handles `.xml/.yaml/.json` files. Both run automatically.

---

## Tips

- **Do not modify existing changesets.** Liquibase tracks applied changesets by checksum. Changing a changeset that has already been applied causes a `ChangeSetCheckSumException` on the next startup. Create a new changeset instead.
- **Context and labels.** Contract Guardian scans all changesets regardless of context or label. If certain changesets are intentionally breaking for non-production environments, remove the relevant rules from the `breaking` list and document the exception.
- **Master changelogs with `<include>`** are not followed across files. Only the file that changed in the PR is scanned. Included files that are themselves changed in the PR are scanned separately.
