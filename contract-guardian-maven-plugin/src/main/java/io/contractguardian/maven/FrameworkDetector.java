package io.contractguardian.maven;

/**
 * Probes a classloader to determine which web framework the project uses.
 *
 * <p>Detection is based on the presence of well-known marker classes:
 * {@code org.springframework.stereotype.Controller} for Spring, and
 * {@code jakarta.ws.rs.Path} / {@code javax.ws.rs.Path} for JAX-RS.
 * Spring takes precedence when both are present.
 */
public class FrameworkDetector {

    private static final String SPRING_CONTROLLER = "org.springframework.stereotype.Controller";
    private static final String JAKARTA_PATH = "jakarta.ws.rs.Path";
    private static final String JAVAX_PATH = "javax.ws.rs.Path";

    /**
     * Detects the framework by probing the given classloader.
     * Falls back to {@link FrameworkType#JAX_RS} when neither framework is detected.
     *
     * @param classLoader the project classloader to probe
     * @return the resolved framework type; never {@code null}
     */
    public FrameworkType detect(final ClassLoader classLoader) {
        if (isPresent(SPRING_CONTROLLER, classLoader)) {
            return FrameworkType.SPRING;
        }
        if (isPresent(JAKARTA_PATH, classLoader) || isPresent(JAVAX_PATH, classLoader)) {
            return FrameworkType.JAX_RS;
        }
        return FrameworkType.JAX_RS;
    }

    private boolean isPresent(final String className, final ClassLoader classLoader) {
        try {
            classLoader.loadClass(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
