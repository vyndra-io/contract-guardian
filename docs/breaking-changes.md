# Breaking and Non-Breaking Contract Changes

This guide explains what makes a schema or API change breaking, what makes it safe, and how to evolve contracts without causing production incidents.

It covers all contract types that Contract Guardian scans: **Kafka Avro**, **Kafka JSON Schema**, **Kafka Protobuf**, and **REST OpenAPI**.

---

## The Basics

### What is a contract?

A contract is the shared agreement between a producer and a consumer about the shape of data. Examples:

- An Avro schema defining the fields in a Kafka message
- A JSON Schema file describing the structure of a Kafka event
- A `.proto` file defining the wire format for Protobuf messages
- An OpenAPI spec describing the HTTP endpoints a service exposes

### What makes a change breaking?

A change is **breaking** if it causes a running consumer or caller to fail **without any code change on their side**. For example:

- A consumer deserializing a Kafka message throws an exception
- An API client receives an unexpected HTTP error
- A field a consumer was reading no longer exists or has the wrong type

### What makes a change safe?

A change is **safe** (backward compatible) if all existing consumers continue to work correctly after the change is deployed, with no changes on their side.

---

## The Golden Rules

These apply across all schema types:

1. **Never remove a field or endpoint that a consumer may be reading.**
2. **Never change a field's type.**
3. **Never add a required field or parameter without providing a default or fallback.**
4. **Deprecate before removing.** Give consumers time to adapt.
5. **Deploy in dependency order.** For backward compatibility, deploy new consumers before old producers drain. For forward compatibility, deploy new producers first.

---

## Kafka Avro

Avro uses binary encoding where fields are matched by name (not position). Compatibility is checked by comparing the new schema (reader) against the old schema (writer), or vice versa, depending on mode.

### Breaking Changes

| Change | Why it breaks |
|---|---|
| Remove a field with no default | New schema has no fallback value for old messages |
| Add a field without a default | Old messages don't contain this field; the new schema has nothing to fill in |
| Change a field's type | Avro binary encoding is type-specific — reading an `int` as a `string` corrupts the value |
| Remove an enum symbol | Consumers reading old messages may receive the removed symbol and fail to match it |
| Narrow a union (remove a branch) | Messages using the removed branch can no longer be deserialized |
| Rename a field without an alias | New schema cannot match the renamed field to old binary data |

### Safe Changes

| Change | Why it is safe |
|---|---|
| Add a field with a default | Old messages use the default; no data is lost |
| Remove a field that has a default | Old readers continue to work using the default |
| Add an enum symbol (BACKWARD mode) | Old messages never contain the new symbol |
| Expand a union (add a branch) | Old messages never use the new branch |
| Add an alias to a field | Allows schema migration without renaming on the wire |
| Add a new record type | Purely additive |

### How to Remove a Field Safely

Direct removal is breaking. Use these two steps instead:

**Step 1 — make the field nullable with a default (safe to merge now):**

```json
{
  "name": "customer_email",
  "type": ["null", "string"],
  "default": null
}
```

Deploy this. All consumers now tolerate the absence of the field.

**Step 2 — remove the field (safe to merge after all consumers are updated):**

Once all consuming services have been deployed and no longer read `customer_email`, remove the field entirely.

### How to Change a Field Type Safely

Avro type changes are always breaking at the wire level. The safe path:

1. Add a new field with the desired type and a default:
   ```json
   {"name": "amount_cents", "type": "long", "default": 0}
   ```
2. Update producers to write both `amount` (old) and `amount_cents` (new).
3. Update consumers to read `amount_cents`.
4. Once all consumers read the new field, remove `amount` using the two-step removal above.

### Compatibility Mode Reference

| Change | BACKWARD | FORWARD | FULL |
|---|---|---|---|
| Add field with default | Pass | Pass | Pass |
| Add field without default | **Breaking** | Pass | **Breaking** |
| Remove field with default | Pass | Pass | Pass |
| Remove field without default | Pass | **Breaking** | **Breaking** |
| Change field type | **Breaking** | **Breaking** | **Breaking** |
| Add enum symbol | Pass | **Breaking** | **Breaking** |
| Remove enum symbol | **Breaking** | Pass | **Breaking** |
| Expand union | Pass | **Breaking** | **Breaking** |
| Narrow union | **Breaking** | Pass | **Breaking** |

---

## Kafka JSON Schema

JSON Schema is schema-on-read. The schema is not embedded in each message like Avro or Protobuf — consumers apply it to parse raw JSON. This makes some changes safe that would fail in Avro, and some dangerous that Avro handles automatically.

### Breaking Changes

| Change | Why it breaks |
|---|---|
| Remove a property | Consumers that access this property get `null` or an exception |
| Add a property to `required` | Old producers don't include the field; new schema validation rejects those messages |
| Change a property's `type` | Consumers deserializing to a typed model fail on the wrong type |
| Remove an `enum` value | Consumers receiving the removed value fail schema validation |
| Tighten `minLength`, `minimum`, `maxItems`, etc. | Previously valid messages now fail validation |
| Remove `additionalProperties: true` or add `false` | Messages with extra fields are now rejected |

