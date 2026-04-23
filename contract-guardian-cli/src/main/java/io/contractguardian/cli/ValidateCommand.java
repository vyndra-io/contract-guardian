package io.contractguardian.cli;

import io.contractguardian.policy.PolicyConfig;
import io.contractguardian.policy.PolicyParseException;
import io.contractguardian.policy.PolicyParser;
import io.contractguardian.policy.PolicyValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Validates a {@code .contract-guardian.yml} config file for syntax and structural errors.
 */
@Command(name = "validate", description = "Validate config file syntax")
public class ValidateCommand implements Callable<Integer> {

    @Option(names = {"--config", "-c"}, defaultValue = ".contract-guardian.yml",
            description = "Path to config file")
    private String configFile;

    @Override
    public Integer call() {
        final PolicyParser parser = new PolicyParser();
        final PolicyValidator validator = new PolicyValidator();
        try {
            final PolicyConfig config = parser.parse(Path.of(configFile));
            final List<String> errors = validator.validate(config);
            if (errors.isEmpty()) {
                System.out.println("Config is valid.");
                return 0;
            } else {
                errors.forEach(e -> System.err.println("ERROR: " + e));
                return 1;
            }
        } catch (PolicyParseException e) {
            System.err.println("Failed to parse config: " + e.getMessage());
            return 1;
        }
    }
}
