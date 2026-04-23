# Database JPA / JSONB Value Class Scanner

The JPA scanner uses JavaParser to parse `.java` source files and compares field-level metadata between the current branch and the baseline. It catches the class of breakage that SQL migration scanners miss: a developer silently changes a JPA mapping or a JSONB value class without writing a migration file.

It handles two kinds of Java files:

- **JPA entities** (`@Entity`) — compares `@Column`, `@JdbcTypeCode`, and related annotations to detect column removals, constraint changes, renames, and JSONB-mapped fields.
- **JSONB value classes** (no `@Entity`) — plain Java classes whose instances are serialized into a `jsonb` column. Compares fields by name and type to detect removals, type changes, and `Map<>`-typed fields.

---

## Setup

Add a `database` source that includes your entity and JSONB value class paths:

```yaml
# .contract-guardian.yml
version: "1"

sources:
  database:
    paths:
      - "src/main/java/**/*Entity.java"
      - "src/main/java/**/entity/**/*.java"
      - "src/main/java/**/model/jsonb/**/*.java"   # JSONB value classes
    baseline: branch:main

rules:
  database:
    breaking:
      - column-removed
      - not-null-added-no-default
      - jsonb-field-removed
      - jsonb-field-type-changed
    warning:
      - column-renamed
      - jsonb-schema-unenforceable

gate:
  block-on: breaking
```

Files containing `@Entity` are analyzed as JPA entities. All other `.java` files matched by the glob are analyzed as JSONB value classes.

---

## What Is Checked

### JPA Entities — Breaking Changes

| Rule | What changed | Why it breaks |
|---|---|---|
| `column-removed` | A `@Column`-mapped field was deleted | The column still exists in the database; the application can no longer write to it and any native queries referencing it fail |
| `not-null-added-no-default` | `@Column(nullable = false)` added to a previously nullable field | Existing rows with NULL violate the new constraint; old app versions cannot INSERT without supplying a value |
| `jsonb-field-removed` | A `@JdbcTypeCode(SqlTypes.JSON)` or `columnDefinition = "jsonb"` field was deleted | Consumers reading the JSONB structure will find the field missing |
| `jsonb-field-type-changed` | The Java type of a JSONB-mapped field changed | The serialized JSON shape changes, breaking consumers that read the old structure |

### JPA Entities — Warnings

| Rule | What changed | Why it warrants attention |
|---|---|---|
| `column-renamed` | `@Column(name = "...")` changed | Native queries, reporting tools, or non-JPA consumers referencing the old column name will break |
| `jsonb-schema-unenforceable` | A JSONB-mapped field uses `Map<>` as its type | Schema is fully dynamic; breaking key changes are invisible to static analysis |

### JSONB Value Classes — Breaking Changes

| Rule | What changed | Why it breaks |
|---|---|---|
| `jsonb-field-removed` | A field was removed from the value class | All stored documents and consumers reading that field receive `null` or fail to deserialize |
| `jsonb-field-type-changed` | A field's Java type changed (e.g. `String` → `Integer`) | Stored documents serialized with the old type cannot be deserialized with the new type |

### JSONB Value Classes — Warnings

| Rule | What changed | Why it warrants attention |
|---|---|---|
| `jsonb-schema-unenforceable` | A field uses `Map<>` as its type | Schema for that field is dynamic; breaking key changes cannot be detected statically |

---

## How Column Names Are Resolved

When `@Column(name = "...")` is explicitly set, that value is used. When absent, the scanner derives the column name from the field name using JPA's default naming strategy (camelCase → snake_case):

| Java field | Resolved column name |
|---|---|
| `customerEmail` | `customer_email` |
| `createdAt` | `created_at` |
| `@Column(name = "cust_email") email` | `cust_email` |

---

## Common Scenarios

### Remove a mapped field from a JPA entity — breaking

```java
// Baseline (on main)
@Entity
public class OrderEntity {

    @Column(name = "customer_email")
    private String email;

    @Column
    private String notes;
}
```

```java
// Current branch — BREAKING: email field removed
@Entity
public class OrderEntity {

    @Column
    private String notes;
}
```

