package io.contractguardian.maven;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an {@link OpenAPI} model from Spring MVC controller classes.
 *
 * <p>All Spring annotation types ({@code @RequestMapping}, {@code @GetMapping}, etc.) are
 * accessed via reflection so that Spring does not need to be on the plugin's compile
 * classpath. Swagger annotations ({@code @Operation}, {@code @Parameter}, etc.) are also
 * read via reflection to avoid classloader mismatches between the plugin and the project
 * under scan.
 *
 * <p>Supported Spring annotations:
 * <ul>
 *   <li>{@code @RequestMapping} (class and method level)</li>
 *   <li>{@code @GetMapping}, {@code @PostMapping}, {@code @PutMapping},
 *       {@code @DeleteMapping}, {@code @PatchMapping}</li>
 *   <li>{@code @PathVariable}, {@code @RequestParam} (method parameters)</li>
 *   <li>{@code @RequestBody} (method parameters)</li>
 * </ul>
 *
 * <p>Supported swagger annotations (optional, used for documentation when present):
 * <ul>
 *   <li>{@code @Operation} — summary, description, operationId, tags</li>
 *   <li>{@code @Parameter} — name, description, required, in</li>
 *   <li>{@code @ApiResponse} / {@code @ApiResponses} — response codes and descriptions</li>
 *   <li>{@code @Tag} / {@code @Tags} (class level) — tag metadata</li>
 * </ul>
 */
public class SpringOpenApiReader {

    // Spring annotation class names
    private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
    private static final String POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
    private static final String PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping";
    private static final String DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping";
    private static final String PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping";
    private static final String PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable";
    private static final String REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam";
    private static final String SPRING_REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";

    // Swagger annotation class names
    private static final String SWAGGER_OPERATION = "io.swagger.v3.oas.annotations.Operation";
    private static final String SWAGGER_PARAMETER = "io.swagger.v3.oas.annotations.Parameter";
    private static final String SWAGGER_TAG = "io.swagger.v3.oas.annotations.tags.Tag";
    private static final String SWAGGER_TAGS = "io.swagger.v3.oas.annotations.tags.Tags";
    private static final String SWAGGER_API_RESPONSE = "io.swagger.v3.oas.annotations.responses.ApiResponse";
    private static final String SWAGGER_API_RESPONSES = "io.swagger.v3.oas.annotations.responses.ApiResponses";

    private static final Map<String, String> SHORTCUT_TO_HTTP_METHOD = Map.of(
            GET_MAPPING, "GET",
            POST_MAPPING, "POST",
            PUT_MAPPING, "PUT",
            DELETE_MAPPING, "DELETE",
            PATCH_MAPPING, "PATCH"
    );

    /**
     * Builds an {@link OpenAPI} model from the given Spring controller classes.
     *
     * @param controllers the set of {@code @RestController} / {@code @Controller} classes to read
     * @return the populated OpenAPI model
     */
    public OpenAPI read(final Set<Class<?>> controllers) {
        final OpenAPI openAPI = new OpenAPI()
                .info(new Info().title("API").version("1.0.0"))
                .paths(new Paths());

        for (final Class<?> controller : controllers) {
            processController(controller, openAPI);
        }

        return openAPI;
    }

    private void processController(final Class<?> clazz, final OpenAPI openAPI) {
        final String classPath = extractPath(clazz.getAnnotations());
        applyClassLevelTags(clazz, openAPI);

        for (final Method method : clazz.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            processMethod(method, classPath, openAPI);
        }
    }

