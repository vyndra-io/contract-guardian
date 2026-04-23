package io.contractguardian.policy;

/**
 * Thrown when a policy configuration file cannot be parsed.
 */
public class PolicyParseException extends RuntimeException {

    /**
     * Creates an exception with a message.
     *
     * @param message description of the parse failure
     */
    public PolicyParseException(final String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and cause.
     *
     * @param message description of the parse failure
     * @param cause   the underlying exception
     */
    public PolicyParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
