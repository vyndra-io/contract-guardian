package io.contractguardian.maven;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scans compiled project classes for OpenAPI annotations ({@code @Operation},
 * {@code @Schema}, {@code @Tag}, etc.) and writes an {@code openapi.yaml} spec file.
 *
 * <p>Supports Spring MVC, JAX-RS (Jakarta EE, Java EE), and Quarkus. The framework is
 * detected automatically from the project classpath, or can be set explicitly via
 * {@code <framework>spring</framework>} or {@code <framework>jax_rs</framework>}.
 *
 * <p>The generated file should be committed to source control. Contract Guardian's
 * {@code validate} goal will then detect breaking changes via git diff whenever
 * annotations are modified and the spec is regenerated.
 *
 * <p>Example configuration:
 * <pre>{@code
 * <plugin>
 *   <groupId>io.contractguardian</groupId>
 *   <artifactId>contract-guardian-maven-plugin</artifactId>
 *   <executions>
 *     <execution>
 *       <goals><goal>generate-spec</goal></goals>
 *     </execution>
 *   </executions>
 *   <configuration>
 *     <packagesToScan>
 *       <package>com.example.api</package>
 *     </packagesToScan>
 *   </configuration>
 * </plugin>
 * }</pre>
 */
@Mojo(
        name = "generate-spec",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE
)
public class GenerateSpecMojo extends AbstractMojo {

    /**
     * The current Maven project, used to resolve the compile classpath and output directory.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Java packages to scan for OpenAPI annotations.
     * At least one package must be specified.
     */
    @Parameter(property = "contractguardian.packagesToScan", required = true)
    private List<String> packagesToScan;

    /**
     * Path where the generated OpenAPI YAML spec will be written.
     * Defaults to {@code src/main/resources/openapi.yaml} so the file is
     * committed alongside the source code and tracked by git.
     */
    @Parameter(
            property = "contractguardian.specOutputFile",
            defaultValue = "${project.basedir}/src/main/resources/openapi.yaml"
    )
    private File outputFile;

    /**
     * Web framework used by the project.
     * Accepted values: {@code auto} (default), {@code spring}, {@code jax_rs}.
     *
     * <p>{@code auto} detects the framework by checking whether Spring or JAX-RS
     * annotation classes are present on the compile classpath.
     */
    @Parameter(property = "contractguardian.framework", defaultValue = "auto")
    private String framework;

    /**
     * Skip spec generation entirely.
     */
    @Parameter(property = "contractguardian.generateSpec.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Contract Guardian: spec generation skipped.");
            return;
        }

        if (packagesToScan == null || packagesToScan.isEmpty()) {
            throw new MojoExecutionException(
                    "Contract Guardian: <packagesToScan> must list at least one package.");
        }

        final ClassLoader projectClassLoader = buildProjectClassLoader();
        final ClassLoader original = Thread.currentThread().getContextClassLoader();

        try {
            Thread.currentThread().setContextClassLoader(projectClassLoader);
            final FrameworkType resolvedFramework = resolveFramework(projectClassLoader);
            getLog().info("Contract Guardian: framework = " + resolvedFramework
                    + ", scanning packages " + packagesToScan + "...");

            final OpenAPI openAPI = generateSpec(resolvedFramework, projectClassLoader);
            writeSpec(openAPI);
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Contract Guardian: failed to generate OpenAPI spec", e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * Resolves the framework type, performing auto-detection when {@link #framework}
     * is set to {@code auto} (or is blank).
     *
     * @param classLoader the project classloader used for auto-detection
     * @return the resolved framework type
     * @throws MojoExecutionException if the configured value is not a valid framework name
     */
    private FrameworkType resolveFramework(final ClassLoader classLoader) throws MojoExecutionException {
        if (framework == null || framework.isBlank() || "auto".equalsIgnoreCase(framework)) {
            return new FrameworkDetector().detect(classLoader);
        }
        try {
            return FrameworkType.valueOf(framework.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(
                    "Contract Guardian: unknown framework '" + framework
                    + "'. Valid values: auto, spring, jax_rs");
        }
    }

    /**
     * Scans for resource classes and generates the OpenAPI model using the
     * appropriate reader for the given framework.
     *
     * @param frameworkType   the resolved framework
     * @param classLoader     the project classloader
     * @return the populated OpenAPI model
     * @throws MojoExecutionException if no resource classes are found or reading fails
     */
    private OpenAPI generateSpec(
            final FrameworkType frameworkType, final ClassLoader classLoader)
            throws MojoExecutionException {

        final File outputDirectory = new File(project.getBuild().getOutputDirectory());
        final Set<Class<?>> resourceClasses = new ResourceClassScanner()
                .scan(outputDirectory, packagesToScan, frameworkType, classLoader);

        if (resourceClasses.isEmpty()) {
            throw new MojoExecutionException(
                    "Contract Guardian: no " + frameworkType + " resource classes found in packages "
                    + packagesToScan + ". Ensure the project is compiled and the packages are correct.");
        }

        getLog().info("Contract Guardian: found " + resourceClasses.size() + " resource class(es): "
                + resourceClasses.stream().map(Class::getSimpleName).collect(Collectors.joining(", ")));

        return frameworkType == FrameworkType.SPRING
                ? new SpringOpenApiReader().read(resourceClasses)
                : new JaxRsOpenApiReader().read(resourceClasses);
    }

    /**
     * Serialises the given {@link OpenAPI} model to YAML and writes it to
     * {@link #outputFile}, creating any missing parent directories.
     *
     * @param openAPI the model to serialise
     * @throws MojoExecutionException if the file cannot be written
     */
    private void writeSpec(final OpenAPI openAPI) throws MojoExecutionException {
        try {
            final File parent = outputFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            final String yaml = Yaml.pretty(openAPI);
            Files.writeString(outputFile.toPath(), yaml);
            getLog().info("Contract Guardian: OpenAPI spec written to " + outputFile);
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Contract Guardian: failed to write spec to " + outputFile, e);
        }
    }

    /**
     * Builds a {@link URLClassLoader} containing the project's compiled output
     * directory and all compile-scope dependencies, parented by the current
     * thread's classloader so Maven plugin classes remain visible.
     *
     * @return classloader covering the project's compile classpath
     * @throws MojoExecutionException if the classpath elements cannot be resolved
     */
    private ClassLoader buildProjectClassLoader() throws MojoExecutionException {
        try {
            final List<String> elements = project.getCompileClasspathElements();
            final URL[] urls = new URL[elements.size()];
            for (int i = 0; i < elements.size(); i++) {
                urls[i] = new File(elements.get(i)).toURI().toURL();
            }
            return new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Contract Guardian: failed to build project classloader", e);
        }
    }
}