### Safe Changes

| Change | Why it is safe |
|---|---|
| Add an optional property (not in `required`) | Old producers simply omit it; old consumers ignore it |
| Loosen a constraint (`minLength` reduced, `maximum` increased) | All previously valid messages still pass |
| Add an `enum` value | Old consumers never encounter the new value |
| Add a new definition to `$defs` / `definitions` | Purely additive |
| Add metadata fields (`title`, `description`, `examples`) | Informational only — validators ignore them |

### How to Add a Required Field Safely

**Step 1 — add the field as optional:**

```json
{
  "properties": {
    "user_id": {"type": "string"},
    "region":  {"type": "string"}
  },
  "required": ["user_id"]
}
```

Deploy this. Update all producers to include `region`.

**Step 2 — promote to required (after all producers write the field):**

```json
{
  "required": ["user_id", "region"]
}
```

### Compatibility Mode Reference

| Change | BACKWARD | FORWARD | FULL |
|---|---|---|---|
| Add optional property | Pass | Pass | Pass |
| Add required property | Pass | **Breaking** | **Breaking** |
| Remove property | **Breaking** | Pass | **Breaking** |
| Change property type | **Breaking** | **Breaking** | **Breaking** |
| Remove enum value | **Breaking** | Pass | **Breaking** |
| Add enum value | Pass | **Breaking** | **Breaking** |

---

## Kafka Protobuf

Protobuf uses a tag-based binary encoding. Each field is identified on the wire by its **tag number**, not its name. This has a critical implication:

- **Renaming a field is safe** — the name doesn't appear in the binary format.
- **Reusing a tag number for a different field is catastrophic** — old messages already stored in Kafka carry the original tag-to-type mapping. A consumer reading those messages with a schema where tag 4 means something different will silently produce corrupt data.

### Breaking Changes

| Change | Why it breaks |
|---|---|
| Remove a field without `reserved` | The tag number can be reused in a future change, corrupting existing messages |
| Change a field's type at the same tag | Readers decode the raw bytes using the wrong type — silent data corruption |
| Reuse a tag number for a different field | Old messages at that tag are decoded with the wrong type |
| Remove an enum value without `reserved` | Consumers receiving old messages with that value fail to decode |
| Remove a `required` field (proto2 only) | Serializers expect the field to be present |

### Safe Changes

| Change | Why it is safe |
|---|---|
| Add a new field at a new tag | Existing readers skip unknown fields by design |
| Remove a field **with** `reserved` | Tag is permanently retired; cannot be accidentally reused |
| Rename a field (same tag, same type) | Protobuf wire format does not include field names |
| Add a new message type | Purely additive |
| Add a new enum value | Old readers store unknown values as their integer — no crash |

### The `reserved` Keyword

When you remove a field, you **must** declare both its tag number and field name as `reserved`. This prevents the tag from ever being assigned to a new field.

```proto
// Before
message OrderEvent {
  string id = 1;
  string customer_id = 2;
  double total_amount = 3;
  string status = 4;
}
```

```proto
// After — correct removal
message OrderEvent {
  reserved 4;
  reserved "status";

  string id = 1;
  string customer_id = 2;
  double total_amount = 3;
}
```

> **Why reserve both?** Reserving only the tag number still allows someone to re-add a field named `status` at a different tag. Reserving only the name still allows tag 4 to be reused for a different field. You must reserve both.

### How to Change a Field Type Safely

Type changes at the same tag are always breaking. Safe migration:

1. Add a new field at a new tag with the new type.
2. Update producers to write both fields.
3. Update consumers to read the new field.
4. Remove the old field with `reserved`.

```proto
// Migration in progress — both fields present
message OrderEvent {
  string id = 1;
  string customer_id = 2;   // original field: string
  int64 customer_id_v2 = 5; // new field: int64, producers now write this
  double total_amount = 3;
}
```

---

## REST OpenAPI

REST API compatibility is about callers (HTTP clients) being able to continue calling the same endpoints with the same requests and getting responses they understand.

### Breaking Changes

| Change | Why it breaks |
|---|---|
| Remove an endpoint | Callers receive `404 Not Found` |
| Remove a required path or query parameter | Existing requests that omit the parameter are now rejected |
| Add a required query or header parameter | Existing callers that don't send it receive `400 Bad Request` |
| Remove a response field | Callers that read this field get `null` or a deserialization error |
| Change a response field's type | Callers fail to deserialize the new type |
| Change an existing status code | Callers matching on the expected code handle the wrong response |
| Require authentication on a previously public endpoint | Unauthenticated callers receive `401` |
| Remove an `enum` value from a request field | Existing callers sending that value receive a validation error |

### Safe Changes

