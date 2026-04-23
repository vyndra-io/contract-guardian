package io.contractguardian.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates a starter {@code .contract-guardian.yml} by detecting schema files in the repository.
 */
@Command(name = "init", description = "Generate a starter .contract-guardian.yml")
public class InitCommand implements Callable<Integer> {

    @Option(names = {"--working-dir", "-w"},
            description = "Working directory (defaults to current)")
    private Path workingDir;

    @Override
    public Integer call() throws IOException {
        final Path root = workingDir != null ? workingDir : Path.of(".").toAbsolutePath().normalize();
        final Path configFile = root.resolve(".contract-guardian.yml");

        if (Files.exists(configFile)) {
            System.err.println("Config file already exists: " + configFile);
            return 1;
        }

        final List<Path> avroFiles = findFiles(root, ".avsc");
        final List<Path> jsonSchemaFiles = findFiles(root, ".json");
        final List<Path> protoFiles = findFiles(root, ".proto");

        final List<Path> allYamlFiles = new ArrayList<>();
        allYamlFiles.addAll(findFiles(root, ".yaml"));
        allYamlFiles.addAll(findFiles(root, ".yml"));
        final Map<Boolean, List<Path>> partitionedYaml = allYamlFiles.stream()
                .collect(Collectors.partitioningBy(this::isLiquibaseFile));
        final List<Path> liquibaseYamlFiles = partitionedYaml.get(true);
        final List<Path> openApiFiles = partitionedYaml.get(false);

        final List<Path> liquibaseXmlFiles = findFiles(root, ".xml").stream()
                .filter(this::isLiquibaseFile)
                .toList();
        final List<Path> sqlFiles = findFiles(root, ".sql");

        final StringBuilder config = new StringBuilder();
        config.append("version: \"1\"\n\n");
        config.append("sources:\n");

        final boolean hasKafkaSources = !avroFiles.isEmpty() || !jsonSchemaFiles.isEmpty() || !protoFiles.isEmpty();
        final boolean hasDbSources = !sqlFiles.isEmpty() || !liquibaseXmlFiles.isEmpty() || !liquibaseYamlFiles.isEmpty();

        if (hasKafkaSources) {
            config.append("  kafka:\n");
            config.append("    paths:\n");
            if (!avroFiles.isEmpty()) {
                final String prefix = detectPrefix(avroFiles, root);
                config.append("      - \"").append(prefix).append("**/*.avsc\"\n");
            }
            if (!jsonSchemaFiles.isEmpty()) {
                final String prefix = detectPrefix(jsonSchemaFiles, root);
                config.append("      - \"").append(prefix).append("**/*.json\"\n");
            }
            if (!protoFiles.isEmpty()) {
                final String prefix = detectPrefix(protoFiles, root);
                config.append("      - \"").append(prefix).append("**/*.proto\"\n");
            }
            config.append("    baseline: branch:main\n\n");
        }

        if (!openApiFiles.isEmpty()) {
            final String prefix = detectPrefix(openApiFiles, root);
            config.append("  rest:\n");
            config.append("    paths:\n");
            config.append("      - \"").append(prefix).append("**/*.yaml\"\n");
            config.append("    baseline: branch:main\n\n");
        }

        if (hasDbSources) {
            config.append("  database:\n");
            config.append("    paths:\n");
            if (!sqlFiles.isEmpty()) {
                final String prefix = detectPrefix(sqlFiles, root);
                config.append("      - \"").append(prefix).append("**/*.sql\"\n");
            }
            if (!liquibaseXmlFiles.isEmpty()) {
                final String prefix = detectPrefix(liquibaseXmlFiles, root);
                config.append("      - \"").append(prefix).append("**/*.xml\"\n");
            }
            if (!liquibaseYamlFiles.isEmpty()) {
                final String prefix = detectPrefix(liquibaseYamlFiles, root);
                config.append("      - \"").append(prefix).append("**/*.yaml\"\n");
            }
            config.append("    baseline: branch:main\n\n");
        }

        config.append("rules:\n");
        if (hasKafkaSources) {
            config.append("  kafka:\n");
            config.append("    compatibility: BACKWARD\n");
        }
        if (!openApiFiles.isEmpty()) {
            config.append("  rest:\n");
            config.append("    breaking:\n");
            config.append("      - endpoint-removed\n");
            config.append("      - required-param-added\n");
            config.append("      - response-field-removed\n");
            config.append("      - response-field-type-changed\n");
            config.append("      - status-code-removed\n");
            config.append("    warning:\n");
            config.append("      - response-field-deprecated\n");
            config.append("      - parameter-renamed\n");
        }
        if (hasDbSources) {
            config.append("  database:\n");
            config.append("    breaking:\n");
            config.append("      - column-removed\n");
            config.append("      - table-removed\n");
            config.append("      - not-null-added-no-default\n");
            config.append("      - column-type-changed\n");
            config.append("    warning:\n");
            config.append("      - column-renamed\n");
            config.append("      - migration-modified\n");
        }
        config.append("\ngate:\n");
        config.append("  block-on: breaking\n");

        Files.writeString(configFile, config.toString());
        System.out.println("Created " + configFile);
        System.out.println("Detected " + avroFiles.size() + " Avro schema file(s)");
        if (!jsonSchemaFiles.isEmpty()) {
            System.out.println("Detected " + jsonSchemaFiles.size() + " JSON Schema file(s)");
        }
        if (!protoFiles.isEmpty()) {
            System.out.println("Detected " + protoFiles.size() + " Protobuf schema file(s)");
        }

        if (!openApiFiles.isEmpty()) {
            System.out.println("Detected " + openApiFiles.size()
                    + " YAML file(s) — added as REST source");
        }
        if (!sqlFiles.isEmpty()) {
            System.out.println("Detected " + sqlFiles.size() + " SQL migration file(s)");
        }
        if (!liquibaseXmlFiles.isEmpty()) {
            System.out.println("Detected " + liquibaseXmlFiles.size() + " Liquibase XML changelog(s)");
        }
        if (!liquibaseYamlFiles.isEmpty()) {
            System.out.println("Detected " + liquibaseYamlFiles.size() + " Liquibase YAML changelog(s)");
        }

        return 0;
    }

    private boolean isLiquibaseFile(final Path file) {
        try {
            return Files.readString(file).contains("databaseChangeLog");
        } catch (IOException e) {
            return false;
        }
    }

    private List<Path> findFiles(final Path root, final String extension) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(extension))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .toList();
        }
    }

    private String detectPrefix(final List<Path> files, final Path root) {
        if (files.isEmpty()) {
            return "";
        }

        final List<String> relativePaths = new ArrayList<>();
        for (final Path f : files) {
            relativePaths.add(root.relativize(f).toString().replace('\\', '/'));
        }

        if (relativePaths.size() == 1) {
            final String path = relativePaths.get(0);
            final int lastSlash = path.lastIndexOf('/');
            return lastSlash >= 0 ? path.substring(0, lastSlash + 1) : "";
        }

        final String first = relativePaths.get(0);
        int commonEnd = 0;
        outer:
        for (int i = 0; i < first.length(); i++) {
            final char c = first.charAt(i);
            for (final String other : relativePaths) {
                if (i >= other.length() || other.charAt(i) != c) {
                    break outer;
                }
            }
            if (c == '/') {
                commonEnd = i + 1;
            }
        }

        return first.substring(0, commonEnd);
    }
}
