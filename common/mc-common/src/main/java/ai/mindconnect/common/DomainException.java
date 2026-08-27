package ai.mindconnect.common;

public class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static DomainException notFound(String entity, String id) {
        return new DomainException("NOT_FOUND", entity + " not found: " + id);
    }

    public static DomainException invalid(String message) {
        return new DomainException("INVALID", message);
    }
}
