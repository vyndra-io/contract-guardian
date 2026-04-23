package io.contractguardian.kafka.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads and parses JSON Schema files from disk.
 */
public class JsonSchemaLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Loads a JSON Schema from the given file.
     *
     * @param schemaFile path to the {@code .json} JSON Schema file
     * @return the parsed JSON tree
     * @throws JsonScanException if the file cannot be read or parsed
     */
    public JsonNode load(final Path schemaFile) {
        try {
            return MAPPER.readTree(schemaFile.toFile());
        } catch (IOException e) {
            throw new JsonScanException("Failed to read JSON Schema file: " + schemaFile, e);
        }
    }

    /**
     * Checks whether the given JSON node looks like a JSON Schema document.
     *
     * <p>Detects the presence of a {@code $schema} keyword, or a combination
     * of {@code type} and {@code properties} fields.
     *
     * @param node the JSON node to inspect
     * @return {@code true} if the node appears to be a JSON Schema
     */
    public boolean isJsonSchema(final JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (node.has("$schema")) {
            return true;
        }
        return node.has("type") && node.has("properties");
    }

    /**
     * Thrown when a JSON Schema file cannot be loaded or parsed.
     */
    public static class JsonScanException extends RuntimeException {

        /**
         * Creates an exception with a message and cause.
         *
         * @param message description of the failure
         * @param cause   the underlying exception
         */
        public JsonScanException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
