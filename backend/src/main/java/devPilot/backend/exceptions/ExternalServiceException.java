package devPilot.backend.exceptions;

/**
 * A call to an upstream service (GitHub, Gemini, Groq, ...) failed. The message is always a
 * clean, user-facing sentence — never the raw provider response body — translated by the
 * caller from the upstream HTTP status.
 */
public class ExternalServiceException extends UserFacingException {
    private final String serviceName;
    private final int upstreamStatus;

    public ExternalServiceException(String serviceName, int upstreamStatus, String message) {
        super(message);
        this.serviceName = serviceName;
        this.upstreamStatus = upstreamStatus;
    }

    public String getServiceName() {
        return serviceName;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }
}
