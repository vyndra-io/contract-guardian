package io.contractguardian.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads OpenAPI specification files from disk.
 *
 * <p>Returns the raw file content as a string for use with the openapi-diff library,
 * which handles parsing internally.
 */
public class OpenApiSchemaLoader {

    /**
     * Reads the content of an OpenAPI specification file.
     *
     * @param specFile path to the {@code .yaml} or {@code .json} OpenAPI file
     * @return the raw file content
     * @throws OpenApiScanException if the file cannot be read
     */
    public String load(final Path specFile) {
        try {
            return Files.readString(specFile);
        } catch (IOException e) {
            throw new OpenApiScanException("Failed to read OpenAPI spec file: " + specFile, e);
        }
    }
}
