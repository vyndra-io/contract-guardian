# REST OpenAPI Scanner

The REST scanner uses [openapi-diff](https://github.com/OpenAPITools/openapi-diff) to compare OpenAPI 3.x spec files and detect changes that would break existing API clients.

You can provide the spec in two ways:

- **Spec-file based** — you maintain an OpenAPI YAML or JSON file directly. The scanner diffs the committed file.
- **Java annotation based** — your controllers use Swagger/OpenAPI annotations (`@Operation`, `@Schema`, etc.) and you generate the spec at build time using the Contract Guardian Maven plugin.

Both approaches scan the same thing: an OpenAPI spec file checked into git.

---

## Spec-File Based Setup

If you already have an OpenAPI YAML or JSON file committed to your repository:

```yaml
# .contract-guardian.yml
version: "1"

sources:
  rest:
    paths:
      - "api/openapi/**/*.yaml"
    baseline: branch:main

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

gate:
  block-on: breaking
```

When a `.yaml` file under `api/openapi/` changes, Contract Guardian loads both the baseline and current versions and reports any breaking changes.

---

## Java Annotation Based Projects

If your team writes Spring MVC, JAX-RS, or MicroProfile controllers with Swagger/OpenAPI annotations, you can generate the spec at build time and commit it. The scanner diffs the committed spec like any other file.

### Annotating Controllers and Models

```java
@Tag(name = "products")
@Path("/products")
public class ProductResource {

    @Operation(summary = "List all products")
    @ApiResponse(responseCode = "200", description = "Product list",
        content = @Content(schema = @Schema(implementation = ProductListResponse.class)))
    @GET
    public Response list(@QueryParam("category") String category) { ... }

    @Operation(summary = "Create a product")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @POST
    public Response create(@Valid @RequestBody ProductRequest request) { ... }
}
```

```java
@Schema(description = "A product in the catalog")
public class Product {

    @Schema(description = "Unique product ID", required = true)
    private String id;

    @Schema(description = "Display name", required = true)
    private String name;

    @Schema(description = "Price in default currency")
    private double price;
}
```

### Maven Plugin Configuration

Add the `generate-spec` goal to generate the spec from annotations:

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
          <package>com.example.model</package>
        </packagesToScan>
        <outputFile>${project.basedir}/docs/openapi.yaml</outputFile>
      </configuration>
    </execution>
  </executions>
</plugin>
```

During `mvn process-classes`, the plugin scans the configured packages for annotations and writes the spec to `outputFile`.

### Point Contract Guardian at the Generated File

```yaml
sources:
  rest:
    paths:
      - "docs/openapi.yaml"     # same path as outputFile above
    baseline: branch:main
```

### Developer Workflow

```
1. Developer modifies a controller or model.
2. mvn package → generate-spec runs → docs/openapi.yaml is updated.
3. Developer commits docs/openapi.yaml alongside the code change.
4. CI runs: contract-guardian scan --diff origin/main..HEAD
5. Contract Guardian diffs docs/openapi.yaml against the baseline.
6. Breaking changes fail the build; safe changes pass.
```

The committed spec file is the single source of truth. Developers don't maintain it by hand — the build regenerates it on every compile.

### Running the Generator Manually

```bash
# Generate the spec only
mvn contract-guardian:generate-spec

# Skip generation when nothing changed
mvn package -Dcontractguardian.generateSpec.skip=true
```

---

## Breaking Change Rules

Each rule identifier can be placed under `breaking` (blocks the build) or `warning` (reported but does not block):

| Identifier | When it triggers | Default |
|---|---|---|
| `endpoint-removed` | An existing endpoint disappears from the spec | Breaking |
| `required-param-added` | A new required query, path, or header parameter is added | Breaking |
| `response-field-removed` | A field is removed from a response body schema | Breaking |
| `response-field-type-changed` | A response field changes type, e.g. `string` → `integer` | Breaking |
| `status-code-removed` | An existing response code (e.g. `200`, `404`) is removed | Breaking |
| `response-field-deprecated` | A response field is marked `deprecated: true` | Warning |
| `parameter-renamed` | A query or path parameter is renamed | Warning |

**Example — demote `endpoint-removed` to a warning instead of blocking:**

```yaml
rules:
  rest:
    breaking:
      - required-param-added
      - response-field-removed
      - response-field-type-changed
      - status-code-removed
    warning:
      - endpoint-removed          # demoted from breaking
      - response-field-deprecated
      - parameter-renamed
```

---

## Ignoring Paths

Use `ignore` to exclude entire API path prefixes from all checks. Useful for internal or admin endpoints that change often and are not part of the public contract:

```yaml
rules:
  rest:
    breaking:
      - endpoint-removed
      - required-param-added
    ignore:
      - "/internal/**"
      - "/admin/**"
      - "/actuator/**"
```

Patterns use glob syntax and are matched against the API path. For example, `/internal/**` would ignore `/internal/config`, `/internal/health`, etc.

---

## Common Scenarios

### Remove an endpoint — breaking

```yaml
# Before — both GET and POST exist
paths:
  /products:
    get: ...
    post: ...

# After — POST removed
paths:
  /products:
    get: ...
```

```
BREAKING  api/openapi/catalog-service.yaml
  Endpoint removed: POST /products — existing callers will break
  Fix: Restore the endpoint or coordinate removal with all consumers
```

**Safe fix — deprecate first:**

```yaml
paths:
  /products:
    post:
      deprecated: true
      description: "Deprecated. Use POST /v2/products instead."
```

Contract Guardian reports `deprecated: true` as a warning, not a blocking finding. Remove the endpoint in a later release once all callers have migrated.

---

### Add a required query parameter — breaking

```yaml
# After — new required parameter added
parameters:
  - name: currency
    in: query
    required: true     # BREAKING: existing callers don't send this
    schema:
      type: string
```

```
BREAKING  api/openapi/catalog-service.yaml
  Required parameter added: currency (query) on GET /products — existing callers do not send this parameter
  Fix: Make the parameter optional (required: false) or provide a default value
```

**Safe fix:** Add parameters as optional with a sensible default:

```yaml
parameters:
  - name: currency
    in: query
    required: false
    schema:
      type: string
      default: "USD"
```

---

### Remove a response field — breaking

```yaml
# Before — Product has a "sku" field
Product:
  properties:
    id:   {type: string}
    name: {type: string}
    sku:  {type: string}

# After — "sku" removed
Product:
  properties:
    id:   {type: string}
    name: {type: string}
```

```
BREAKING  api/openapi/catalog-service.yaml
  Response field removed: sku (GET /products → 200 → items) — consumers reading this field will break
  Fix: Deprecate the field first, then remove it once consumers stop using it
```

---

### Add a new endpoint — always safe

```
INFO  api/openapi/catalog-service.yaml
  New endpoint: DELETE /products/{id}
```

Adding endpoints is always safe — no existing client knows about them, so no client can break. This is reported as INFO and never blocks the build.

---

## Tips

- **Use semantic versioning for APIs.** When you need a breaking change, introduce a new version (`/v2/products`) rather than modifying the existing contract.
- **Deprecate before removing.** Mark fields and parameters `deprecated: true` for at least one release cycle before removing them. Contract Guardian reports these as warnings so you have a record.
- **Commit the generated spec.** Whether written by hand or generated from annotations, the committed file is what git diffs. Developers must regenerate and commit it whenever annotations change.
- **Use `ignore` for internal paths.** Admin and internal endpoints change frequently. Exclude them from breaking-change enforcement to avoid noise.
