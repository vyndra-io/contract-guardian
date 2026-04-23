package io.contractguardian.maven;

import java.io.File;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans a compiled class output directory for web resource classes.
 *
 * <p>A class is considered a resource class when it carries a framework-specific
 * marker annotation at the class level:
 * <ul>
 *   <li>JAX-RS: {@code @Path} (both {@code jakarta.ws.rs} and {@code javax.ws.rs})</li>
 *   <li>Spring: {@code @RestController} or {@code @Controller}</li>
 * </ul>
 *
 * <p>Only classes whose package names are listed in {@code packagesToScan} (or
 * sub-packages thereof) are considered. Inner classes (names containing {@code $})
 * are skipped.
 */
public class ResourceClassScanner {

    private static final Set<String> JAX_RS_ANNOTATIONS = Set.of(
            "jakarta.ws.rs.Path",
            "javax.ws.rs.Path"
    );

    private static final Set<String> SPRING_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.stereotype.Controller"
    );

    /**
     * Scans the given output directory for resource classes belonging to the
     * specified packages.
     *
     * @param outputDirectory the compiled class directory (e.g. {@code target/classes})
     * @param packagesToScan  packages to include in the scan; sub-packages are included
     * @param frameworkType   determines which annotations mark a class as a resource
     * @param classLoader     used to load candidate classes for annotation inspection
     * @return discovered resource classes, in encounter order
     */
    public Set<Class<?>> scan(
            final File outputDirectory,
            final List<String> packagesToScan,
            final FrameworkType frameworkType,
            final ClassLoader classLoader) {

        final Set<String> targetAnnotations = frameworkType == FrameworkType.SPRING
                ? SPRING_ANNOTATIONS
                : JAX_RS_ANNOTATIONS;

        final Set<Class<?>> resourceClasses = new LinkedHashSet<>();

        for (final String pkg : packagesToScan) {
            final File packageDir = new File(outputDirectory, pkg.replace('.', File.separatorChar));
            collectFrom(packageDir, pkg, targetAnnotations, classLoader, resourceClasses);
        }

        return resourceClasses;
    }

    private void collectFrom(
            final File directory,
            final String packageName,
            final Set<String> targetAnnotations,
            final ClassLoader classLoader,
            final Set<Class<?>> result) {

        if (!directory.isDirectory()) {
            return;
        }

        final File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }

        for (final File entry : entries) {
            if (entry.isDirectory()) {
                collectFrom(entry, packageName + "." + entry.getName(),
                        targetAnnotations, classLoader, result);
            } else if (entry.getName().endsWith(".class") && !entry.getName().contains("$")) {
                final String className = packageName + "." + entry.getName().replace(".class", "");
                tryLoad(className, targetAnnotations, classLoader, result);
            }
        }
    }

    private void tryLoad(
            final String className,
            final Set<String> targetAnnotations,
            final ClassLoader classLoader,
            final Set<Class<?>> result) {
        try {
            final Class<?> clazz = classLoader.loadClass(className);
            if (isResourceClass(clazz, targetAnnotations)) {
                result.add(clazz);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            // class may reference unavailable types at load time — skip it
        }
    }

    private boolean isResourceClass(final Class<?> clazz, final Set<String> targetAnnotations) {
        for (final Annotation annotation : clazz.getAnnotations()) {
            if (targetAnnotations.contains(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }
}
