package ai.mindconnect.agent.runtime.feature;

/**
 * A feature could not be installed or resolved: a dependency is not
 * installed, a bean nobody registered was asked for, two beans need each
 * other to be built. The message names both sides.
 */
public class FeatureException extends RuntimeException {

    public FeatureException(String message) {
        super(message);
    }

    public FeatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
