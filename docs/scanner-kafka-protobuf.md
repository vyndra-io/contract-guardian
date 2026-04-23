# Kafka Protobuf Scanner

The Protobuf scanner uses the [Wire schema parser](https://github.com/square/wire) to detect changes in `.proto` files that would corrupt existing serialized data or break consumers.

**Key fact about Protobuf:** Each field is identified on the wire by its **tag number**, not its name. This means:
- **Renaming a field is safe** — the name doesn't appear in the binary format.
- **Reusing a tag number for a different type is catastrophic** — old messages stored in Kafka still carry the original tag, and a consumer that reads them with the new schema will silently produce corrupt data.

---

## Setup

```yaml
# .contract-guardian.yml
version: "1"

sources:
  kafka:
    paths:
      - "schemas/kafka/**/*.proto"
    baseline: branch:main

rules:
  kafka:
    compatibility: BACKWARD

gate:
  block-on: breaking
```

---

## Compatibility Modes

Protobuf checks are about wire-format safety. The compatibility mode controls whether checks run at all, not the direction they apply:

| Mode | Behavior |
|---|---|
| `BACKWARD` (default) | Checks for all wire-format breaking changes |
| `FORWARD` | Same as BACKWARD — Protobuf checks are direction-agnostic |
| `FULL` | Same as BACKWARD |
| `NONE` | No checks. Use for experimental or internal-only topics. |

---

## What Is Checked

| Change | Breaking? | Why |
|---|---|---|
| Remove field **with** `reserved` | No | Tag is protected — cannot be accidentally reused |
| Remove field **without** `reserved` | **Yes** | Tag can be reused in a future change, corrupting existing data |
| Add a new field | No | Existing readers skip unknown fields by design |
| Change field type at the same tag | **Yes** | Wire encoding differs — readers misinterpret the bytes |
| Reuse a tag number for a different field | **Yes** | Old messages at that tag are decoded as the wrong type |
| Remove an enum value | **Yes** | Consumers that receive this value fail to deserialize |
| Add an enum value | No | Unknown enum values are stored as their integer — old readers still decode |
| Add a new message or enum | No | Purely additive |

---

## The `reserved` Keyword — Most Important Rule

When you remove a field, you **must** mark both its tag number and field name as `reserved`. This permanently retires the tag and prevents it from ever being reused.

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
// After — BREAKING: "status" removed without reserved
message OrderEvent {
  string id = 1;
  string customer_id = 2;
  double total_amount = 3;
}
```

```
BREAKING  schemas/kafka/proto/order-event.proto
  Field 'status' (tag 4) removed from message 'OrderEvent' without reserved declaration
  Fix: Add 'reserved 4; reserved "status";' to the message
```

**Correct fix:**

```proto
// After — PASS
message OrderEvent {
  reserved 4;
  reserved "status";

  string id = 1;
  string customer_id = 2;
  double total_amount = 3;
}
```

> **Why reserve both?**
> - Reserving only the tag number still allows someone to re-add a field named `status` at a different tag.
> - Reserving only the name still allows tag 4 to be reused for a completely different field.
> - You must reserve both.

---

## Common Scenarios

### Change a field type — always breaking

```proto
// Before
string customer_id = 2;

// After — BREAKING: type changed at same tag
int64 customer_id = 2;
```

```
BREAKING  schemas/kafka/proto/order-event.proto
  Field 'customer_id' (tag 2) in message 'OrderEvent' changed type from string to int64
  Fix: Never change the type of an existing field — add a new field with the new type instead
```

**Safe fix:** Add a new field at a new tag number, migrate consumers, then remove the old field with `reserved`:

```proto
string customer_id = 2;     // keep the original during migration
int64 customer_id_v2 = 5;   // new field at new tag — producers now write this
```

Once all consumers read `customer_id_v2`:

```proto
reserved 2;
reserved "customer_id";

int64 customer_id_v2 = 5;
```

---

### Remove an enum value — breaking

```proto
// Before
enum OrderStatus {
  PLACED    = 0;
  SHIPPED   = 1;
  CANCELLED = 2;
}

// After — BREAKING: CANCELLED removed without reserved
enum OrderStatus {
  PLACED  = 0;
  SHIPPED = 1;
}
```

```
BREAKING  schemas/kafka/proto/order-event.proto
  Enum value 'CANCELLED' (2) removed from 'OrderStatus'
  Fix: Reserve the value number and name: reserved 2; reserved "CANCELLED";
```

**Correct fix:**

```proto
enum OrderStatus {
  reserved 2;
  reserved "CANCELLED";

  PLACED  = 0;
  SHIPPED = 1;
}
```

---

### Add a new field — always safe

```proto
// After — PASS: new field at new tag
message OrderEvent {
  reserved 4;
  reserved "status";

  string id = 1;
  string customer_id = 2;
  double total_amount = 3;
  string tracking_number = 5;   // new field
}
```

Existing consumers that don't know about `tracking_number` will simply ignore it.

---

## Proto3 vs Proto2

Both proto2 and proto3 are supported. Key differences:

- **Proto3** — all fields are optional by default; there is no `required` keyword. Field removal is safer from a schema perspective, but you still need `reserved` to prevent tag reuse.
- **Proto2** — removing a `required` field is a breaking change regardless of `reserved`, because existing serializers expect the field to be present.

---

## Per-Topic Overrides

```yaml
rules:
  kafka:
    compatibility: BACKWARD
    overrides:
      - topic: "internal/**"
        compatibility: NONE
```

---

## Tips

- **Always use `reserved` when removing a field or enum value.** This is the single most important rule for safe Protobuf evolution.
- **Never change a field's type at the same tag.** Add a new field at a new tag and migrate consumers instead.
- **Do not reuse tag numbers**, even if a field was removed years ago. Old messages in Kafka retention may still carry data at that tag.
- **Tag numbers 1–15 use 1 byte for encoding.** Reserve them for frequently-used fields that are unlikely to ever be removed.
- **Proto3 default values are not transmitted.** A field set to its default (empty string, 0, false) is omitted from the wire. Consumers cannot distinguish "field was omitted" from "field was set to default" — design your schemas accordingly.
