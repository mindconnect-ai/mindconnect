package ai.mindconnect.workflow.execution;

/**
 * Thrown by {@link CodeStep} when script compilation or evaluation fails.
 */
public class CodeStepException extends RuntimeException {

    private final String language;
    private final String code;

    public CodeStepException(String language, String code, Throwable cause) {
        super("Code step failed [" + language + "]: " + cause.getMessage(), cause);
        this.language = language;
        this.code = code;
    }

    public String getLanguage() {
        return language;
    }

    public String getCode() {
        return code;
    }
}
