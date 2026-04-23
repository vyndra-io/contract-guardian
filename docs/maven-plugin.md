# Maven Plugin

The Contract Guardian Maven plugin lets you catch breaking contract changes **as part of your regular Maven build**, without needing a separate CLI invocation in CI.

It provides two goals:

| Goal | Runs at phase | What it does |
|---|---|---|
| `validate` | `validate` | Scans for breaking changes and fails the build if found |
| `generate-spec` | `process-classes` | Generates an OpenAPI spec from Java annotations |

---

## Installation

Add the plugin to your project's `pom.xml`:

```xml
<plugin>
  <groupId>io.contractguardian</groupId>
  <artifactId>contract-guardian-maven-plugin</artifactId>
  <version>1.0.0</version>
</plugin>
```

---

## `validate` Goal

Scans changed contract files against a baseline and fails the build if breaking changes are detected.

### Minimal Configuration

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

With no additional configuration, the plugin:
- Compares `origin/main..HEAD`
- Reads `.contract-guardian.yml` from the project base directory
- Fails the build if any breaking change is found

### Parameters

| Parameter | CLI property | Default | Description |
|---|---|---|---|
| `policyFile` | `contractguardian.policyFile` | `${project.basedir}/.contract-guardian.yml` | Path to the config file |
| `diff` | `contractguardian.diff` | `origin/main..HEAD` | Git diff spec |
| `workingDir` | `contractguardian.workingDir` | `${project.basedir}` | Git repository root |
| `skip` | `contractguardian.skip` | `false` | Skip validation entirely |
| `failOnMissingRef` | `contractguardian.failOnMissingRef` | `false` | Fail if the baseline git ref cannot be resolved |

### `failOnMissingRef`

By default, if the baseline ref (e.g. `origin/main`) doesn't exist — which is common on a developer's local machine that has never fetched — the plugin logs a warning and skips validation. This prevents failing local builds when `origin/main` isn't available.

Set `failOnMissingRef=true` to treat a missing ref as a build error. Recommended in CI where `origin/main` should always be present:

```xml
<configuration>
  <failOnMissingRef>true</failOnMissingRef>
</configuration>
```

Or pass it from the command line:

```bash
mvn validate -Dcontractguardian.failOnMissingRef=true
```

### Skipping Validation

```bash
mvn package -Dcontractguardian.skip=true
```

Or configure a profile for local development:

```xml
<profiles>
  <profile>
    <id>local</id>
    <properties>
      <contractguardian.skip>true</contractguardian.skip>
    </properties>
  </profile>
</profiles>
```

### Overriding the Diff Spec

```bash
# Compare against a specific commit
mvn validate -Dcontractguardian.diff=abc123..HEAD

# Compare the last three commits
mvn validate -Dcontractguardian.diff=HEAD~3..HEAD
```

### Example Output

When the build passes:

```
[INFO] Contract Guardian: scanning changes (origin/main..HEAD)...
[INFO] Contract Guardian: PASS (0 breaking, 0 warning, 3 pass)
```

When the build fails:

```
[ERROR] [BREAKING] schemas/kafka/avro/payment-value.avsc — Field "customer_id" removed
[ERROR] Contract Guardian: FAIL (1 breaking, 0 warning, 2 pass)
[ERROR] BUILD FAILURE
[ERROR] Contract Guardian: BUILD FAILED — 1 breaking change(s) detected.
        Fix the schema incompatibilities or update the policy.
```

---

## `generate-spec` Goal

Generates an OpenAPI YAML spec from compiled Java classes annotated with Swagger/OpenAPI annotations. The generated file should be committed to version control so the `validate` goal can diff it on the next PR.

This is useful when your team writes Spring MVC, JAX-RS, or MicroProfile controllers with `@Operation`, `@Schema`, and `@ApiResponse` annotations — instead of maintaining a YAML file by hand.

### How It Works

1. Runs at `process-classes` — after `compile`, before `package`.
2. Builds a classloader from the project's compiled output and all compile-scope dependencies.
3. Scans the configured packages for Swagger Core v3 annotations.
4. Writes an `openapi.yaml` to the configured output path.

### Parameters

