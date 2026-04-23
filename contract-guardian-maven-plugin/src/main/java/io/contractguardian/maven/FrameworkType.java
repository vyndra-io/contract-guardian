package io.contractguardian.maven;

/**
 * The web framework used by the project being scanned.
 *
 * <p>Determines which annotations are used to detect resource classes and how
 * endpoint metadata (path, HTTP method) is extracted when building the OpenAPI spec.
 */
public enum FrameworkType {

    /**
     * Spring MVC / Spring Boot.
     * Resource classes are annotated with {@code @RestController} or {@code @Controller}.
     * Endpoint paths and methods are extracted from {@code @RequestMapping} and its
     * shortcut variants ({@code @GetMapping}, {@code @PostMapping}, etc.).
     */
    SPRING,

    /**
     * JAX-RS — covers Jakarta EE, Java EE, and Quarkus.
     * Resource classes are annotated with {@code @Path}.
     * Endpoint metadata is read by the {@code swagger-jaxrs2} Reader.
     */
    JAX_RS,

    /**
     * Auto-detect the framework from the project's compile classpath.
     * Spring is preferred if both Spring and JAX-RS are present.
     */
    AUTO
}
