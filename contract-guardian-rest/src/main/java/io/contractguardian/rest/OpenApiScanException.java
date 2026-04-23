package io.contractguardian.rest;

/**
 * Thrown when an OpenAPI specification file cannot be loaded or compared.
 */
public class OpenApiScanException extends RuntimeException {

    /**
     * Creates an exception with a message and cause.
     *
     * @param message description of the failure
     * @param cause   the underlying exception
     */
    public OpenApiScanException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates an exception with a message only.
     *
     * @param message description of the failure
     */
    public OpenApiScanException(final String message) {
        super(message);
    }
}