```
BREAKING  src/main/java/com/example/order/OrderEntity.java
  Field 'OrderEntity.email' removed — mapped column 'customer_email' will no longer be read or written
  Fix: Deprecate the field first; remove after all consumers have been updated
```

---

### Add a NOT NULL constraint without a default — breaking

```java
// Baseline
@Column(nullable = true)
private String currency;
```

```java
// Current branch — BREAKING: nullable changed to false
@Column(nullable = false)
private String currency;
```

```
BREAKING  src/main/java/com/example/payment/PaymentEntity.java
  Field 'PaymentEntity.currency' changed to nullable=false on column 'currency'
  Fix: Add a @ColumnDefault or supply a DEFAULT in a migration before enforcing NOT NULL
```

**Safe fix — expand-migrate-contract:**

```java
// Step 1: keep nullable, add application-level validation only
@Column(nullable = true)
private String currency;

// Step 2 (separate migration): backfill NULL rows
// UPDATE payments SET currency = 'USD' WHERE currency IS NULL;

// Step 3 (separate migration): enforce NOT NULL at the database level
// ALTER TABLE payments ALTER COLUMN currency SET NOT NULL;

// Step 4: update the entity
@Column(nullable = false)
private String currency;
```

---

### Remove a field from a JSONB value class — breaking

```java
// Baseline
public class UserPreferences {
    private String theme;
    private boolean darkMode;
    private String language;
}
```

```java
// Current branch — BREAKING: "theme" removed
public class UserPreferences {
    private boolean darkMode;
    private String language;
}
```

```
BREAKING  src/main/java/com/example/model/jsonb/UserPreferences.java
  JSONB value class 'UserPreferences.theme' removed — consumers reading this field will receive null or fail to deserialize
  Fix: Follow expand-migrate-contract; ensure all consumers have stopped reading this field before removing it
```

---

### Change a field type in a JSONB value class — breaking

```java
// Baseline
public class UserPreferences {
    private String theme;   // was String
}

// Current branch — BREAKING: type changed from String to Integer
public class UserPreferences {
    private Integer theme;
}
```

```
BREAKING  src/main/java/com/example/model/jsonb/UserPreferences.java
  JSONB value class 'UserPreferences.theme' type changed from 'String' to 'Integer'
  Fix: Add a new field with the new type, migrate stored data, then remove the old field
```

---

### JSONB field using Map<> — warning

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb")
private Map<String, Object> metadata;
```

```
WARNING  src/main/java/com/example/order/OrderEntity.java
  JSONB field 'OrderEntity.metadata' is typed as Map<> — schema is dynamic and cannot be statically verified
  Fix: Consider replacing Map<> with a typed value class so field-level changes can be detected
```

`Map<>`-typed JSONB fields have no fixed schema. Contract Guardian cannot detect breaking key changes inside them. The warning surfaces the gap so teams are aware the column is unguarded.

---

## Annotations Recognized

| Annotation | Behavior |
|---|---|
| `@Entity` | Marks the class for entity analysis. Files without `@Entity` are analyzed as JSONB value classes. |
| `@Column` | Declares the field as database-mapped. Attributes `name` and `nullable` are read. |
| `@Column(nullable = false)` | Field is treated as NOT NULL. |
| `@JdbcTypeCode(SqlTypes.JSON)` | Field is treated as a JSONB column (Hibernate 6+). |
| `@Column(columnDefinition = "jsonb")` | Field is treated as a JSONB column (pre-Hibernate 6 style). |

---

## Tips

- **This scanner is not a substitute for migration files.** It detects risky annotation changes but cannot verify that a corresponding migration has been written. Always pair JPA entity changes with a migration.
- **New files are not flagged.** When the file has no baseline (new file), a single INFO finding is produced and the scan passes.
- **`Map<>` fields emit a warning on every scan.** This is intentional — the warning reminds the team that the field is unguarded. Suppress it by removing `jsonb-schema-unenforceable` from the `warning` list once acknowledged.
- **Only top-level fields are compared.** Nested value classes (e.g. an `Address` field inside `UserPreferences`) are not recursively checked. Track them by adding their path to the `database` source as well.
- **Inner classes with `@Entity` are supported.** Each `@Entity` class in the file is analyzed independently against its counterpart in the baseline.
