package za.co.oip.common.error;

/**
 * Application exceptions that map to well-defined HTTP responses in {@link GlobalExceptionHandler}.
 */
public final class Exceptions {

    private Exceptions() {
    }

    /** The resource does not exist, or exists but is not visible to the caller's tenant (404). */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String resource, Object id) {
            super(resource + " not found: " + id);
        }
    }

    /** The request conflicts with the current state, e.g. a duplicate email (409). */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** The request is well formed but breaks a business rule (422). */
    public static class BusinessRuleException extends RuntimeException {
        public BusinessRuleException(String message) {
            super(message);
        }
    }

    /** Authentication failed (401). The message must not reveal which credential was wrong. */
    public static class AuthenticationFailedException extends RuntimeException {
        public AuthenticationFailedException(String message) {
            super(message);
        }
    }

    /** The caller is authenticated but not allowed to perform the action (403). */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    /** Too many requests from a client (429). */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }
}
