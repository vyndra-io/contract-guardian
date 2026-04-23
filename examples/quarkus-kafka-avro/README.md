# Example: Quarkus Order Service with Contract Guardian

A sample Quarkus microservice that produces and consumes Kafka events using Avro schemas. Use this project to see how Contract Guardian catches breaking schema changes before they reach production.

## What's in this project

```
src/main/avro/
  order-value.avsc                  # Schema for the "orders" topic (this service owns it)
  order-status-changed-value.avsc   # Schema for the "order-status-changes" topic
  shipment-value.avsc               # Schema for the "shipments" topic (owned by logistics service)

src/main/java/com/example/order/
  OrderResource.java                # REST API: POST /orders, PUT /orders/{id}/cancel, etc.
  kafka/
    OrderEventProducer.java         # Publishes OrderValue and OrderStatusChangedValue to Kafka
    ShipmentEventConsumer.java      # Consumes ShipmentValue events from logistics service

breaking-changes/                   # Pre-made schema changes to try with Contract Guardian
  01-remove-field/                  # BREAKING: removes customer_email (no default)
  02-remove-enum-symbol/            # BREAKING: removes CANCELLED from OrderStatus enum
  03-safe-evolution/                # SAFE: adds optional shipping_address and currency with defaults
```

## Prerequisites

- Java 17+
- Maven 3.8+

You do **not** need a running Kafka or Schema Registry to try out Contract Guardian. It only needs git and the schema files.

## Try it out

### Step 1: Build the Contract Guardian CLI

The Contract Guardian CLI is a separate project from this example Quarkus app. You need to build it first.

From the **root** of the `contract-guardian` repository (the parent, **not** this example directory):

```bash
# Go to the contract-guardian repo root (NOT examples/quarkus-kafka-avro)
cd /path/to/contract-guardian

mvn package -DskipTests
```

This produces the CLI fat jar at:

```
contract-guardian-cli/target/contract-guardian-cli-1.0.0.jar
```

> **Important:** This is the jar you run for all `contract-guardian` commands.
> Do **not** use the Quarkus example's jar (`order-service-1.0.0-SNAPSHOT.jar`) — that is the sample application, not the CLI tool.

Set up an alias to avoid typing the full path every time:

```bash
# Replace with the actual absolute path to your contract-guardian repo
export CG_HOME=/path/to/contract-guardian

alias contract-guardian="java -jar $CG_HOME/contract-guardian-cli/target/contract-guardian-cli-1.0.0.jar"
```

To make the alias permanent, add both lines to your `~/.bashrc` or `~/.zshrc`.

Verify it works:

```bash
contract-guardian --help
```

### Step 2: Initialize a git repo for this example

Contract Guardian uses `git diff` to detect changes, so this example needs to be its own git repo:

```bash
cd /path/to/contract-guardian/examples/quarkus-kafka-avro

git init
git add .
git commit -m "Initial commit with order service schemas"
```

### Step 3: Validate the config

```bash
contract-guardian validate
```

Expected output:
```
Config is valid.
```

### Step 4: Try a breaking change — remove a field

Copy the breaking schema over the original and scan:

```bash
cp breaking-changes/01-remove-field/order-value.avsc src/main/avro/order-value.avsc
contract-guardian scan --diff HEAD
```

Expected output:
```
  BREAKING  src/main/avro/order-value.avsc
    Field at 'customer_email' was removed or has no default — breaks backward compatibility

  Result: FAIL (1 breaking, 0 warning, 0 pass)
```

**Why this breaks:** Consumers running the old schema expect a `customer_email` field. Messages produced with the new schema won't have it, and there's no default value to fall back on.

Restore the original:
```bash
git checkout -- src/main/avro/order-value.avsc
```

### Step 5: Try a breaking change — remove an enum symbol

```bash
cp breaking-changes/02-remove-enum-symbol/order-value.avsc src/main/avro/order-value.avsc
contract-guardian scan --diff HEAD
```

Expected output:
```
  BREAKING  src/main/avro/order-value.avsc
    Enum symbols removed at 'status' — breaks backward compatibility: [CANCELLED]

  Result: FAIL (1 breaking, 0 warning, 0 pass)
```

**Why this breaks:** Any existing message in Kafka with `status: CANCELLED` cannot be deserialized by consumers using the new schema — the symbol no longer exists.