    private void processMethod(final Method method, final String classPath, final OpenAPI openAPI) {
        String httpMethod = null;
        String methodPath = "";

        for (final Annotation ann : method.getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (SHORTCUT_TO_HTTP_METHOD.containsKey(name)) {
                httpMethod = SHORTCUT_TO_HTTP_METHOD.get(name);
                methodPath = extractPath(new Annotation[]{ann});
                break;
            } else if (REQUEST_MAPPING.equals(name)) {
                httpMethod = extractRequestMappingMethod(ann);
                methodPath = extractPath(new Annotation[]{ann});
                break;
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

    /**
     * Extracts the first path value from any {@code @RequestMapping}-family annotation.
     * Tries the {@code value} attribute first, then {@code path}.
     */
    private String extractPath(final Annotation[] annotations) {
        for (final Annotation ann : annotations) {
            final String name = ann.annotationType().getName();
            if (REQUEST_MAPPING.equals(name) || SHORTCUT_TO_HTTP_METHOD.containsKey(name)) {
                final String[] values = invokeStringArray(ann, "value");
                if (values.length > 0 && !values[0].isBlank()) {
                    return values[0];
                }
                final String[] paths = invokeStringArray(ann, "path");
                if (paths.length > 0 && !paths[0].isBlank()) {
                    return paths[0];
                }
            }
        }
        return "";
    }

    /**
     * Extracts the HTTP method string from a {@code @RequestMapping} annotation.
     * Returns {@code "GET"} when no method is specified (Spring's default).
     */
    private String extractRequestMappingMethod(final Annotation ann) {
        try {
            final Object[] methods = (Object[]) ann.annotationType().getMethod("method").invoke(ann);
            if (methods.length > 0) {
                return methods[0].toString();
            }
        } catch (Exception ignored) {
        }
        return "GET";
    }

    private io.swagger.v3.oas.models.Operation buildOperation(final Method method) {
        final io.swagger.v3.oas.models.Operation operation = new io.swagger.v3.oas.models.Operation();

        applySwaggerOperation(method, operation);

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

    private void applySwaggerOperation(
            final Method method, final io.swagger.v3.oas.models.Operation operation) {
        for (final Annotation ann : method.getAnnotations()) {
            if (!SWAGGER_OPERATION.equals(ann.annotationType().getName())) {
                continue;
            }
            try {
                final Class<?> type = ann.annotationType();
                final String summary = (String) type.getMethod("summary").invoke(ann);
                final String description = (String) type.getMethod("description").invoke(ann);
                final String operationId = (String) type.getMethod("operationId").invoke(ann);
                final String[] tags = (String[]) type.getMethod("tags").invoke(ann);

                if (!summary.isBlank()) operation.setSummary(summary);
                if (!description.isBlank()) operation.setDescription(description);
                if (!operationId.isBlank()) operation.setOperationId(operationId);
                for (final String tag : tags) {
                    if (!tag.isBlank()) operation.addTagsItem(tag);
                }
            } catch (Exception ignored) {
            }
            break;
        }
    }

    private List<Parameter> extractParameters(final Method method) {
        final List<Parameter> parameters = new ArrayList<>();
        final java.lang.reflect.Parameter[] methodParams = method.getParameters();

        for (final java.lang.reflect.Parameter param : methodParams) {
            final Parameter openApiParam = tryBuildPathVariable(param);
            if (openApiParam != null) {
                mergeSwaggerParameter(param, openApiParam);
                parameters.add(openApiParam);
                continue;
            }
            final Parameter queryParam = tryBuildRequestParam(param);
            if (queryParam != null) {
                mergeSwaggerParameter(param, queryParam);
                parameters.add(queryParam);
            }
        }

        return parameters;
    }

    private Parameter tryBuildPathVariable(final java.lang.reflect.Parameter param) {
        for (final Annotation ann : param.getAnnotations()) {
            if (!PATH_VARIABLE.equals(ann.annotationType().getName())) {
                continue;
            }
            final String name = resolveParamName(ann, param.getName());
            return new Parameter()
                    .in("path")
                    .name(name)
                    .required(true)
                    .schema(new Schema<>().type("string"));
        }
        return null;
    }

    private Parameter tryBuildRequestParam(final java.lang.reflect.Parameter param) {
        for (final Annotation ann : param.getAnnotations()) {
            if (!REQUEST_PARAM.equals(ann.annotationType().getName())) {
                continue;
            }
            final String name = resolveParamName(ann, param.getName());
            final boolean required = invokeBoolean(ann, "required", false);
            return new Parameter()
                    .in("query")
                    .name(name)
                    .required(required)
                    .schema(new Schema<>().type("string"));
        }
        return null;
    }

    /**
     * Overlays swagger {@code @Parameter} documentation onto an already-built parameter.
     */
    private void mergeSwaggerParameter(
            final java.lang.reflect.Parameter param, final Parameter target) {
        for (final Annotation ann : param.getAnnotations()) {
            if (!SWAGGER_PARAMETER.equals(ann.annotationType().getName())) {
                continue;
            }
            try {
                final Class<?> type = ann.annotationType();
                final String description = (String) type.getMethod("description").invoke(ann);
                if (!description.isBlank()) target.setDescription(description);
            } catch (Exception ignored) {
            }
            break;
        }
    }

    private RequestBody extractRequestBody(final Method method) {
        for (final java.lang.reflect.Parameter param : method.getParameters()) {
            for (final Annotation ann : param.getAnnotations()) {
                if (SPRING_REQUEST_BODY.equals(ann.annotationType().getName())) {
                    final boolean required = invokeBoolean(ann, "required", true);
                    return new RequestBody()
                            .required(required)
                            .content(new Content().addMediaType(
                                    "application/json",
                                    new MediaType().schema(new Schema<>().type("object"))));
                }
            }
        }
        return null;
    }

    private ApiResponses extractApiResponses(final Method method) {
        final ApiResponses responses = new ApiResponses();

        for (final Annotation ann : method.getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (SWAGGER_API_RESPONSE.equals(name)) {
                addApiResponse(ann, responses);
            } else if (SWAGGER_API_RESPONSES.equals(name)) {
                try {
                    final Object[] nested = (Object[]) ann.annotationType()
                            .getMethod("value").invoke(ann);
                    for (final Object nested1 : nested) {
                        addApiResponse((Annotation) nested1, responses);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (responses.isEmpty()) {
            responses.addApiResponse("200", new ApiResponse().description("OK"));
        }

        return responses;
    }

    private void addApiResponse(final Annotation ann, final ApiResponses responses) {
        try {
            final Class<?> type = ann.annotationType();
            final String responseCode = (String) type.getMethod("responseCode").invoke(ann);
            final String description = (String) type.getMethod("description").invoke(ann);
            responses.addApiResponse(responseCode, new ApiResponse().description(description));
        } catch (Exception ignored) {
        }
    }

    private void applyClassLevelTags(final Class<?> clazz, final OpenAPI openAPI) {
        for (final Annotation ann : clazz.getAnnotations()) {
            final String name = ann.annotationType().getName();
            if (SWAGGER_TAG.equals(name)) {
                addTag(ann, openAPI);
            } else if (SWAGGER_TAGS.equals(name)) {
                try {
                    final Object[] nested = (Object[]) ann.annotationType()
                            .getMethod("value").invoke(ann);
                    for (final Object nested1 : nested) {
                        addTag((Annotation) nested1, openAPI);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void addTag(final Annotation ann, final OpenAPI openAPI) {
        try {
            final Class<?> type = ann.annotationType();
            final String name = (String) type.getMethod("name").invoke(ann);
            final String description = (String) type.getMethod("description").invoke(ann);
            if (!name.isBlank()) {
                final Tag tag = new Tag().name(name);
                if (!description.isBlank()) tag.setDescription(description);
                if (openAPI.getTags() == null || openAPI.getTags().stream()
                        .noneMatch(t -> name.equals(t.getName()))) {
                    openAPI.addTagsItem(tag);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void setOperation(
            final PathItem pathItem,
            final String httpMethod,
            final io.swagger.v3.oas.models.Operation operation) {
        switch (httpMethod.toUpperCase()) {
            case "GET" -> pathItem.setGet(operation);
            case "POST" -> pathItem.setPost(operation);
            case "PUT" -> pathItem.setPut(operation);
            case "DELETE" -> pathItem.setDelete(operation);
            case "PATCH" -> pathItem.setPatch(operation);
            case "HEAD" -> pathItem.setHead(operation);
            case "OPTIONS" -> pathItem.setOptions(operation);
            default -> { /* unsupported method — skip */ }
        }
    }

    /**
     * Joins a class-level base path and a method-level path into a single normalised path.
     * Ensures exactly one slash between segments and that the result starts with {@code /}.
     */
    private String joinPaths(final String base, final String method) {
        final String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        final String right = method.startsWith("/") ? method : (method.isEmpty() ? "" : "/" + method);
        final String joined = left + right;
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private String resolveParamName(final Annotation ann, final String fallback) {
        final String value = invokeString(ann, "value");
        if (!value.isBlank()) return value;
        final String name = invokeString(ann, "name");
        if (!name.isBlank()) return name;
        return fallback;
    }

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

    private boolean invokeBoolean(
            final Annotation ann, final String attribute, final boolean defaultValue) {
        try {
            return (boolean) ann.annotationType().getMethod(attribute).invoke(ann);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
