# Architecture

Contract Guardian is a Java CLI tool and Maven plugin that catches breaking API and schema changes **before they merge** into your main branch. This document explains how it is structured internally, how a scan flows from start to finish, and how to extend it with a custom scanner.

---

## Module Layout

The project is a Maven multi-module build. Each module has a single responsibility:

```
contract-guardian/
├── contract-guardian-core/          # Shared interfaces, data model, policy engine, reporters
├── contract-guardian-kafka/         # Avro, JSON Schema, and Protobuf scanners
├── contract-guardian-rest/          # OpenAPI diff scanner
├── contract-guardian-db/            # SQL migration, JPA entity, and Liquibase scanners
├── contract-guardian-cli/           # CLI entry point (scan, init, validate commands)
├── contract-guardian-github/        # Posts results as a GitHub PR comment
├── contract-guardian-gitlab/        # Posts results as a GitLab MR note
├── contract-guardian-maven-plugin/  # Maven plugin (validate and generate-spec goals)
└── examples/
    ├── kafka-avro/                  # Minimal Kafka Avro example
    ├── multi-contract/              # All contract types — breaking and safe scenarios
    └── quarkus-kafka-avro/          # Quarkus microservice example
```

**All scanner logic lives in `kafka`, `rest`, and `db` modules.** The `core` module only defines the interfaces they implement — it has no scanner code itself.

---

## How a Scan Works

Here is what happens when you run `contract-guardian scan --diff origin/main..HEAD`:

```
CLI (scan command)
    │
    ├── DiffAnalyzer           — asks git which files changed, then classifies each
    │                            file by contract type (Avro, OpenAPI, SQL, etc.)
    │
    ├── ScannerRegistry        — finds all ContractScanner implementations on the classpath
    │   └── ServiceLoader      — each module registers its scanners via META-INF/services
    │
    ├── ContractScanner(s)     — one scanner runs per changed file
    │   └── returns ScanResult — zero or more Findings describing what changed
    │
    ├── PolicyEngine           — reads .contract-guardian.yml and evaluates all ScanResults
    │   └── produces Verdict   — PASS / WARN / FAIL
    │
    └── Reporter(s)            — formats the Verdict and sends it somewhere
        ├── TerminalReporter   — colored output to the console
        ├── JUnitXmlReporter   — XML file for CI dashboards
        ├── GitHubReporter     — comment on a GitHub PR
        └── GitLabReporter     — note on a GitLab MR
```

**Example:** You change `schemas/kafka/payment-value.avsc`. The `DiffAnalyzer` sees the `.avsc` extension and classifies it as `KAFKA_AVRO`. The `ScannerRegistry` finds `KafkaAvroScanner`, which compares the current and baseline versions of the file. It produces a `Finding` for the removed field. The `PolicyEngine` checks your config — `block-on: breaking` — and emits a `FAIL` verdict. The `TerminalReporter` prints the result; the `GitHubReporter` posts it to the PR.

---

## Core Data Model

All shared types live in `contract-guardian-core`. These are the objects that scanners produce and reporters consume.

```java
/** Which kind of contract was scanned. */
public enum ContractType {
    KAFKA_AVRO, KAFKA_JSON_SCHEMA, KAFKA_PROTOBUF,
    REST_OPENAPI, REST_GRPC,
    DB_MIGRATION, DB_JPA_ENTITY, DB_JSONB
}

/** Severity of a single finding. */
public enum Severity {
    BREAKING,   // will cause runtime failures for existing consumers
    WARNING,    // may cause issues — needs review
    INFO        // informational only — no action required
}

/** Final verdict status after policy evaluation. */
public enum VerdictStatus { PASS, WARN, FAIL }

/** A single incompatibility found by a scanner. */
public record Finding(
    ContractType contractType,
    Severity severity,
    String file,
    int line,           // -1 when not applicable
    String rule,        // e.g. "field-removed", "endpoint-removed"
    String message,     // human-readable description
    String detail,      // additional context
    @Nullable String fix
) {}

/** The result of scanning one file. */
public record ScanResult(
    String file,
    ContractType contractType,
    List<Finding> findings,
    Duration scanDuration
) {}

/** The final verdict after the policy engine runs. */
public record Verdict(
    VerdictStatus status,
    List<ScanResult> results,
    Duration totalDuration
) {}
```

---

## Scanner Interface

Every scanner implements one interface. The key method is `scan(current, baseline, config)` — it receives both versions of a file and returns a list of findings.

```java
/**
 * Scans a changed contract file against its baseline version.
 */
public interface ContractScanner {

    /** The contract types this scanner handles. */
    Set<ContractType> supportedTypes();

    /** Returns true if this scanner should handle the given file. */
    boolean canScan(Path file, SourceConfig sourceConfig);

    /**
     * Compares the current file against its baseline and returns findings.
     *
     * @param current   the file as it exists in the PR or MR branch
     * @param baseline  the file as it exists on the base branch;
     *                  null if the file is newly added
     * @param config    scanner-specific rules from the policy file
     */
    ScanResult scan(Path current, @Nullable Path baseline, RuleConfig config);
}
```