Restore:
```bash
git checkout -- src/main/avro/order-value.avsc
```

### Step 6: Try a safe evolution — add optional fields

```bash
cp breaking-changes/03-safe-evolution/order-value.avsc src/main/avro/order-value.avsc
contract-guardian scan --diff HEAD
```

Expected output:
```
  PASS      src/main/avro/order-value.avsc
    Schema change is compatible

  Result: PASS (0 breaking, 0 warning, 1 pass)
```

**Why this is safe:** Both new fields (`shipping_address` and `currency`) have default values. Consumers running the old schema simply ignore the new fields. Consumers running the new schema get the default when reading old messages.

### Step 7: Generate a JUnit report

```bash
cp breaking-changes/01-remove-field/order-value.avsc src/main/avro/order-value.avsc

contract-guardian scan --diff HEAD \
  --reporter terminal \
  --reporter junit:build/contract-guardian-report.xml
```

The JUnit XML at `build/contract-guardian-report.xml` can be uploaded to your CI dashboard (GitHub Actions, GitLab, Jenkins) to show contract checks alongside your test results.

## Understanding the config

The `.contract-guardian.yml` in this project:

```yaml
version: "1"

sources:
  kafka:
    paths:
      - "src/main/avro/**/*.avsc"     # Scan all Avro schemas under src/main/avro
    baseline: branch:main

rules:
  kafka:
    compatibility: BACKWARD           # Default: new schema must read old data
    overrides:
      - topic: "**/order-status-changed*"
        compatibility: NONE           # Internal event — teams coordinate deploys

gate:
  block-on: breaking                  # Fail the scan on any breaking change
```

Key points:
- The `paths` pattern points to `src/main/avro/` where Quarkus expects Avro schemas
- The override sets `NONE` for the internal status-changed event, meaning it can break freely
- The gate blocks on `breaking` — warnings are reported but don't fail the build

## Using the Maven plugin (alternative to CLI)

Instead of running the CLI manually, you can integrate Contract Guardian directly into your Maven build. This project's `pom.xml` already includes the plugin:

```xml
<plugin>
    <groupId>io.contractguardian</groupId>
    <artifactId>contract-guardian-maven-plugin</artifactId>
    <version>1.0.0</version>
    <executions>
        <execution>
            <goals>
                <goal>validate</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

The plugin uses sensible defaults (policy file at `.contract-guardian.yml`, diff against `origin/main..HEAD`). Override them via `<configuration>` or `-D` properties.

With this in place, schema validation runs automatically during `mvn validate` (the first phase of the build lifecycle). Any breaking change will fail the build.

### Try it with a breaking change

```bash
# Copy a breaking schema change
cp breaking-changes/01-remove-field/order-value.avsc src/main/avro/order-value.avsc

# Run the build — it will fail at the validate phase
mvn compile
```

You'll see:
```
[ERROR] Contract Guardian: BUILD FAILED — 1 breaking change(s) detected.
```

Restore and rebuild:
```bash
git checkout -- src/main/avro/order-value.avsc
mvn compile   # passes
```

### Configuration options

All parameters can be set via `-D` properties:

| Property | Default | Description |
|---|---|---|
| `contractguardian.policyFile` | `.contract-guardian.yml` | Path to the policy file |
| `contractguardian.diff` | `origin/main..HEAD` | Git diff spec for change detection |
| `contractguardian.workingDir` | `${project.basedir}` | Working directory for git operations |
| `contractguardian.skip` | `false` | Skip validation entirely |

For example, to compare against a different branch or skip during local development:

```bash
# Compare against a different base
mvn compile -Dcontractguardian.diff=develop..HEAD

# Skip validation
mvn compile -Dcontractguardian.skip=true
```

## Building the Quarkus project (optional)

If you want to actually build and run the Quarkus service (requires Docker for Kafka dev services):

```bash
# Build (generates Avro classes from .avsc files)
mvn compile

# Run in dev mode (starts Kafka + Schema Registry via Dev Services)
mvn quarkus:dev

# Create an order
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-123",
    "customerEmail": "alice@example.com",
    "items": [{
      "productId": "prod-456",
      "productName": "Wireless Mouse",
      "quantity": 2,
      "unitPrice": 29.99
    }]
  }'
```
