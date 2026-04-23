package io.contractguardian.maven;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an {@link OpenAPI} model from JAX-RS resource classes.
 *
 * <p>Supports both Jakarta EE ({@code jakarta.ws.rs}) and legacy Java EE
 * ({@code javax.ws.rs}) namespaces. Documentation annotations are read from
 * <em>MicroProfile OpenAPI</em> ({@code org.eclipse.microprofile.openapi.annotations})
 * first, falling back to <em>Swagger v3</em> ({@code io.swagger.v3.oas.annotations})
 * when MicroProfile annotations are absent. All annotation access is done via reflection
 * to avoid compile-time dependencies on either framework.
 *
 * <p>Schema generation:
 * <ol>
 *   <li>If {@code @APIResponse} carries {@code content = @Content(schema = @Schema(implementation = Foo.class))},
 *       that class drives the schema.</li>
 *   <li>Otherwise a schema is inferred from the method return type (when not {@code Response}
 *       or another opaque JAX-RS / framework type).</li>
 *   <li>Referenced domain classes are introspected recursively: their fields become object
 *       properties, and {@code @Schema(description = ...)} on fields is honoured.</li>
 * </ol>
 */
public class JaxRsOpenApiReader {

    // JAX-RS path annotations (both namespace variants)
    private static final Set<String> PATH_ANNOTATIONS = Set.of(
            "jakarta.ws.rs.Path",
            "javax.ws.rs.Path"
    );