| Parameter | CLI property | Default | Description |
|---|---|---|---|
| `packagesToScan` | `contractguardian.packagesToScan` | *(required)* | List of packages to scan for annotations |
| `outputFile` | `contractguardian.specOutputFile` | `${project.basedir}/src/main/resources/openapi.yaml` | Path where the spec is written |
| `skip` | `contractguardian.generateSpec.skip` | `false` | Skip spec generation |

### Minimal Configuration

```xml
<plugin>
  <groupId>io.contractguardian</groupId>
  <artifactId>contract-guardian-maven-plugin</artifactId>
  <version>1.0.0</version>
  <executions>
    <execution>
      <id>generate-openapi-spec</id>
      <goals>
        <goal>generate-spec</goal>
      </goals>
      <configuration>
        <packagesToScan>
          <package>com.example.api</package>
        </packagesToScan>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### Custom Output Path

```xml
<configuration>
  <packagesToScan>
    <package>com.example.api</package>
    <package>com.example.model</package>
  </packagesToScan>
  <outputFile>${project.basedir}/docs/openapi.yaml</outputFile>
</configuration>
```

Then point Contract Guardian's `rest` source at the same path:

```yaml
sources:
  rest:
    paths:
      - "docs/openapi.yaml"
    baseline: branch:main
```

### Using Both Goals Together

```xml
<plugin>
  <groupId>io.contractguardian</groupId>
  <artifactId>contract-guardian-maven-plugin</artifactId>
  <version>1.0.0</version>
  <executions>

    <execution>
      <id>generate-openapi-spec</id>
      <goals>
        <goal>generate-spec</goal>
      </goals>
      <configuration>
        <packagesToScan>
          <package>com.example.api</package>
        </packagesToScan>
        <outputFile>${project.basedir}/docs/openapi.yaml</outputFile>
      </configuration>
    </execution>

    <execution>
      <id>validate-contracts</id>
      <goals>
        <goal>validate</goal>
      </goals>
      <configuration>
        <failOnMissingRef>true</failOnMissingRef>
      </configuration>
    </execution>

  </executions>
</plugin>
```

> **Note on phase ordering:** `generate-spec` runs at `process-classes`; `validate` runs at `validate` (which is earlier). Maven runs phases in order, so `validate` happens first — but the spec on the current branch was already generated and committed in a previous commit. The `validate` goal diffs the committed spec against the baseline, which is intentional.
>
> If you want to generate and immediately validate in the same build, bind `validate` to a later phase:
>
> ```xml
> <execution>
>   <id>validate-contracts</id>
>   <phase>package</phase>   <!-- run after generate-spec at process-classes -->
>   <goals>
>     <goal>validate</goal>
>   </goals>
> </execution>
> ```

### Running Goals Manually

```bash
# Generate the spec only
mvn contract-guardian:generate-spec

# Validate only
mvn contract-guardian:validate

# Skip spec generation
mvn package -Dcontractguardian.generateSpec.skip=true
```

---

## Supported Annotations

The generator uses [swagger-integration](https://github.com/swagger-api/swagger-core) to scan for standard Swagger Core v3 annotations:

| Annotation | Package | Purpose |
|---|---|---|
| `@Operation` | `io.swagger.v3.oas.annotations` | Describes an endpoint |
| `@ApiResponse` | `io.swagger.v3.oas.annotations.responses` | Describes a response status and body |
| `@Parameter` | `io.swagger.v3.oas.annotations.parameters` | Describes a request parameter |
| `@RequestBody` | `io.swagger.v3.oas.annotations.parameters` | Describes a request body |
| `@Schema` | `io.swagger.v3.oas.annotations.media` | Describes a model field or class |
| `@Tag` | `io.swagger.v3.oas.annotations` | Groups endpoints into categories |
| `@Content` | `io.swagger.v3.oas.annotations.media` | Describes media type content |

**For Spring MVC projects,** add the swagger-annotations dependency:

```xml
<dependency>
  <groupId>io.swagger.core.v3</groupId>
  <artifactId>swagger-annotations</artifactId>
  <version>2.2.21</version>
</dependency>
```

**For Jakarta EE / JAX-RS projects,** use `org.eclipse.microprofile.openapi:microprofile-openapi-api` annotations — they follow the same standard and are also scanned.