Scanners are discovered at runtime via `java.util.ServiceLoader`. Each module registers its scanners in:

```
META-INF/services/io.contractguardian.scanner.ContractScanner
```

No other wiring is needed — if the JAR is on the classpath, its scanners are automatically picked up.

---

## Scanner Implementations

### Kafka Avro (`contract-guardian-kafka`)

Uses the Apache Avro `SchemaCompatibility` API. Supports `BACKWARD`, `FORWARD`, `FULL`, and `NONE` compatibility modes.

**Breaking changes detected:**
- Field removed without a default value
- Field type changed to an incompatible type
- Enum symbol removed
- Union type narrowed (branch removed)

### Kafka JSON Schema (`contract-guardian-kafka`)

Computes a structural diff between old and new JSON Schema files using Jackson.

| Change | Verdict |
|---|---|
| Required property added | BREAKING |
| Property removed | BREAKING |
| Property type changed | BREAKING |
| Constraint tightened (`minLength`, `maximum`, etc.) | BREAKING |
| Optional property added | COMPATIBLE |
| Constraint relaxed | COMPATIBLE |

### Kafka Protobuf (`contract-guardian-kafka`)

Uses the Square Wire schema parser. Protobuf is tag-based — field names don't matter on the wire, tag numbers do.

**Breaking changes detected:**
- Field removed without a `reserved` declaration
- Field type changed at the same tag number
- Tag number reused for a different field
- Enum value removed

### REST OpenAPI (`contract-guardian-rest`)

Uses the `openapi-diff` library to compare OpenAPI 3.x specs.

**Breaking changes detected:**
- Endpoint removed
- Required request parameter added
- Request/response field type changed
- Response field removed
- HTTP status code removed

### Database SQL (`contract-guardian-db`)

Parses SQL DDL statements with JSqlParser (works with Flyway and raw SQL migrations).

**Breaking changes detected:**
- `DROP COLUMN`
- `ALTER COLUMN` type change
- `NOT NULL` added without a `DEFAULT`
- `DROP TABLE`

### Database JPA (`contract-guardian-db`)

Parses Java source files with JavaParser and compares `@Column`, `@JdbcTypeCode`, and related annotations.

**Breaking changes detected:**
- `@Column` mapping removed (field deleted from entity)
- Column name changed via `@Column(name = ...)`
- JSONB-mapped field removed or type changed

### Database Liquibase (`contract-guardian-db`)

Parses XML, YAML, and JSON Liquibase changelog files and diffs changesets.

**Breaking changes detected:**
- `dropColumn` changeset added
- `dropTable` changeset added
- `addNotNullConstraint` without a `defaultValue`

---

## Policy Engine

The policy engine reads `.contract-guardian.yml`, evaluates all `ScanResult` objects against the configured rules, and produces a `Verdict`.

The `gate` setting controls which severity level blocks the merge:

```yaml
gate:
  block-on: breaking   # breaking | warning | any
```

Rules can be overridden per-file or per-topic using `overrides`. See [Configuration Reference](configuration.md) for all options.

---

## Reporters

Multiple reporters can run in a single scan. All implement `Reporter` in `contract-guardian-core`.

| Reporter | How to enable |
|---|---|
| Terminal (colored output) | `--reporter terminal` (default) |
| JUnit XML | `--reporter junit:<path>` |
| GitHub PR comment | `--github-pr owner/repo#123` |
| GitLab MR note | `--gitlab-mr project/path!123` |

See [Reporters](reporters.md) for configuration details.

---

## Writing a Custom Scanner

1. Create a class that implements `ContractScanner`.
2. Register it in `META-INF/services/io.contractguardian.scanner.ContractScanner`.
3. Add the JAR to the classpath when running `contract-guardian`.

The `ScannerRegistry` picks it up automatically via `ServiceLoader`. No other changes are needed.

---

## Tech Stack

| Component | Library | Version |
|---|---|---|
| Language | Java | 17+ |
| Build | Maven (multi-module) | — |
| CLI | Picocli | 4.7.6 |
| Avro compatibility | Apache Avro | 1.11.3 |
| Protobuf compatibility | Square Wire | 4.9.9 |
| OpenAPI diff | openapi-diff | 2.1.0-beta.11 |
| SQL parsing | JSqlParser | 4.9 |
| Java source parsing | JavaParser | 3.25.10 |
| YAML config | SnakeYAML | 2.2 |
| JSON | Jackson | 2.17.2 |
| Logging | SLF4J | 2.0.13 |
| Testing | JUnit 5, Mockito, AssertJ | — |
