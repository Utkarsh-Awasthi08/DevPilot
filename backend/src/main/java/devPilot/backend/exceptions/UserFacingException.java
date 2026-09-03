package devPilot.backend.exceptions;

/**
 * Marker for an exception whose message is already safe and meaningful to show directly to the
 * end user (as opposed to an arbitrary library/framework exception, whose message may contain
 * internal details and should never be shown verbatim). Thrown from application code that has
 * deliberately composed a clean, user-facing message.
 */
public class UserFacingException extends RuntimeException {
    public UserFacingException(String message) {
        super(message);
    }

    public UserFacingException(String message, Throwable cause) {
        super(message, cause);
    }
}
