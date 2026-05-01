package io.contractguardian.policy;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses a {@code .contract-guardian.yml} policy file into a {@link PolicyConfig}.
 */
public class PolicyParser {

    /**
     * Parses the given config file into a {@link PolicyConfig}.
     *
     * @param configFile path to the YAML config file
     * @return the parsed policy configuration
     * @throws PolicyParseException if the file cannot be read or parsed
     */
    @SuppressWarnings("unchecked")
    public PolicyConfig parse(final Path configFile) {
        try (InputStream in = Files.newInputStream(configFile)) {
            final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            final Map<String, Object> raw = yaml.load(in);
            if (raw == null) {
                throw new PolicyParseException("Config file is empty: " + configFile);
            }
            return buildConfig(raw);
        } catch (IOException e) {
            throw new PolicyParseException("Failed to read config file: " + configFile, e);
        } catch (PolicyParseException e) {
            throw e;
        } catch (Exception e) {
            throw new PolicyParseException("Failed to parse config file: " + configFile, e);
        }
    }

    /**
     * Parses the given config file, or returns a default config if the file does not exist.
     *
     * @param configFile path to the YAML config file
     * @return the parsed or default policy configuration
     * @throws PolicyParseException if the file exists but cannot be parsed
     */
    public PolicyConfig parseOrDefault(final Path configFile) {
        if (Files.exists(configFile)) {
            return parse(configFile);
        }
        return PolicyConfig.defaultConfig();
    }

    @SuppressWarnings("unchecked")
    private PolicyConfig buildConfig(final Map<String, Object> raw) {
        final String version = String.valueOf(raw.getOrDefault("version", "1"));

        final Map<String, SourceConfig> sources = parseSources(
                (Map<String, Object>) raw.getOrDefault("sources", Map.of()));

        final Map<String, RuleConfig> rules = parseRules(
                (Map<String, Object>) raw.getOrDefault("rules", Map.of()));

        final GateConfig gate = parseGate(
                (Map<String, Object>) raw.getOrDefault("gate", Map.of()));

        return new PolicyConfig(version, sources, rules, gate);
    }

    @SuppressWarnings("unchecked")
    private Map<String, SourceConfig> parseSources(final Map<String, Object> raw) {
        final Map<String, SourceConfig> sources = new LinkedHashMap<>();

        for (final Map.Entry<String, Object> entry : raw.entrySet()) {
            final String name = entry.getKey();
            final Map<String, Object> sourceRaw = (Map<String, Object>) entry.getValue();

            final List<String> paths = (List<String>) sourceRaw.getOrDefault("paths", List.of());
            final String baseline = (String) sourceRaw.getOrDefault("baseline", "branch:main");

            sources.put(name, new SourceConfig(name, paths, baseline));
        }

        return sources;
    }

    @SuppressWarnings("unchecked")
    private Map<String, RuleConfig> parseRules(final Map<String, Object> raw) {
        final Map<String, RuleConfig> rules = new LinkedHashMap<>();

        if (raw.containsKey("kafka")) {
            final Map<String, Object> kafkaRaw = (Map<String, Object>) raw.get("kafka");
            rules.put("kafka", parseKafkaRules(kafkaRaw));
        }

        if (raw.containsKey("rest")) {
            final Map<String, Object> restRaw = (Map<String, Object>) raw.get("rest");
            rules.put("rest", parseRestRules(restRaw));
        }

        if (raw.containsKey("database")) {
            final Map<String, Object> dbRaw = (Map<String, Object>) raw.get("database");
            rules.put("database", parseDbRules(dbRaw));
        }

        return rules;
    }

    @SuppressWarnings("unchecked")
    private KafkaRuleConfig parseKafkaRules(final Map<String, Object> raw) {
        final String compatStr = (String) raw.getOrDefault("compatibility", "BACKWARD");
        final KafkaRuleConfig.CompatibilityMode compatibility;
        try {
            compatibility = KafkaRuleConfig.CompatibilityMode.valueOf(compatStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PolicyParseException("Invalid Kafka compatibility mode: " + compatStr);
        }

        final List<CompatibilityOverride> overrides = new ArrayList<>();
        final List<Map<String, Object>> overrideList =
                (List<Map<String, Object>>) raw.getOrDefault("overrides", List.of());

        for (final Map<String, Object> overrideRaw : overrideList) {
            final String topic = (String) overrideRaw.get("topic");
            final String overrideCompat = (String) overrideRaw.get("compatibility");
            final KafkaRuleConfig.CompatibilityMode mode;
            try {
                mode = KafkaRuleConfig.CompatibilityMode.valueOf(overrideCompat.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new PolicyParseException(
                        "Invalid compatibility mode in override for topic '" + topic + "': " + overrideCompat);
            }
            overrides.add(new CompatibilityOverride(topic, mode));
        }

        final int nVersionCompatibility =
                (Integer) raw.getOrDefault("n-version-compatibility", 1);

        return new KafkaRuleConfig(compatibility, overrides, nVersionCompatibility);
    }

    @SuppressWarnings("unchecked")
    private RestRuleConfig parseRestRules(final Map<String, Object> raw) {
        final List<String> breaking =
                (List<String>) raw.getOrDefault("breaking", List.of());
        final List<String> warning =
                (List<String>) raw.getOrDefault("warning", List.of());
        final List<String> ignore =
                (List<String>) raw.getOrDefault("ignore", List.of());

        final int nVersionCompatibility =
                (Integer) raw.getOrDefault("n-version-compatibility", 1);

        return new RestRuleConfig(breaking, warning, ignore, nVersionCompatibility);
    }

    @SuppressWarnings("unchecked")
    private DbRuleConfig parseDbRules(final Map<String, Object> raw) {
        final List<String> breaking =
                (List<String>) raw.getOrDefault("breaking", List.of());
        final List<String> warning =
                (List<String>) raw.getOrDefault("warning", List.of());
        final boolean requireExpandMigrateContract =
                Boolean.TRUE.equals(raw.get("require-expand-migrate-contract"));
        final int nVersionCompatibility =
                (Integer) raw.getOrDefault("n-version-compatibility", 1);

        return new DbRuleConfig(breaking, warning, requireExpandMigrateContract, nVersionCompatibility);
    }

    @SuppressWarnings("unchecked")
    private GateConfig parseGate(final Map<String, Object> raw) {
        final String blockOnStr = (String) raw.getOrDefault("block-on", "breaking");
        final GateConfig.BlockOn blockOn;
        try {
            blockOn = GateConfig.BlockOn.valueOf(blockOnStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PolicyParseException("Invalid gate block-on value: " + blockOnStr);
        }

        final boolean approvalRequired = Boolean.TRUE.equals(raw.get("approval-required-to-bypass"));
        final String approvalLabel = (String) raw.get("approval-label");
        final List<String> approvers = (List<String>) raw.getOrDefault("approvers", List.of());

        return new GateConfig(blockOn, approvalRequired, approvalLabel, approvers);
    }
}
