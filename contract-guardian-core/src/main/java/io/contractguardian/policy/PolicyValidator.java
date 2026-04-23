package io.contractguardian.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a {@link PolicyConfig} for structural correctness.
 */
public class PolicyValidator {

    /**
     * Validates the given policy configuration and returns any errors found.
     *
     * @param config the policy configuration to validate
     * @return a list of error messages, empty if valid
     */
    public List<String> validate(final PolicyConfig config) {
        final List<String> errors = new ArrayList<>();

        if (config.version() == null || config.version().isBlank()) {
            errors.add("Missing required field: version");
        } else if (!"1".equals(config.version())) {
            errors.add("Unsupported config version: " + config.version() + " (expected \"1\")");
        }

        if (config.sources().isEmpty()) {
            errors.add("At least one source must be defined");
        }

        for (final var entry : config.sources().entrySet()) {
            final SourceConfig source = entry.getValue();
            if (source.paths().isEmpty()) {
                errors.add("Source '" + entry.getKey() + "' has no paths defined");
            }
            for (final String path : source.paths()) {
                try {
                    io.contractguardian.util.GlobMatcher.globToRegex(path);
                } catch (Exception e) {
                    errors.add("Source '" + entry.getKey() + "' has invalid glob pattern: " + path);
                }
            }
        }

        return errors;
    }
}
