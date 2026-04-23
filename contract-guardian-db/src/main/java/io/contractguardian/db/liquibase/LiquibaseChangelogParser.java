package io.contractguardian.db.liquibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Liquibase changelog files in XML, YAML, and JSON formats into
 * a list of {@link LiquibaseChangeset} objects.
 *
 * <p>Supported file extensions: {@code .xml}, {@code .yaml}, {@code .yml}, {@code .json}.
 */
class LiquibaseChangelogParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Parses a Liquibase changelog file and returns all changesets it contains.
     *
     * @param file the changelog file to parse
     * @return the parsed changesets, or an empty list if the file cannot be understood
     * @throws UncheckedIOException if the file cannot be read
     */
    List<LiquibaseChangeset> parse(final Path file) {
        final String name = file.getFileName().toString().toLowerCase();
        try {
            if (name.endsWith(".xml")) {
                return parseXml(file);
            }
            if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                return parseYaml(file);
            }
            if (name.endsWith(".json")) {
                return parseJson(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read changelog: " + file, e);
        } catch (Exception e) {
            // Malformed file — return empty so the scanner can report a parse-skipped finding
            return List.of();
        }
        return List.of();
    }

    // -------------------------------------------------------------------------
    // XML
    // -------------------------------------------------------------------------

    private List<LiquibaseChangeset> parseXml(final Path file) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        // Disable external entity resolution (XXE protection)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final Document doc = factory.newDocumentBuilder().parse(file.toFile());
        doc.getDocumentElement().normalize();

        final List<LiquibaseChangeset> changesets = new ArrayList<>();
        final NodeList changeSetNodes = doc.getElementsByTagName("changeSet");

        for (int i = 0; i < changeSetNodes.getLength(); i++) {
            final Element changeSetEl = (Element) changeSetNodes.item(i);
            final String id = changeSetEl.getAttribute("id");
            final String author = changeSetEl.getAttribute("author");
            final List<LiquibaseChange> changes = extractXmlChanges(changeSetEl);
            changesets.add(new LiquibaseChangeset(id, author, changes));
        }

        return changesets;
    }

    private List<LiquibaseChange> extractXmlChanges(final Element changeSetEl) {
        final List<LiquibaseChange> changes = new ArrayList<>();
        final NodeList children = changeSetEl.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            final Element el = (Element) child;
            final String changeType = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
            final Map<String, Object> attrs = extractXmlAttributes(el, changeType);
            changes.add(new LiquibaseChange(changeType, attrs));
        }

        return changes;
    }

    private Map<String, Object> extractXmlAttributes(final Element el, final String changeType) {
        final Map<String, Object> attrs = new LinkedHashMap<>();

        // Copy element-level attributes (tableName, columnName, etc.)
        final NamedNodeMap xmlAttrs = el.getAttributes();
        for (int i = 0; i < xmlAttrs.getLength(); i++) {
            final Node attr = xmlAttrs.item(i);
            attrs.put(attr.getNodeName(), attr.getNodeValue());
        }

        // For addColumn: parse nested <column> children into a columns list
        if ("addColumn".equals(changeType)) {
            attrs.put("columns", extractXmlColumns(el));
        }

        return attrs;
    }

    private List<Map<String, Object>> extractXmlColumns(final Element addColumnEl) {
        final List<Map<String, Object>> columns = new ArrayList<>();
        final NodeList children = addColumnEl.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            final Element colEl = (Element) child;
            final Map<String, Object> colAttrs = new LinkedHashMap<>();

            // Copy column attributes (name, type, defaultValue, etc.)
            final NamedNodeMap xmlAttrs = colEl.getAttributes();
            for (int j = 0; j < xmlAttrs.getLength(); j++) {
                final Node attr = xmlAttrs.item(j);
                colAttrs.put(attr.getNodeName(), attr.getNodeValue());
            }

            // Parse nested <constraints> element
            final NodeList constraintNodes = colEl.getElementsByTagName("constraints");
            if (constraintNodes.getLength() > 0) {
                final Element constraintsEl = (Element) constraintNodes.item(0);
                final Map<String, Object> constraints = new LinkedHashMap<>();
                final NamedNodeMap constraintAttrs = constraintsEl.getAttributes();
                for (int j = 0; j < constraintAttrs.getLength(); j++) {
                    final Node attr = constraintAttrs.item(j);
                    constraints.put(attr.getNodeName(), attr.getNodeValue());
                }
                colAttrs.put("constraints", constraints);
            }

            columns.add(colAttrs);
        }

        return columns;
    }

    // -------------------------------------------------------------------------
    // YAML
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<LiquibaseChangeset> parseYaml(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            final Object raw = yaml.load(in);
            return extractChangesetsFromStructure(raw);
        }
    }

    // -------------------------------------------------------------------------
    // JSON
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<LiquibaseChangeset> parseJson(final Path file) throws IOException {
        final Object raw = JSON_MAPPER.readValue(file.toFile(), Object.class);
        return extractChangesetsFromStructure(raw);
    }

    // -------------------------------------------------------------------------
    // Shared YAML/JSON structure extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts changesets from the parsed YAML/JSON structure.
     *
     * <p>Handles both the map form ({@code databaseChangeLog: [...]}) and the
     * bare list form ({@code [{changeSet: {...}}, ...]}).
     *
     * @param raw the parsed object from SnakeYAML or Jackson
     * @return the list of extracted changesets
     */
    @SuppressWarnings("unchecked")
    private List<LiquibaseChangeset> extractChangesetsFromStructure(final Object raw) {
        if (raw instanceof List<?> list) {
            return extractChangesetsFromList((List<Object>) list);
        }
        if (raw instanceof Map<?, ?> map) {
            final Object changeLog = map.get("databaseChangeLog");
            if (changeLog instanceof List<?> list) {
                return extractChangesetsFromList((List<Object>) list);
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<LiquibaseChangeset> extractChangesetsFromList(final List<Object> entries) {
        final List<LiquibaseChangeset> changesets = new ArrayList<>();

        for (final Object entry : entries) {
            if (!(entry instanceof Map<?, ?> entryMap)) {
                continue;
            }
            final Object changeSetRaw = entryMap.get("changeSet");
            if (!(changeSetRaw instanceof Map<?, ?> csMap)) {
                continue;
            }
            final String id = String.valueOf(csMap.get("id"));
            final String author = String.valueOf(csMap.get("author"));
            final List<LiquibaseChange> changes = extractYamlChanges(
                    (Map<String, Object>) csMap);
            changesets.add(new LiquibaseChangeset(id, author, changes));
        }

        return changesets;
    }

    @SuppressWarnings("unchecked")
    private List<LiquibaseChange> extractYamlChanges(final Map<String, Object> csMap) {
        final List<LiquibaseChange> changes = new ArrayList<>();
        final Object changesRaw = csMap.get("changes");
        if (!(changesRaw instanceof List<?> changeList)) {
            return changes;
        }

        for (final Object changeEntry : changeList) {
            if (!(changeEntry instanceof Map<?, ?> changeMap)) {
                continue;
            }
            // Each entry is a single-key map: {changeType: {attrs...}}
            for (final Map.Entry<?, ?> entry : changeMap.entrySet()) {
                final String changeType = String.valueOf(entry.getKey());
                final Map<String, Object> attrs = normalizeYamlChangeAttrs(
                        changeType, (Map<String, Object>) entry.getValue());
                changes.add(new LiquibaseChange(changeType, attrs));
            }
        }

        return changes;
    }

    /**
     * Normalizes YAML/JSON change attributes so the column list in {@code addColumn} uses
     * the same flat structure as the XML parser produces.
     *
     * <p>In YAML/JSON, addColumn columns are wrapped: {@code [{column: {name:..., constraints:...}}]}.
     * This method unwraps the {@code column} key so the analyzer sees a flat list of column maps.
     *
     * @param changeType the change type (used to detect addColumn)
     * @param raw        the raw attribute map
     * @return the normalized attribute map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeYamlChangeAttrs(final String changeType,
                                                          final Map<String, Object> raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!"addColumn".equals(changeType)) {
            return raw;
        }

        // Unwrap [{column: {name:..., ...}}] → [{name:..., ...}]
        final Object columnsRaw = raw.get("columns");
        if (!(columnsRaw instanceof List<?> columnList)) {
            return raw;
        }

        final List<Map<String, Object>> normalized = new ArrayList<>();
        for (final Object colEntry : columnList) {
            if (!(colEntry instanceof Map<?, ?> colMap)) {
                continue;
            }
            final Object colAttrs = colMap.get("column");
            if (colAttrs instanceof Map<?, ?> flatCol) {
                normalized.add((Map<String, Object>) flatCol);
            }
        }

        final Map<String, Object> result = new LinkedHashMap<>(raw);
        result.put("columns", normalized);
        return result;
    }
}
