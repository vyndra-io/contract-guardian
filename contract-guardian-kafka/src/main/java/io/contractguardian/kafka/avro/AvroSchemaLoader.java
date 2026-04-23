package io.contractguardian.kafka.avro;

import org.apache.avro.Schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and parses Apache Avro schema files.
 */
public class AvroSchemaLoader {

    /**
     * Loads an Avro schema from the given file.
     *
     * @param schemaFile path to the {@code .avsc} file
     * @return the parsed Avro schema
     * @throws AvroScanException if the file cannot be read or parsed
     */
    public Schema load(final Path schemaFile) {
        try {
            final String content = Files.readString(schemaFile);
            return new Schema.Parser().parse(content);
        } catch (IOException e) {
            throw new AvroScanException("Failed to read schema file: " + schemaFile, e);
        } catch (Exception e) {
            throw new AvroScanException("Failed to parse Avro schema: " + schemaFile + " - " + e.getMessage(), e);
        }
    }

    /**
     * Thrown when an Avro schema file cannot be loaded or parsed.
     */
    public static class AvroScanException extends RuntimeException {

        /**
         * Creates an exception with a message and cause.
         *
         * @param message description of the failure
         * @param cause   the underlying exception
         */
        public AvroScanException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
