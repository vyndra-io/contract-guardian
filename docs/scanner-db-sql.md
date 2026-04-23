# Database SQL Migration Scanner

The SQL scanner parses Flyway and Liquibase raw SQL migration files (`.sql`) using JSqlParser and flags dangerous DDL operations **before they reach production**. Unlike schema registry tools that catch problems at runtime, this scanner catches them at merge time.

---

## Setup

Add a `database` source pointing at your migration directory:

```yaml
# .contract-guardian.yml
version: "1"

sources:
  database:
    paths:
      - "db/migrations/**/*.sql"           # Flyway
      - "src/main/resources/db/**/*.sql"   # Spring Boot convention
    baseline: branch:main

rules:
  database:
    breaking:
      - column-removed
      - table-removed
      - not-null-added-no-default
    warning:
      - column-renamed
      - migration-modified

gate:
  block-on: breaking
```

Contract Guardian discovers the SQL scanner automatically at runtime via `ServiceLoader`.

---

## What Is Checked

The scanner analyzes **every new migration file** added in the PR. If an existing migration file is modified (one that already exists on the baseline branch), it produces a `migration-modified` warning instead of re-analyzing the contents — since that migration may already be applied in production.

### Breaking Changes

| Rule | DDL pattern | Why it breaks |
|---|---|---|
| `column-removed` | `ALTER TABLE t DROP COLUMN c` | Any query, JPA mapping, or application code referencing the column fails at runtime |
| `table-removed` | `DROP TABLE t` | All queries and JPA entities backed by this table fail |
| `not-null-added-no-default` | `ALTER TABLE t ADD COLUMN c TYPE NOT NULL` with no DEFAULT | Existing rows cannot satisfy the constraint; old app versions cannot INSERT |

### Warnings

| Rule | DDL pattern | Why it warrants attention |
|---|---|---|
| `column-renamed` | `ALTER TABLE t RENAME COLUMN old TO new` | Any consumer referencing the old column name breaks; requires coordinated deployment |
| `migration-modified` | File exists in baseline but was changed | Flyway and Liquibase track migrations by checksum; modifying an applied file causes a checksum mismatch error on next startup |

---

## Common Scenarios

### Drop a column — breaking

```sql
-- BREAKING: column-removed
ALTER TABLE orders DROP COLUMN customer_email;
```

```
BREAKING  db/migrations/V25__cleanup.sql
  Column 'orders.customer_email' dropped
  Fix: Deprecate the column first; remove only after all consumers have stopped reading it
```

**Safe approach — two-step removal:**

```sql
-- Step 1 (this PR): stop writing to the column in application code
-- Step 2 (next release): drop the column
ALTER TABLE orders DROP COLUMN customer_email;
```

---

### Add a NOT NULL column without a DEFAULT — breaking

```sql
-- BREAKING: not-null-added-no-default
ALTER TABLE orders ADD COLUMN region VARCHAR(50) NOT NULL;
```

```
BREAKING  db/migrations/V26__add_region.sql
  Column 'orders.region' added as NOT NULL without a DEFAULT
  Fix: Add a DEFAULT value, or follow expand-migrate-contract
```

**Fix 1 — provide a DEFAULT:**

```sql
-- Safe: existing rows get the default value immediately
ALTER TABLE orders ADD COLUMN region VARCHAR(50) NOT NULL DEFAULT 'US';
```

**Fix 2 — expand-migrate-contract pattern (three migrations):**

```sql
-- Migration 1: add nullable column (safe to merge now)
ALTER TABLE orders ADD COLUMN region VARCHAR(50);

-- Migration 2 (separate PR): backfill existing rows
UPDATE orders SET region = 'US' WHERE region IS NULL;

-- Migration 3 (after all consumers are updated): enforce NOT NULL
ALTER TABLE orders ALTER COLUMN region SET NOT NULL;
```

---

### Rename a column — warning

```sql
-- WARNING: column-renamed
ALTER TABLE users RENAME COLUMN email TO email_address;
```

```
WARNING  db/migrations/V27__rename_email.sql
  Column 'users.email' renamed to 'email_address'
  Fix: Add the new column, copy data, update consumers, then drop the old column
```

Renaming a column requires all consumers (JPA entities, native queries, reporting tools) to be updated simultaneously. Use an expand-migrate-contract approach to avoid coordinating big-bang deployments.

---

### Modify a migration file — warning

If a file that already exists on `main` is changed in the PR:

```
WARNING  db/migrations/V10__initial_schema.sql
  Migration file was modified — this file may already be applied to production databases
  Fix: Create a new migration file with the corrected SQL instead
```

---

## Disabling Specific Rules

To disable a rule, remove it from the `breaking` or `warning` list:

```yaml
rules:
  database:
    breaking:
      - column-removed
      - table-removed
      # not-null-added-no-default omitted — not enforced
    warning:
      - column-renamed
      # migration-modified omitted — not enforced
```

---

## Tips

- **Flyway files are immutable once applied.** Never edit `V<n>__<description>.sql` files after they run. If you need to correct a migration, create `V<n+1>__fix_<description>.sql` instead.
- **Liquibase SQL files.** If you use Liquibase with raw SQL changesets, this scanner applies the same rules. For Liquibase XML/YAML/JSON changelogs, see [Liquibase Changelog Scanner](scanner-db-liquibase.md).
- **Repeatable migrations.** Flyway `R__<description>.sql` files are designed to be re-run. If your team uses repeatable migrations, consider removing `migration-modified` from the `warning` list for those paths using a separate source entry.
