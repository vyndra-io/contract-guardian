# Kafka Avro Scanner

The Avro scanner compares `.avsc` schema files between your PR branch and the baseline branch, using the Apache Avro `SchemaCompatibility` API. If a change would break existing producers or consumers, it reports a finding and (by default) fails the build.

---

## Setup

Add the `kafka` source pointing at your Avro schema files:

```yaml
# .contract-guardian.yml
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

That's all. Contract Guardian discovers the Avro scanner automatically at runtime.

---

## Compatibility Modes

Choose the mode that matches how your services are deployed:

| Mode | What it enforces | When to use |
|---|---|---|
| `BACKWARD` (default) | New schema can read data written by the old schema | Deploy new consumers before old producers drain |
| `FORWARD` | Old schema can read data written by the new schema | Deploy new producers before consumers update |
| `FULL` | Both BACKWARD and FORWARD simultaneously | Shared platform schemas where both old producers and old consumers must keep working |
| `NONE` | No checks | Scratch or internal-only topics |

---

## What Is Checked

| Change | BACKWARD | FORWARD | FULL |
|---|---|---|---|
| Add field **with** default | ✅ pass | ✅ pass | ✅ pass |
| Add field **without** default | ❌ breaking | ✅ pass | ❌ breaking |
| Remove field **with** default | ✅ pass | ✅ pass | ✅ pass |
| Remove field **without** default | ✅ pass | ❌ breaking | ❌ breaking |
| Change field type | ❌ breaking | ❌ breaking | ❌ breaking |
| Add enum symbol | ✅ pass | ❌ breaking | ❌ breaking |
| Remove enum symbol | ❌ breaking | ✅ pass | ❌ breaking |
| Expand union (add branch) | ✅ pass | ❌ breaking | ❌ breaking |
| Narrow union (remove branch) | ❌ breaking | ✅ pass | ❌ breaking |

---

## Common Scenarios

### Remove a field — breaking

```json
// Before (baseline on main)
{
  "type": "record",
  "name": "PaymentEvent",
  "fields": [
    {"name": "id",          "type": "string"},
    {"name": "amount",      "type": "double"},
    {"name": "customer_id", "type": "string"}
  ]
}
```

```json
// After (your branch) — BREAKING: customer_id removed
{
  "type": "record",
  "name": "PaymentEvent",
  "fields": [
    {"name": "id",     "type": "string"},
    {"name": "amount", "type": "double"}
  ]
}
```

```
BREAKING  schemas/kafka/avro/payment-value.avsc
  Field "customer_id" removed — breaks backward compatibility
  Fix: Add a default value to the field before removing it
```

**Safe fix — two-step removal:**

```json
// Step 1: add a null default (safe to merge now)
{"name": "customer_id", "type": ["null", "string"], "default": null}

// Step 2: after all consumers are updated, remove the field entirely
```

---

### Add a field without a default — breaking in BACKWARD and FULL

```json
// After — BREAKING: "currency" has no default
{
  "type": "record",
  "name": "PaymentEvent",
  "fields": [
    {"name": "id",       "type": "string"},
    {"name": "amount",   "type": "double"},
    {"name": "currency", "type": "string"}
  ]
}
```

```
BREAKING  schemas/kafka/avro/payment-value.avsc
  New field "currency" has no default — old schema cannot read messages written by new schema
  Fix: Add a default value, e.g. "default": "USD"
```

**Fix:** Always provide a default when adding a field:

```json
{"name": "currency", "type": "string", "default": "USD"}
```

---

### Add an enum symbol — breaking in FORWARD and FULL

```json
// Before
{"name": "status", "type": {"type": "enum", "name": "Status", "symbols": ["PLACED", "SHIPPED"]}}

// After — BREAKING in FORWARD/FULL: "CANCELLED" is new
{"name": "status", "type": {"type": "enum", "name": "Status", "symbols": ["PLACED", "SHIPPED", "CANCELLED"]}}
```

**Fix:** Add an enum `"default"` so Avro falls back to a known symbol when old consumers encounter the new value:

```json
{
  "name": "status",
  "type": {
    "type": "enum",
    "name": "Status",
    "symbols": ["PLACED", "SHIPPED", "CANCELLED"],
    "default": "PLACED"
  }
}
```

---

### Add a field with a default — always safe

```json
// After — PASS in all modes
{
  "type": "record",
  "name": "PaymentEvent",
  "fields": [
    {"name": "id",       "type": "string"},
    {"name": "amount",   "type": "double"},
    {"name": "currency", "type": "string", "default": "USD"}
  ]
}
```

Old messages that don't include `currency` will use the default `"USD"`. No consumers break.

---

## Per-Topic Overrides

Apply a different compatibility mode to specific file paths using `overrides`. The `topic` value is a glob matched against the file path relative to the repo root. First match wins.

```yaml
rules:
  kafka:
    compatibility: BACKWARD     # default for all Avro files
    overrides:
      - topic: "internal/**"
        compatibility: NONE     # internal schemas — no enforcement
      - topic: "**/public/**"
        compatibility: FULL     # public schemas — strictest enforcement
```

---

## Scanning in CI

```bash
contract-guardian scan --diff origin/main..HEAD
```

For GitHub Actions or GitLab CI with PR comments, see [CI Integration](ci-integration.md).

---

## Tips

- **Always add a default before removing a field.** Removing a field that already has a default is safe in all modes.
- **Prefer `["null", "string"]` union for optional fields.** Null-union fields can be safely added and removed.
- **Never change a field's type.** Avro's binary encoding is type-dependent. Changing `int` to `long` is breaking in all modes, even though the values are compatible in most languages. Add a new field with a different name instead.
- **Use `NONE` for scratch topics** that are never persisted or consumed across service boundaries.
