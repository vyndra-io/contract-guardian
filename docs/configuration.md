# Configuration Reference

Contract Guardian is configured through a `.contract-guardian.yml` file in your project root. This file tells it **what to scan**, **what rules to apply**, and **when to fail the build**.

---

## Quick Start

The fastest way to get started is to run:

```bash
contract-guardian init
```

This creates a `.contract-guardian.yml` with sensible defaults. You can also write one by hand — here is the simplest possible config for a project with Kafka Avro schemas:

```yaml
version: "1"
sources:
  kafka:
    paths:
      - "schemas/kafka/**/*.avsc"
    baseline: branch:main
rules:
  kafka:
    compatibility: BACKWARD
gate:
  block-on: breaking
```

---

## File Location

By default, Contract Guardian looks for `.contract-guardian.yml` in the current working directory. To use a different path:

```bash
contract-guardian scan --diff origin/main..HEAD --config path/to/my-config.yml
```

If the file does not exist, Contract Guardian falls back to a built-in default: a `kafka` source scanning `schemas/kafka/**/*.avsc` with `BACKWARD` compatibility.

---

## Top-Level Structure

Every config file has three sections:

```yaml
version: "1"      # required — must be "1"
sources: { ... }  # where to find schema/spec files
rules: { ... }    # how to classify changes (breaking vs warning)
gate: { ... }     # when to fail the build
```

---

## `version`

Currently the only valid value is `"1"`.

```yaml
version: "1"
```

---

## `sources`

Defines named groups of files to scan. Each source has:
- a **name** (you choose it — it must match a key under `rules`)
- a list of **glob patterns** for which files belong to this source
- a **baseline** git ref to compare against

```yaml
sources:
  kafka:                             # name — referenced under rules.kafka
    paths:
      - "schemas/kafka/**/*.avsc"
      - "schemas/kafka/**/*.json"
    baseline: branch:main

  rest:
    paths:
      - "api/openapi/**/*.yaml"
    baseline: branch:main

  database:
    paths:
      - "db/migrations/**/*.sql"
      - "db/changelog/**/*.xml"
      - "src/main/java/**/*Entity.java"
    baseline: branch:main
```

### Glob Pattern Syntax

Patterns are relative to the repository root.

| Pattern | Matches |
|---|---|
| `*` | Any characters except `/` |
| `**` | Zero or more directory levels |
| `?` | One character except `/` |
| `{a,b}` | Either `a` or `b` |

**Examples:**

```yaml
paths:
  - "schemas/**/*.avsc"              # all .avsc files anywhere under schemas/
  - "api/openapi/**/*.yaml"          # all .yaml files under api/openapi/
  - "services/*/contracts/*.json"    # contracts/ inside any service directory
  - "docs/{v1,v2}/openapi.yaml"      # specific paths with alternatives
```

### `baseline`

The git reference to compare changed files against. Currently only `branch:<name>` is supported.

```yaml
baseline: branch:main
baseline: branch:develop
```

---

## `rules`

Controls how compatibility is evaluated for each source. If a source has no entry under `rules`, Contract Guardian uses built-in defaults for that source type.

---

### Kafka Rules

```yaml
rules:
  kafka:
    compatibility: BACKWARD    # BACKWARD | FORWARD | FULL | NONE
    overrides:                 # optional — override per file path
      - topic: "internal/**"
        compatibility: NONE
      - topic: "**/public/**"
        compatibility: FULL
```

#### Compatibility Modes

| Value | What it means |
|---|---|
| `BACKWARD` | New schema can read data written by the old schema. **Default.** Safe to deploy new consumers before old producers drain. |
| `FORWARD` | Old schema can read data written by the new schema. Safe to deploy new producers before consumers update. |
| `FULL` | Both BACKWARD and FORWARD simultaneously. Strictest option for shared platform schemas. |
| `NONE` | No checks at all. Use for scratch or internal-only topics. |

#### Per-File Overrides

Use `overrides` to apply a different compatibility mode to specific file paths. The `topic` value is a glob matched against the file path (relative to the repo root). First match wins.

```yaml
overrides:
  - topic: "internal/**"
    compatibility: NONE        # no enforcement for internal topics
  - topic: "schemas/public/**"
    compatibility: FULL        # strictest for public-facing topics
```

---

### Database Rules

```yaml
rules:
  database:
    breaking:
      - column-removed
      - table-removed
      - not-null-added-no-default
      - jsonb-field-removed
      - jsonb-field-type-changed
      - column-type-changed
    warning:
      - column-renamed
      - jsonb-field-renamed
      - migration-modified
```

Changes listed under `breaking` fail the build (subject to the `gate` setting). Changes under `warning` are reported but do not affect the exit code (unless `gate.block-on: warning`). Identifiers not listed under either are silently ignored.

#### Breaking Rule Identifiers

| Identifier | What triggers it |
|---|---|
| `column-removed` | `DROP COLUMN`, a deleted `@Column` field, or a `dropColumn` Liquibase changeset |
| `table-removed` | `DROP TABLE` or a `dropTable` changeset |
| `not-null-added-no-default` | `NOT NULL` constraint added with no `DEFAULT` or backfill |
| `jsonb-field-removed` | A `@JdbcTypeCode(SqlTypes.JSON)` or `columnDefinition = "jsonb"` field is deleted |
| `jsonb-field-type-changed` | The Java type of a JSONB-mapped field changes |
| `column-type-changed` | `modifyDataType` changeset or `MODIFY` DDL |