    // JAX-RS HTTP method annotations → HTTP verb
    private static final Map<String, String> HTTP_METHOD_ANNOTATIONS;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        for (final String ns : List.of("jakarta.ws.rs", "javax.ws.rs")) {
            m.put(ns + ".GET",     "GET");
            m.put(ns + ".POST",    "POST");
            m.put(ns + ".PUT",     "PUT");
            m.put(ns + ".DELETE",  "DELETE");
            m.put(ns + ".PATCH",   "PATCH");
            m.put(ns + ".HEAD",    "HEAD");
            m.put(ns + ".OPTIONS", "OPTIONS");
        }
        HTTP_METHOD_ANNOTATIONS = Collections.unmodifiableMap(m);
    }

    // JAX-RS parameter annotations → OpenAPI "in" location
    private static final Map<String, String> PARAM_IN_ANNOTATIONS;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        for (final String ns : List.of("jakarta.ws.rs", "javax.ws.rs")) {
            m.put(ns + ".PathParam",   "path");
            m.put(ns + ".QueryParam",  "query");
            m.put(ns + ".HeaderParam", "header");
        }
        PARAM_IN_ANNOTATIONS = Collections.unmodifiableMap(m);
    }

    // Annotations that mark a method parameter as NOT the entity body
    private static final Set<String> NON_BODY_PARAM_ANNOTATIONS = Set.of(
            "jakarta.ws.rs.PathParam",    "javax.ws.rs.PathParam",
            "jakarta.ws.rs.QueryParam",   "javax.ws.rs.QueryParam",
            "jakarta.ws.rs.HeaderParam",  "javax.ws.rs.HeaderParam",
            "jakarta.ws.rs.CookieParam",  "javax.ws.rs.CookieParam",
            "jakarta.ws.rs.FormParam",    "javax.ws.rs.FormParam",
            "jakarta.ws.rs.MatrixParam",  "javax.ws.rs.MatrixParam",
            "jakarta.ws.rs.core.Context", "javax.ws.rs.core.Context",
            "jakarta.inject.Inject",      "javax.inject.Inject",
            "org.jboss.resteasy.reactive.RestForm"
    );

    // MicroProfile OpenAPI annotation names
    private static final String MP_OPERATION    = "org.eclipse.microprofile.openapi.annotations.Operation";
    private static final String MP_RESPONSE     = "org.eclipse.microprofile.openapi.annotations.responses.APIResponse";
    private static final String MP_RESPONSES    = "org.eclipse.microprofile.openapi.annotations.responses.APIResponses";
    private static final String MP_REQUEST_BODY = "org.eclipse.microprofile.openapi.annotations.parameters.RequestBody";
    private static final String MP_TAG          = "org.eclipse.microprofile.openapi.annotations.tags.Tag";
    private static final String MP_TAGS         = "org.eclipse.microprofile.openapi.annotations.tags.Tags";
    private static final String MP_SCHEMA       = "org.eclipse.microprofile.openapi.annotations.media.Schema";

    // Swagger v3 annotation names (fallback)
    private static final String SW_OPERATION    = "io.swagger.v3.oas.annotations.Operation";
    private static final String SW_RESPONSE     = "io.swagger.v3.oas.annotations.responses.ApiResponse";
    private static final String SW_RESPONSES    = "io.swagger.v3.oas.annotations.responses.ApiResponses";
    private static final String SW_REQUEST_BODY = "io.swagger.v3.oas.annotations.parameters.RequestBody";
    private static final String SW_TAG          = "io.swagger.v3.oas.annotations.tags.Tag";
    private static final String SW_TAGS         = "io.swagger.v3.oas.annotations.tags.Tags";
    private static final String SW_SCHEMA       = "io.swagger.v3.oas.annotations.media.Schema";

    /**
     * Class name prefixes for framework / JDK types that should NOT produce component schemas.
     * These are checked ONLY after all primitive and well-known JDK types have been handled,
     * so e.g. {@code java.lang.String} is mapped to {@code {type: string}} before this guard runs.
     */
    private static final List<String> OPAQUE_TYPE_PREFIXES = List.of(
            "jakarta.ws.rs.", "javax.ws.rs.",
            "jakarta.servlet.", "javax.servlet.",
            "org.eclipse.microprofile.",
            "io.swagger.",
            "io.quarkus.",
            "org.jboss.",
            "io.vertx.",
            "io.smallrye."
    );

    // --- instance state (reset on each read() call) ---
    private OpenAPI openAPI;
    private final Set<String> registeredSchemas = new LinkedHashSet<>();

    /**
     * Builds an {@link OpenAPI} model from the given JAX-RS resource classes.
     *
     * @param resourceClasses the set of {@code @Path}-annotated classes to read
     * @return the populated OpenAPI model
     */
    public OpenAPI read(final Set<Class<?>> resourceClasses) {
        openAPI = new OpenAPI()
                .info(new Info().title("API").version("1.0.0"))
                .paths(new Paths());
        registeredSchemas.clear();

        for (final Class<?> clazz : resourceClasses) {
            processClass(clazz);
        }

        return openAPI;
    }

    // -------------------------------------------------------------------------
    // Class and method processing
    // -------------------------------------------------------------------------

    private void processClass(final Class<?> clazz) {
        final String classPath = extractJaxRsPath(clazz.getAnnotations());
        applyClassLevelTags(clazz);

        for (final Method method : clazz.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            processMethod(method, classPath);
        }
    }

    private void processMethod(final Method method, final String classPath) {
        String httpMethod = null;
        String methodPath = "";

        for (final Annotation ann : method.getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (HTTP_METHOD_ANNOTATIONS.containsKey(name)) {
                httpMethod = HTTP_METHOD_ANNOTATIONS.get(name);
            } else if (PATH_ANNOTATIONS.contains(name)) {
                methodPath = extractPathValue(ann);
            }
        }

        if (httpMethod == null) {
            return;
        }

        final String fullPath = joinPaths(classPath, methodPath);
        final PathItem pathItem = openAPI.getPaths().computeIfAbsent(fullPath, k -> new PathItem());
        final io.swagger.v3.oas.models.Operation operation = buildOperation(method);
        setOperation(pathItem, httpMethod, operation);
    }

    private io.swagger.v3.oas.models.Operation buildOperation(final Method method) {
        final io.swagger.v3.oas.models.Operation operation = new io.swagger.v3.oas.models.Operation();

        applyOperationDocs(method, operation);

        final List<Parameter> parameters = extractParameters(method);
        if (!parameters.isEmpty()) {
            operation.setParameters(parameters);
        }

        final RequestBody requestBody = extractRequestBody(method);
        if (requestBody != null) {
            operation.setRequestBody(requestBody);
        }

        operation.setResponses(extractApiResponses(method));
        return operation;
    }

    // -------------------------------------------------------------------------
    // @Operation
    // -------------------------------------------------------------------------

    private void applyOperationDocs(
            final Method method, final io.swagger.v3.oas.models.Operation operation) {
        for (final Annotation ann : method.getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (MP_OPERATION.equals(name) || SW_OPERATION.equals(name)) {
                final String summary     = invokeString(ann, "summary");
                final String description = invokeString(ann, "description");
                final String operationId = invokeString(ann, "operationId");
                final String[] tags      = invokeStringArray(ann, "tags");

                if (!summary.isBlank())     operation.setSummary(summary);
                if (!description.isBlank()) operation.setDescription(description);
                if (!operationId.isBlank()) operation.setOperationId(operationId);
                for (final String tag : tags) {
                    if (!tag.isBlank()) operation.addTagsItem(tag);
                }
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Parameters
    // -------------------------------------------------------------------------

    private List<Parameter> extractParameters(final Method method) {
        final List<Parameter> parameters = new ArrayList<>();
        for (final java.lang.reflect.Parameter param : method.getParameters()) {
            for (final Annotation ann : param.getAnnotations()) {
                final String in = PARAM_IN_ANNOTATIONS.get(ann.annotationType().getName());
                if (in == null) continue;
                final String name = invokeString(ann, "value");
                parameters.add(new Parameter()
                        .in(in)
                        .name(name.isBlank() ? param.getName() : name)
                        .required("path".equals(in))
                        .schema(new Schema<>().type("string")));
                break;
            }
        }
        return parameters;
    }

    // -------------------------------------------------------------------------
    // Request body
    // -------------------------------------------------------------------------

    private RequestBody extractRequestBody(final Method method) {
        for (final Annotation ann : method.getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (MP_REQUEST_BODY.equals(name) || SW_REQUEST_BODY.equals(name)) {
                return buildRequestBodyFromAnnotation(ann, method);
            }
        }

        final String consumes = extractConsumes(method);
        for (final java.lang.reflect.Parameter param : method.getParameters()) {
            if (!isBodyParameter(param)) continue;
            final Schema<?> schema = javaTypeToSchema(param.getParameterizedType());
            if (schema == null) continue;
            return new RequestBody().required(true)
                    .content(new Content().addMediaType(consumes, new MediaType().schema(schema)));
        }

        return null;
    }

    private RequestBody buildRequestBodyFromAnnotation(
            final Annotation ann, final Method method) {
        final boolean required = invokeBoolean(ann, "required", true);
        final Object[] contentAnns = invokeArray(ann, "content");
        if (contentAnns.length > 0) {
            final Content content = buildContent((Annotation) contentAnns[0], method);
            if (content != null) {
                return new RequestBody().required(required).content(content);
            }
        }
        final String consumes = extractConsumes(method);
        return new RequestBody().required(required)
                .content(new Content().addMediaType(consumes,
                        new MediaType().schema(new Schema<>().type("object"))));
    }

    private boolean isBodyParameter(final java.lang.reflect.Parameter param) {
        for (final Annotation ann : param.getAnnotations()) {
            if (NON_BODY_PARAM_ANNOTATIONS.contains(ann.annotationType().getName())) {
                return false;
            }
        }
        return !isOpaqueType(param.getType());
    }

    // -------------------------------------------------------------------------
    // Responses
    // -------------------------------------------------------------------------

    private ApiResponses extractApiResponses(final Method method) {
        final ApiResponses responses = new ApiResponses();

        for (final Annotation ann : method.getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (MP_RESPONSE.equals(name) || SW_RESPONSE.equals(name)) {
                addResponse(ann, method, responses);
            } else if (MP_RESPONSES.equals(name) || SW_RESPONSES.equals(name)) {
                try {
                    final Object[] nested = (Object[]) ann.annotationType()
                            .getMethod("value").invoke(ann);
                    for (final Object item : nested) {
                        addResponse((Annotation) item, method, responses);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (responses.isEmpty()) {
            final ApiResponse defaultResponse = new ApiResponse().description("OK");
            final Schema<?> returnSchema = inferReturnSchema(method);
            if (returnSchema != null) {
                defaultResponse.content(new Content().addMediaType(
                        extractProduces(method), new MediaType().schema(returnSchema)));
            }
            responses.addApiResponse("200", defaultResponse);
        }

        return responses;
    }

    private void addResponse(
            final Annotation ann, final Method method, final ApiResponses responses) {
        final String code        = invokeString(ann, "responseCode");
        final String description = invokeString(ann, "description");
        final ApiResponse response = new ApiResponse().description(description);

        Content content = null;
        final Object[] contentAnns = invokeArray(ann, "content");
        if (contentAnns.length > 0) {
            content = buildContent((Annotation) contentAnns[0], method);
        }

        // Infer from return type for success responses when no content annotation provided
        if (content == null && isSuccessCode(code)) {
            final Schema<?> inferred = inferReturnSchema(method);
            if (inferred != null) {
                content = new Content().addMediaType(
                        extractProduces(method), new MediaType().schema(inferred));
            }
        }

        if (content != null) response.setContent(content);
        responses.addApiResponse(code.isBlank() ? "200" : code, response);
    }

    private Content buildContent(final Annotation contentAnn, final Method method) {
        String mediaType = invokeString(contentAnn, "mediaType");
        if (mediaType.isBlank()) {
            mediaType = method != null ? extractProduces(method) : "application/json";
        }

        Schema<?> schema = null;
        try {
            final Annotation schemaAnn = (Annotation) contentAnn.annotationType()
                    .getMethod("schema").invoke(contentAnn);
            schema = buildSchemaFromAnnotation(schemaAnn);
        } catch (Exception ignored) {
        }

        return schema != null
                ? new Content().addMediaType(mediaType, new MediaType().schema(schema))
                : null;
    }

    private Schema<?> buildSchemaFromAnnotation(final Annotation schemaAnn) {
        if (schemaAnn == null) return null;
        try {
            final Class<?> impl = (Class<?>) schemaAnn.annotationType()
                    .getMethod("implementation").invoke(schemaAnn);
            if (impl != null && impl != void.class && impl != Void.class) {
                return schemaRefFor(impl);
            }
        } catch (Exception ignored) {
        }
        final String type = invokeString(schemaAnn, "type");
        return type.isBlank() ? null : new Schema<>().type(type);
    }

    private Schema<?> inferReturnSchema(final Method method) {
        final Type returnType = method.getGenericReturnType();
        if (returnType == void.class || returnType == Void.class) return null;
        return javaTypeToSchema(returnType);
    }

    // -------------------------------------------------------------------------
    // Schema: Java type → OpenAPI Schema
    // -------------------------------------------------------------------------

    /**
     * Maps a Java {@link Type} to an OpenAPI {@link Schema}.
     *
     * <p>Well-known JDK scalar types (String, Integer, dates, UUID, etc.) are mapped to
     * their OpenAPI primitives. Generic collection and map types are handled via their
     * type parameters. All other domain types are registered in {@code components/schemas}
     * and referenced via {@code $ref}.
     *
     * @param type the Java type to map; may be a {@link Class} or {@link ParameterizedType}
     * @return the corresponding schema, or {@code null} when the type should be omitted
     */
    @SuppressWarnings("rawtypes")
    Schema<?> javaTypeToSchema(final Type type) {
        if (type instanceof Class<?> clazz) {
            return classToSchema(clazz);
        }

        if (type instanceof ParameterizedType pt) {
            final Type raw = pt.getRawType();
            if (!(raw instanceof Class<?> rawClass)) return null;

            if (Iterable.class.isAssignableFrom(rawClass)) {
                final Schema<?> itemSchema = javaTypeToSchema(pt.getActualTypeArguments()[0]);
                return itemSchema != null ? new ArraySchema().items(itemSchema) : new ArraySchema();
            }
            if (Map.class.isAssignableFrom(rawClass)) {
                final Schema<?> valueSchema = javaTypeToSchema(pt.getActualTypeArguments()[1]);
                final Schema<Object> mapSchema = new Schema<>().type("object");
                if (valueSchema != null) mapSchema.additionalProperties(valueSchema);
                return mapSchema;
            }
            if (rawClass.getName().equals("java.util.Optional")) {
                return javaTypeToSchema(pt.getActualTypeArguments()[0]);
            }
            // Other parameterized types — treat as their raw class
            return classToSchema(rawClass);
        }

        return null;
    }

    /**
     * Maps a plain Java class to an OpenAPI schema.
     *
     * <p><strong>Important</strong>: well-known JDK types (String, Integer, dates …) are
     * resolved <em>before</em> the opaque-type guard so that e.g. {@code java.lang.String}
     * is correctly emitted as {@code {type: string}} rather than being silently dropped.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> classToSchema(final Class<?> clazz) {
        if (clazz == null || clazz == void.class || clazz == Void.class) return null;

        // --- scalar types (checked first, before any opaque-type guard) ---
        if (clazz == String.class)
            return new Schema<>().type("string");
        if (clazz == Boolean.class || clazz == boolean.class)
            return new Schema<>().type("boolean");
        if (clazz == Integer.class || clazz == int.class
                || clazz == Short.class  || clazz == short.class
                || clazz == Byte.class   || clazz == byte.class)
            return new Schema<>().type("integer").format("int32");
        if (clazz == Long.class || clazz == long.class)
            return new Schema<>().type("integer").format("int64");
        if (clazz == Float.class || clazz == float.class)
            return new Schema<>().type("number").format("float");
        if (clazz == Double.class || clazz == double.class)
            return new Schema<>().type("number").format("double");
        if (clazz == Character.class || clazz == char.class)
            return new Schema<>().type("string").maxLength(1);

        // --- well-known java.* types ---
        switch (clazz.getName()) {
            case "java.math.BigDecimal", "java.math.BigInteger"
                    -> { return new Schema<>().type("number"); }
            case "java.time.LocalDate"
                    -> { return new Schema<>().type("string").format("date"); }
            case "java.time.LocalDateTime", "java.time.Instant",
                 "java.time.OffsetDateTime", "java.time.ZonedDateTime"
                    -> { return new Schema<>().type("string").format("date-time"); }
            case "java.util.UUID"
                    -> { return new Schema<>().type("string").format("uuid"); }
            case "java.net.URI", "java.net.URL"
                    -> { return new Schema<>().type("string").format("uri"); }
            case "java.lang.Object"
                    -> { return new Schema<>(); }  // free-form object
        }

        // --- enum ---
        if (clazz.isEnum()) {
            final Schema<String> schema = new Schema<String>().type("string");
            for (final Object constant : clazz.getEnumConstants()) {
                schema.addEnumItemObject(constant.toString());
            }
            return schema;
        }

        // --- array ---
        if (clazz.isArray()) {
            final Schema<?> items = classToSchema(clazz.getComponentType());
            return items != null ? new ArraySchema().items(items) : new ArraySchema();
        }

        // --- raw collection / map (no generic info available) ---
        if (Iterable.class.isAssignableFrom(clazz)) return new ArraySchema();
        if (Map.class.isAssignableFrom(clazz))      return new Schema<>().type("object");

        // --- framework/library types that should not produce schemas ---
        if (isOpaqueType(clazz)) return null;

        // --- domain type: register as component and return $ref ---
        return schemaRefFor(clazz);
    }

    /**
     * Returns {@code true} for JAX-RS, servlet, Quarkus, or other library types
     * whose internals we should not introspect for schema generation.
     *
     * <p>This method is intentionally checked <em>after</em> all scalar / well-known
     * JDK types have been handled, so {@code java.lang.String} etc. are never rejected here.
     */
    private boolean isOpaqueType(final Class<?> clazz) {
        final String name = clazz.getName();
        for (final String prefix : OPAQUE_TYPE_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Component schema registration and field introspection
    // -------------------------------------------------------------------------

    private Schema<?> schemaRefFor(final Class<?> clazz) {
        ensureSchemaRegistered(clazz);
        return new Schema<>().$ref("#/components/schemas/" + clazz.getSimpleName());
    }

    private void ensureSchemaRegistered(final Class<?> clazz) {
        final String simpleName = clazz.getSimpleName();
        if (registeredSchemas.contains(simpleName)) return;

        // Reserve the name first to break circular references
        registeredSchemas.add(simpleName);
        if (openAPI.getComponents() == null) openAPI.setComponents(new Components());
        openAPI.getComponents().addSchemas(simpleName, new Schema<>().type("object"));

        // Then build the full schema (may recurse for nested types)
        openAPI.getComponents().addSchemas(simpleName, generateObjectSchema(clazz));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> generateObjectSchema(final Class<?> clazz) {
        final Schema<Object> schema = new Schema<>().type("object");

        for (final Annotation ann : clazz.getAnnotations()) {
            final String annName = ann.annotationType().getName();
            if (MP_SCHEMA.equals(annName) || SW_SCHEMA.equals(annName)) {
                final String desc = invokeString(ann, "description");
                if (!desc.isBlank()) schema.setDescription(desc);
                break;
            }
        }

        final Map<String, Schema> properties = new LinkedHashMap<>();
        final List<String> requiredFields = new ArrayList<>();

        for (final Field field : getAllFields(clazz)) {
            if (Modifier.isStatic(field.getModifiers())
                    || Modifier.isTransient(field.getModifiers())
                    || field.isSynthetic()) {
                continue;
            }

            Schema<?> propSchema = javaTypeToSchema(field.getGenericType());

            for (final Annotation ann : field.getAnnotations()) {
                final String annName = ann.annotationType().getName();
                if (!MP_SCHEMA.equals(annName) && !SW_SCHEMA.equals(annName)) continue;

                final String desc = invokeString(ann, "description");
                if (propSchema != null && !desc.isBlank()) propSchema.setDescription(desc);

                final boolean required = invokeBoolean(ann, "required", false);
                if (required) requiredFields.add(field.getName());
                break;
            }

            if (propSchema != null) {
                properties.put(field.getName(), propSchema);
            }
        }

        if (!properties.isEmpty()) schema.setProperties(properties);
        if (!requiredFields.isEmpty()) schema.setRequired(requiredFields);
        return schema;
    }

    private List<Field> getAllFields(final Class<?> clazz) {
        final List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (final Field f : current.getDeclaredFields()) {
                if (!f.isSynthetic()) fields.add(f);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    // -------------------------------------------------------------------------
    // Tags
    // -------------------------------------------------------------------------

    private void applyClassLevelTags(final Class<?> clazz) {
        for (final Annotation ann : clazz.getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (MP_TAG.equals(name) || SW_TAG.equals(name)) {
                addTag(ann);
            } else if (MP_TAGS.equals(name) || SW_TAGS.equals(name)) {
                try {
                    final Object[] nested = (Object[]) ann.annotationType()
                            .getMethod("value").invoke(ann);
                    for (final Object item : nested) addTag((Annotation) item);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void addTag(final Annotation ann) {
        final String name        = invokeString(ann, "name");
        final String description = invokeString(ann, "description");
        if (name.isBlank()) return;
        if (openAPI.getTags() == null
                || openAPI.getTags().stream().noneMatch(t -> name.equals(t.getName()))) {
            final Tag tag = new Tag().name(name);
            if (!description.isBlank()) tag.setDescription(description);
            openAPI.addTagsItem(tag);
        }
    }

    // -------------------------------------------------------------------------
    // JAX-RS helpers
    // -------------------------------------------------------------------------

    private void setOperation(
            final PathItem pathItem,
            final String httpMethod,
            final io.swagger.v3.oas.models.Operation operation) {
        switch (httpMethod) {
            case "GET"     -> pathItem.setGet(operation);
            case "POST"    -> pathItem.setPost(operation);
            case "PUT"     -> pathItem.setPut(operation);
            case "DELETE"  -> pathItem.setDelete(operation);
            case "PATCH"   -> pathItem.setPatch(operation);
            case "HEAD"    -> pathItem.setHead(operation);
            case "OPTIONS" -> pathItem.setOptions(operation);
            default        -> { /* unsupported — skip */ }
        }
    }

    private String extractJaxRsPath(final Annotation[] annotations) {
        for (final Annotation ann : annotations) {
            if (PATH_ANNOTATIONS.contains(ann.annotationType().getName())) {
                return extractPathValue(ann);
            }
        }
        return "";
    }

    private String extractPathValue(final Annotation ann) {
        final String v = invokeString(ann, "value");
        return v.isBlank() ? "" : v;
    }

    private String extractConsumes(final Method method) {
        return extractMediaType(method,
                "jakarta.ws.rs.Consumes", "javax.ws.rs.Consumes", "application/json");
    }

    private String extractProduces(final Method method) {
        return extractMediaType(method,
                "jakarta.ws.rs.Produces", "javax.ws.rs.Produces", "application/json");
    }

    private String extractMediaType(
            final Method method,
            final String jakartaAnn, final String javaxAnn,
            final String defaultValue) {
        for (final Annotation ann : method.getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (jakartaAnn.equals(name) || javaxAnn.equals(name)) {
                final String[] values = invokeStringArray(ann, "value");
                if (values.length > 0) return values[0];
            }
        }
        for (final Annotation ann : method.getDeclaringClass().getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (jakartaAnn.equals(name) || javaxAnn.equals(name)) {
                final String[] values = invokeStringArray(ann, "value");
                if (values.length > 0) return values[0];
            }
        }
        return defaultValue;
    }

    private boolean isSuccessCode(final String code) {
        if (code.isBlank()) return true;
        try {
            final int status = Integer.parseInt(code);
            return status >= 200 && status < 300;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String joinPaths(final String base, final String method) {
        final String left  = base.endsWith("/")     ? base.substring(0, base.length() - 1) : base;
        final String right = method.startsWith("/") ? method : (method.isEmpty() ? "" : "/" + method);
        final String joined = left + right;
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    // -------------------------------------------------------------------------
    // Reflection utilities
    // -------------------------------------------------------------------------

    private String invokeString(final Annotation ann, final String attribute) {
        try {
            return (String) ann.annotationType().getMethod(attribute).invoke(ann);
        } catch (Exception e) {
            return "";
        }
    }

    private String[] invokeStringArray(final Annotation ann, final String attribute) {
        try {
            return (String[]) ann.annotationType().getMethod(attribute).invoke(ann);
        } catch (Exception e) {
            return new String[0];
        }
    }

    private Object[] invokeArray(final Annotation ann, final String attribute) {
        try {
            return (Object[]) ann.annotationType().getMethod(attribute).invoke(ann);
        } catch (Exception e) {
            return new Object[0];
        }
    }

    private boolean invokeBoolean(
            final Annotation ann, final String attribute, final boolean defaultValue) {
        try {
            return (boolean) ann.annotationType().getMethod(attribute).invoke(ann);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
