package io.contractguardian.kafka.proto;

import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.internal.parser.ProtoFileElement;
import com.squareup.wire.schema.internal.parser.ProtoParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and parses Protobuf schema ({@code .proto}) files from disk.
 */
public class ProtobufSchemaLoader {

    /**
     * Loads and parses a Protobuf schema file.
     *
     * @param schemaFile path to the {@code .proto} file
     * @return the parsed proto file element
     * @throws ProtobufScanException if the file cannot be read or parsed
     */
    public ProtoFileElement load(final Path schemaFile) {
        try {
            final String content = Files.readString(schemaFile);
            final Location location = Location.get(schemaFile.toString());
            return new ProtoParser(location, content.toCharArray()).readProtoFile();
        } catch (IOException e) {
            throw new ProtobufScanException("Failed to read proto file: " + schemaFile, e);
        } catch (Exception e) {
            throw new ProtobufScanException(
                    "Failed to parse proto file: " + schemaFile + " - " + e.getMessage(), e);
        }
    }

    /**
     * Thrown when a Protobuf schema file cannot be loaded or parsed.
     */
    public static class ProtobufScanException extends RuntimeException {

        /**
         * Creates an exception with a message and cause.
         *
         * @param message description of the failure
         * @param cause   the underlying exception
         */
        public ProtobufScanException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