#### Warning Rule Identifiers

| Identifier | What triggers it |
|---|---|
| `column-renamed` | `RENAME COLUMN`, changed `@Column(name=...)`, or `renameColumn` changeset |
| `jsonb-field-renamed` | A JSONB-mapped field is renamed |
| `migration-modified` | An existing migration file (already on the baseline branch) is edited |

#### Which Scanner Handles Which File?

The `database` source routes files to different scanners by extension automatically:

| File extension | Scanner used |
|---|---|
| `.sql` | SQL Migration Scanner (Flyway / raw Liquibase SQL) |
| `.xml`, `.yaml`, `.yml`, `.json` | Liquibase Changelog Scanner |
| `.java` | JPA Entity Scanner |

See [SQL Migration Scanner](scanner-db-sql.md), [Liquibase Changelog Scanner](scanner-db-liquibase.md), and [JPA Entity Scanner](scanner-db-jpa.md) for details.

---

### REST Rules

```yaml
rules:
  rest:
    breaking:
      - endpoint-removed
      - required-param-added
      - response-field-removed
      - response-field-type-changed
      - status-code-removed
    warning:
      - response-field-deprecated
      - parameter-renamed
    ignore:
      - "/internal/**"
      - "/admin/**"
```

#### Breaking Rule Identifiers

| Identifier | What triggers it |
|---|---|
| `endpoint-removed` | An endpoint disappears from the spec |
| `required-param-added` | A new required query, path, or header parameter is added |
| `response-field-removed` | A field is removed from a response body |
| `response-field-type-changed` | A response field's type changes |
| `status-code-removed` | A documented response status code is removed |

#### Warning Rule Identifiers

| Identifier | What triggers it |
|---|---|
| `response-field-deprecated` | A response field is marked `deprecated: true` |
| `parameter-renamed` | A query or path parameter is renamed |

#### Ignoring API Paths

Use `ignore` to exclude entire API path prefixes from all checks. Useful for internal, admin, or health-check endpoints that change often and are not part of your public contract.

```yaml
ignore:
  - "/internal/**"       # ignores /internal/config, /internal/health, etc.
  - "/actuator/**"       # Spring Boot actuator endpoints
  - "/v1/admin/**"       # admin paths
```

Pattern syntax uses `/` as the path separator. `**` matches any number of path segments.

---

## `gate`

Controls when the build fails based on finding severity.

```yaml
gate:
  block-on: breaking     # breaking | warning | any
```

| Value | When the build fails |
|---|---|
| `breaking` | At least one BREAKING finding exists. **Default.** |
| `warning` | At least one BREAKING or WARNING finding exists. |
| `any` | Any finding exists (including INFO). |

---

## Full Example

Here is a complete config covering Kafka, REST, and database sources:

```yaml
version: "1"

sources:
  kafka:
    paths:
      - "schemas/kafka/**/*.avsc"
      - "schemas/kafka/**/*.json"
      - "schemas/kafka/**/*.proto"
    baseline: branch:main

  rest:
    paths:
      - "api/openapi/**/*.yaml"
    baseline: branch:main

  database:
    paths:
      - "db/migrations/**/*.sql"
      - "db/changelog/**/*.xml"
      - "db/changelog/**/*.yaml"
      - "src/main/java/**/*Entity.java"
    baseline: branch:main

rules:
  kafka:
    compatibility: BACKWARD
    overrides:
      - topic: "internal/**"
        compatibility: NONE
      - topic: "schemas/public/**"
        compatibility: FULL

  rest:
    breaking:
      - endpoint-removed
      - required-param-added
      - response-field-removed
      - response-field-type-changed
      - status-code-removed
    warning:
      - response-field-deprecated
      - parameter-renamed
    ignore:
      - "/internal/**"
      - "/actuator/**"

  database:
    breaking:
      - column-removed
      - table-removed
      - not-null-added-no-default
      - jsonb-field-removed
      - jsonb-field-type-changed
      - column-type-changed
    warning:
      - column-renamed
      - jsonb-field-renamed
      - migration-modified

gate:
  block-on: breaking
```

---

## Validating Your Config

Run this before committing to catch typos and structural errors:

```bash
contract-guardian validate --config .contract-guardian.yml
```

Output when valid:
```
Config is valid.
```

Output when invalid:
```
Failed to parse config: rules.kafka.compatibility must be one of BACKWARD, FORWARD, FULL, NONE. Found: BACKWARDS
```

---

## Default Values

When `.contract-guardian.yml` is missing or a section is omitted, Contract Guardian falls back to these defaults:

| Section | Default |
|---|---|
| `sources` | `kafka` source at `schemas/kafka/**/*.avsc` |
| `rules.kafka.compatibility` | `BACKWARD` |
| `rules.rest.breaking` | `endpoint-removed`, `required-param-added`, `response-field-removed`, `response-field-type-changed`, `status-code-removed` |
| `rules.rest.warning` | `response-field-deprecated`, `parameter-renamed` |
| `rules.database.breaking` | `column-removed`, `table-removed`, `not-null-added-no-default`, `jsonb-field-removed`, `jsonb-field-type-changed`, `column-type-changed` |
| `rules.database.warning` | `column-renamed`, `jsonb-field-renamed`, `migration-modified` |
| `gate.block-on` | `breaking` |
