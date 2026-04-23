# Kafka JSON Schema Scanner

The JSON Schema scanner compares `.json` files that contain a valid JSON Schema. It uses a structural Jackson diff to detect changes between the current branch and the baseline, then classifies each change as breaking or safe based on the configured compatibility mode.

A file is treated as a JSON Schema if it contains a `$schema` keyword, or a top-level `type` field combined with a `properties` field.

---

## Setup

```yaml
# .contract-guardian.yml
version: "1"

sources:
  kafka:
    paths:
      - "schemas/kafka/**/*.json"
    baseline: branch:main

rules:
  kafka:
    compatibility: BACKWARD

gate:
  block-on: breaking
```

If you also have Avro or Protobuf schemas, list all patterns under the same `kafka` source:

```yaml
sources:
  kafka:
    paths:
      - "schemas/kafka/**/*.avsc"
      - "schemas/kafka/**/*.json"
      - "schemas/kafka/**/*.proto"
    baseline: branch:main
```

---

## Compatibility Modes

| Mode | What it enforces |
|---|---|
| `BACKWARD` (default) | New schema can read messages produced by the old schema. Safe to deploy new consumers before old producers drain. |
| `FORWARD` | Old schema can read messages produced by the new schema. Safe to deploy new producers while old consumers are still running. |
| `FULL` | Both BACKWARD and FORWARD simultaneously. |
| `NONE` | No checks. Findings are never produced for this file. |

---

## What Is Checked

| Change | BACKWARD | FORWARD | FULL |
|---|---|---|---|
| Add optional property | ✅ pass | ✅ pass | ✅ pass |
| Add required property | ✅ pass | ❌ breaking | ❌ breaking |
| Remove property | ❌ breaking | ✅ pass | ❌ breaking |
| Change property type | ❌ breaking | ❌ breaking | ❌ breaking |
| Remove enum value | ❌ breaking | ✅ pass | ❌ breaking |
| Add enum value | ✅ pass | ❌ breaking | ❌ breaking |

---

## Common Scenarios

### Remove a property — breaking in BACKWARD and FULL

```json
// Before (baseline on main)
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "user_id":  {"type": "string"},
    "theme":    {"type": "string"},
    "language": {"type": "string"}
  },
  "required": ["user_id"]
}
```

```json
// After (your branch) — BREAKING: "language" removed
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "user_id": {"type": "string"},
    "theme":   {"type": "string"}
  },
  "required": ["user_id"]
}
```

```
BREAKING  schemas/kafka/json/user-preferences.json
  Property "language" removed — breaks backward compatibility
  Fix: Do not remove properties that existing consumers may be reading
```

**Fix:** Mark the property as deprecated in a `description` field first. Coordinate with consumers before removing it.

---

### Add a required property — breaking in FORWARD and FULL

```json
// After — BREAKING in FORWARD mode: "region" added to required
{
  "properties": {
    "user_id": {"type": "string"},
    "theme":   {"type": "string"},
    "region":  {"type": "string"}
  },
  "required": ["user_id", "region"]
}
```

```
BREAKING  schemas/kafka/json/user-preferences.json
  Required property "region" added — old producers do not know about this field
  Fix: Add the field as optional (remove from required array) until all producers are updated
```

**Safe fix — two-step approach:**

```json
// Step 1: add as optional (safe to merge now)
{
  "properties": {
    "user_id": {"type": "string"},
    "theme":   {"type": "string"},
    "region":  {"type": "string"}
  },
  "required": ["user_id"]
}
```

Update all producers to include `region`. Then, once all producers are deployed:

```json
// Step 2: promote to required
{
  "required": ["user_id", "region"]
}
```

---

### Change a property type — breaking in all modes

```json
// Before: "notifications_enabled" is boolean
{"notifications_enabled": {"type": "boolean"}}

// After — BREAKING in all modes: changed to string
{"notifications_enabled": {"type": "string"}}
```

```
BREAKING  schemas/kafka/json/user-preferences.json
  Property "notifications_enabled" type changed from boolean to string
  Fix: Type changes are always breaking — add a new property instead
```

**Fix:** Introduce a new property with the new type (e.g. `"notifications_mode": {"type": "string"}`). Deprecate the old one, migrate consumers, then remove it in a later step.

---

### Add an optional property — always safe

```json
// After — PASS in all modes
{
  "properties": {
    "user_id":  {"type": "string"},
    "theme":    {"type": "string"},
    "timezone": {"type": "string"}    // new, not in required
  },
  "required": ["user_id"]
}
```

Adding a property that is not in `required` is always safe — old producers don't include it and old consumers ignore it.

---

## Per-Topic Overrides

```yaml
rules:
  kafka:
    compatibility: BACKWARD
    overrides:
      - topic: "internal/**"
        compatibility: NONE
      - topic: "**/public/**"
        compatibility: FULL
```

---

## Tips

- **Never add to `required` in one step.** Always introduce a new property as optional first, then move it to `required` once all producers write it.
- **Never change a property type.** Consumers that already deserialize the field will break. Introduce a new property with a different name instead.
- **Be careful with `enum` values.** Removing a known enum value is breaking in BACKWARD mode because existing consumers may receive messages with that value and fail to parse them.
- **Consider Avro or Protobuf for strict guarantees.** JSON Schema is schema-on-read and doesn't guarantee binary compatibility. For strict wire-format enforcement, Avro or Protobuf are stronger choices.
