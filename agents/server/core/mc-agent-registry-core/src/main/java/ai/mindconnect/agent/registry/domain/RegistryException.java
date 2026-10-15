package ai.mindconnect.agent.registry.domain;

/**
 * A registry could not be read or an import could not be started: an unknown
 * source, an index that is not there or does not parse, a repository behind a
 * token this installation does not have.
 *
 * <p>Not thrown for a single member that fails to install — that is a
 * {@link ImportStatus#FAILED} line in the report, because the other members
 * still installed.
 */
public class RegistryException extends RuntimeException {

    public RegistryException(String message) {
        super(message);
    }

    public RegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