| Change | Why it is safe |
|---|---|
| Add a new endpoint | Existing callers don't know about it — nothing breaks |
| Add an optional query or header parameter | Existing callers simply omit it; the default applies |
| Add a new response field | Callers that don't know about it ignore it |
| Add a new status code | Existing callers may receive it but are not required to handle it |
| Mark an endpoint or field `deprecated: true` | Purely informational; behaviour is unchanged |
| Relax request validation (make a required parameter optional) | Existing callers already send the parameter |

### How to Remove an Endpoint Safely

Never remove an endpoint without a deprecation period.

**Step 1 — mark the endpoint deprecated:**

```yaml
paths:
  /products:
    post:
      deprecated: true
      description: "Deprecated. Use POST /v2/products instead."
```

Contract Guardian reports this as a warning, not a blocking finding.

**Step 2 — introduce the replacement:**

Add `POST /v2/products` alongside the old endpoint. Update callers to use the new path.

**Step 3 — remove the old endpoint:**

Once all callers have been updated and traffic to the old endpoint reaches zero, remove it. This will be a breaking finding in Contract Guardian — intentionally bypass it with an approval label or move the endpoint to the `ignore` list during the removal window.

### How to Add a Required Parameter Safely

**Wrong:** Add a required parameter directly — all existing callers immediately break.

**Right:**

1. Add the parameter as optional with a documented default:
   ```yaml
   parameters:
     - name: currency
       in: query
       required: false
       schema:
         type: string
         default: "USD"
   ```
2. Update callers to pass the parameter explicitly.
3. Once all callers are updated, you may promote it to `required: true` — but only if you control all callers. For public APIs, never make an existing parameter required.

### API Versioning

When a breaking change is unavoidable, introduce a new API version:

- **URL versioning** (`/v1/products` → `/v2/products`) — explicit and easy to route
- **Header versioning** (`Accept: application/vnd.example.v2+json`) — cleaner URLs, harder to test

Maintain the previous version for a documented deprecation period. Use Contract Guardian's `ignore` configuration to exclude versioned legacy paths from enforcement once they are officially deprecated.

---

## Universal Patterns for Safe Migrations

### Expand → Migrate → Contract

Use this pattern for any contract type when you need to rename or remove a field. Each phase is a separate deployment — no phase breaks the running system.

```
Phase 1 — EXPAND
  Add the new field alongside the old one.
  Producers write both. Consumers read the old field.

Phase 2 — MIGRATE
  Update consumers to read the new field.
  Producers continue writing both.

Phase 3 — CONTRACT
  Remove the old field from producers.
  Once all consumers are migrated, remove the old field from the schema.
```

### N-Version Compatibility

In systems with rolling deployments (Kubernetes, blue-green), multiple versions of a service run simultaneously. A new schema must be compatible with the previous N versions, not just the immediately preceding one.

For a rolling deployment where up to two old versions may be running:
- New schema must be backward compatible with version N-1 and N-2.
- Old schemas must be able to read messages written by the new schema.

Set `compatibility: FULL` and verify your two-step migration spans enough deployment cycles.

### Deprecation Timeline

| Step | Action |
|---|---|
| Release 1 | Add the replacement field/endpoint; mark the old one `deprecated` |
| Release 2 | Notify consuming teams; provide a migration guide |
| Release 3+ | Monitor usage; confirm consumers have migrated |
| Final release | Remove the deprecated field/endpoint |

Never skip the deprecation step for public contracts. Always allow at least one full release cycle between deprecation and removal.

### Semantic Versioning and Contract Changes

| SemVer bump | What it signals |
|---|---|
| Patch (`1.0.x`) | Bug fix — no contract change |
| Minor (`1.x.0`) | New feature — only additive, non-breaking changes |
| Major (`x.0.0`) | Breaking contract change — consumers must update |

---

## Quick Reference

### Is my change breaking?

Check these before merging:

- [ ] Did I remove a field, property, or endpoint?
- [ ] Did I add a new field or parameter without a default?
- [ ] Did I change a field's type?
- [ ] Did I remove an enum value?
- [ ] Did I tighten a validation constraint?
- [ ] Did I make an optional parameter required?
- [ ] In Protobuf: did I reuse a tag number?
- [ ] In Protobuf: did I remove a field without `reserved`?

**If any box is checked, the change is breaking.** Either use a safe migration pattern or version the contract.

### Safe Change Patterns at a Glance

| Goal | Safe pattern |
|---|---|
| Remove a field | Make it optional/defaulted first, remove in a follow-up |
| Rename a field | Add an alias (Avro), or add new field + remove old (all others) |
| Change a field's type | Add new field at new tag/name, migrate consumers, remove old |
| Remove an endpoint | Deprecate first, remove after migration window |
| Add a required parameter | Add as optional first, require only after all callers are updated |
| Change an enum | Add values freely; never remove without migration |

---

## Further Reading

- [Kafka Avro Scanner](scanner-kafka-avro.md)
- [Kafka JSON Schema Scanner](scanner-kafka-json-schema.md)
- [Kafka Protobuf Scanner](scanner-kafka-protobuf.md)
- [REST OpenAPI Scanner](scanner-rest-openapi.md)
- [Configuration Reference](configuration.md)
