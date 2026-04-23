# Multi-Contract Example — Payment Service

This example demonstrates all Phase 2 Contract Guardian scanner capabilities
against a fictional **Payment Service** microservices system. It covers:

- Avro schema compatibility (Kafka)
- JSON Schema compatibility (Kafka)
- Protobuf compatibility (Kafka)
- OpenAPI / REST breaking-change detection

---

## Project structure

```
multi-contract/
├── .contract-guardian.yml          # Scanner configuration
├── schemas/
│   └── kafka/
│       ├── avro/
│       │   └── payment-value.avsc  # Avro: PaymentEvent
│       ├── json/
│       │   └── user-preferences.json  # JSON Schema: UserPreferences
│       └── proto/
│           └── order-event.proto   # Protobuf: OrderEvent
├── api/
│   └── openapi/
│       └── catalog-service.yaml   # OpenAPI 3.0.3: Catalog Service
├── breaking-changes/               # Pre-built FAIL scenarios
│   ├── avro-field-removed/
│   ├── json-required-property-added/
│   ├── proto-field-without-reserved/
│   └── rest-endpoint-removed/
└── safe-changes/                   # Pre-built PASS scenarios
    ├── avro-field-with-default/
    ├── json-optional-property/
    └── rest-new-endpoint/
```

---

## Setup

Build the project once from the repository root, then initialise this example
as its own Git repository so Contract Guardian can compare your working tree
against the `main` baseline.

```bash
# 1. Build the CLI (from repo root)
cd /path/to/contract-guardian
mvn clean package -DskipTests

# 2. Enter the example directory
cd examples/multi-contract

# 3. Initialise Git and commit the baseline
git init
git add .
git commit -m "chore: baseline contracts for multi-contract example"
```

### Alias setup

```bash
JAR=../../contract-guardian-cli/target/contract-guardian-cli-1.0.0.jar
alias cg='java -jar $JAR'
```

Run the baseline scan to confirm everything passes:

```bash
cg scan
```

Expected output:

```
[INFO] Contract Guardian — scan started
[INFO] Source: kafka  — 3 schema(s) found
[INFO] Source: rest   — 1 spec(s) found
[INFO] Comparing against baseline: branch:main

[OK] schemas/kafka/avro/payment-value.avsc        BACKWARD compatible
[OK] schemas/kafka/json/user-preferences.json     BACKWARD compatible
[OK] schemas/kafka/proto/order-event.proto        BACKWARD compatible
[OK] api/openapi/catalog-service.yaml             No breaking changes

[PASS] 0 breaking change(s) detected.
```

---

## Breaking change scenarios

For each scenario below, copy the replacement file over the original, run the
scan, review the output, then restore the original with `git checkout`.

---

### 1. Avro — field removed without alias

**What breaks:** Removing `customer_id` from the Avro schema without declaring
it as an alias or providing a default means old consumers that deserialise
stored messages will fail to populate the field.

```bash
cp breaking-changes/avro-field-removed/payment-value.avsc \
   schemas/kafka/avro/payment-value.avsc

cg scan
```

Expected output:

```
[BREAKING] schemas/kafka/avro/payment-value.avsc
  - Field 'customer_id' removed with no default or alias. Violates BACKWARD compatibility.

[FAIL] 1 breaking change(s) detected. Gate: block-on breaking.
```

Restore:

```bash
git checkout schemas/kafka/avro/payment-value.avsc
```

---

### 2. JSON Schema — new required property added

**What breaks:** Adding `region` to the `required` array means existing
producers that do not send this field will produce messages that fail
validation. This is a FORWARD-incompatible change.

```bash
cp breaking-changes/json-required-property-added/user-preferences.json \
   schemas/kafka/json/user-preferences.json

cg scan
```

Expected output:

```
[BREAKING] schemas/kafka/json/user-preferences.json
  - Property 'region' added to 'required'. Existing producers will emit invalid messages.

[FAIL] 1 breaking change(s) detected. Gate: block-on breaking.
```

Restore:

```bash
git checkout schemas/kafka/json/user-preferences.json
```

---

### 3. Protobuf — field deleted without reserved declaration

**What breaks:** Removing field `status` (tag 4) without declaring
`reserved 4; reserved "status";` allows future fields to reuse tag 4.
Any consumer still on the old schema will silently misinterpret the data.

```bash
cp breaking-changes/proto-field-without-reserved/order-event.proto \
   schemas/kafka/proto/order-event.proto

cg scan
```

Expected output:

```
[BREAKING] schemas/kafka/proto/order-event.proto
  - Field 'status' (tag 4) removed without a reserved declaration. Tag reuse is possible.

[FAIL] 1 breaking change(s) detected. Gate: block-on breaking.
```

Restore:

```bash
git checkout schemas/kafka/proto/order-event.proto
```

---

### 4. REST — endpoint removed

**What breaks:** `POST /products` is removed from the OpenAPI spec. Any client
that calls this endpoint will receive a 404 at runtime.

```bash
cp breaking-changes/rest-endpoint-removed/catalog-service.yaml \
   api/openapi/catalog-service.yaml

cg scan
```

Expected output:

```
[BREAKING] api/openapi/catalog-service.yaml
  - Endpoint removed: POST /products. Existing clients will receive 404.

[FAIL] 1 breaking change(s) detected. Gate: block-on breaking.
```

Restore:

```bash
git checkout api/openapi/catalog-service.yaml
```

---

## Safe change scenarios

These changes are additive and backward compatible. The gate should pass.

---

### 1. Avro — new optional field with default

Adding `description` with `"default": ""` is BACKWARD compatible. Old consumers
ignore the field; new consumers get the default value for old messages.

```bash
cp safe-changes/avro-field-with-default/payment-value.avsc \
   schemas/kafka/avro/payment-value.avsc

cg scan
```

Expected output:

```
[OK] schemas/kafka/avro/payment-value.avsc        BACKWARD compatible

[PASS] 0 breaking change(s) detected.
```

Restore:

```bash
git checkout schemas/kafka/avro/payment-value.avsc
```

---

### 2. JSON Schema — new optional property

Adding `timezone` without including it in `required` is safe. Existing
producers are not required to send it, and existing consumers that do not read
it are unaffected.

```bash
cp safe-changes/json-optional-property/user-preferences.json \
   schemas/kafka/json/user-preferences.json

cg scan
```

Expected output:

```
[OK] schemas/kafka/json/user-preferences.json     BACKWARD compatible

[PASS] 0 breaking change(s) detected.
```

Restore:

```bash
git checkout schemas/kafka/json/user-preferences.json
```

---

### 3. REST — new endpoint added

Adding `GET /products/featured` is a purely additive change. No existing
client is affected.

```bash
cp safe-changes/rest-new-endpoint/catalog-service.yaml \
   api/openapi/catalog-service.yaml

cg scan
```

Expected output:

```
[OK] api/openapi/catalog-service.yaml             No breaking changes

[PASS] 0 breaking change(s) detected.
```

Restore:

```bash
git checkout api/openapi/catalog-service.yaml
```

---

## Configuration reference

The `.contract-guardian.yml` in this directory configures:

| Setting | Value | Meaning |
|---|---|---|
| `sources.kafka.baseline` | `branch:main` | Compare Kafka schemas against the `main` branch |
| `sources.rest.baseline` | `branch:main` | Compare OpenAPI specs against the `main` branch |
| `rules.kafka.compatibility` | `BACKWARD` | Default Kafka rule: new schema must read old data |
| `rules.kafka.overrides` | `internal.*` → `NONE` | Internal topics skip compatibility checks |
| `rules.rest.breaking` | see config | REST changes that block the gate |
| `rules.rest.ignore` | `/internal/**` | Internal REST paths are excluded |
| `gate.block-on` | `breaking` | CI fails when any breaking change is detected |
